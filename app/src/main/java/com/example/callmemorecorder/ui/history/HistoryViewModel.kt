package com.example.callmemorecorder.ui.history

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.callmemorecorder.data.AppContainer
import com.example.callmemorecorder.data.repository.RecordRepository
import com.example.callmemorecorder.domain.model.CallDirection
import com.example.callmemorecorder.domain.model.RecordItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

/** 再生状態を保持するデータクラス */
data class PlaybackState(
    val recordId: String? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L
) {
    val progress: Float
        get() = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f
}

/** 履歴検索・絞り込みの条件 */
data class SearchFilter(
    val query: String = "",                  // 名前 or 番号のフリーワード
    val dateFrom: Long? = null,              // 開始日時 (ms epoch)
    val dateTo: Long? = null,                // 終了日時 (ms epoch)
    val directionFilter: CallDirection? = null  // null=すべて
)

class HistoryViewModel(
    private val recordRepository: RecordRepository,
    private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "HistoryViewModel"
        private const val SEEK_STEP_MS = 10_000

        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HistoryViewModel(container.recordRepository, container.context) as T
        }
    }

    // ── 検索フィルター ────────────────────────────────────
    private val _filter = MutableStateFlow(SearchFilter())
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    // ── 全レコード（DB から）──────────────────────────────
    private val _allRecords: StateFlow<List<RecordItem>> = recordRepository.getAllRecords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── フィルター適用後のレコード（UI に公開）────────────
    val records: StateFlow<List<RecordItem>> = combine(_allRecords, _filter) { all, f ->
        all.filter { record -> matchesFilter(record, f) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun matchesFilter(record: RecordItem, f: SearchFilter): Boolean {
        // フリーワード（名前 or 番号）
        if (f.query.isNotBlank()) {
            val q = f.query.trim().lowercase()
            val nameMatch   = record.callerName?.lowercase()?.contains(q) == true
            val numberMatch = record.callerNumber?.contains(q) == true
            val titleMatch  = record.title.lowercase().contains(q)
            if (!nameMatch && !numberMatch && !titleMatch) return false
        }
        // 開始日
        if (f.dateFrom != null && record.createdAt < f.dateFrom) return false
        // 終了日（その日の23:59:59 まで含める）
        if (f.dateTo != null) {
            val endOfDay = f.dateTo + 24 * 60 * 60 * 1000L - 1
            if (record.createdAt > endOfDay) return false
        }
        // 発着信フィルター
        if (f.directionFilter != null && record.callDirection != f.directionFilter) return false
        return true
    }

    // ── フィルター更新 ────────────────────────────────────

    fun setQuery(q: String) {
        _filter.update { it.copy(query = q) }
    }

    fun setDateFrom(ms: Long?) {
        _filter.update { it.copy(dateFrom = ms) }
    }

    fun setDateTo(ms: Long?) {
        _filter.update { it.copy(dateTo = ms) }
    }

    fun setDirectionFilter(dir: CallDirection?) {
        _filter.update { it.copy(directionFilter = dir) }
    }

    fun clearFilter() {
        _filter.value = SearchFilter()
    }

    /** dateFrom を今日から n 日前に設定するショートカット */
    fun setDateRangeDaysAgo(days: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -days)
        }
        _filter.update { it.copy(dateFrom = cal.timeInMillis, dateTo = null) }
    }

    // ── 再生状態 ──────────────────────────────────────────
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    // ── 削除結果 ─────────────────────────────────────────
    private val _deleteResult = MutableStateFlow<String?>(null)
    val deleteResult: StateFlow<String?> = _deleteResult.asStateFlow()

    // ── 再生 / 停止 ───────────────────────────────────────

    fun togglePlayback(record: RecordItem) {
        val path = record.localPath ?: return
        if (!File(path).exists()) { Log.w(TAG, "File not found: $path"); return }
        when {
            _playbackState.value.recordId == record.id && _playbackState.value.isPlaying -> pausePlayback()
            _playbackState.value.recordId == record.id && !_playbackState.value.isPlaying -> resumePlayback()
            else -> startPlayback(record)
        }
    }

    private fun startPlayback(record: RecordItem) {
        stopPlayback()
        val path = record.localPath ?: return
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
                setOnCompletionListener {
                    _playbackState.value = PlaybackState()
                    stopProgressTracking()
                }
            }
            val durationMs = mediaPlayer?.duration?.toLong() ?: record.durationMs
            _playbackState.value = PlaybackState(
                recordId = record.id, isPlaying = true, currentPositionMs = 0L,
                durationMs = if (durationMs > 0) durationMs else record.durationMs
            )
            startProgressTracking()
        } catch (e: Exception) {
            Log.e(TAG, "startPlayback error", e)
            mediaPlayer?.release(); mediaPlayer = null
            _playbackState.value = PlaybackState()
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.pause()
        _playbackState.update { it.copy(isPlaying = false) }
        stopProgressTracking()
    }

    private fun resumePlayback() {
        mediaPlayer?.start()
        _playbackState.update { it.copy(isPlaying = true) }
        startProgressTracking()
    }

    fun stopPlayback() {
        stopProgressTracking()
        mediaPlayer?.release(); mediaPlayer = null
        _playbackState.value = PlaybackState()
    }

    fun seekTo(progress: Float) {
        val mp = mediaPlayer ?: return
        val duration = _playbackState.value.durationMs
        if (duration <= 0) return
        val targetMs = (progress * duration).toLong().coerceIn(0, duration)
        mp.seekTo(targetMs.toInt())
        _playbackState.update { it.copy(currentPositionMs = targetMs) }
    }

    fun seekForward() {
        val mp = mediaPlayer ?: return
        val duration = _playbackState.value.durationMs
        val newPos = (mp.currentPosition + SEEK_STEP_MS).coerceAtMost(duration.toInt())
        mp.seekTo(newPos)
        _playbackState.update { it.copy(currentPositionMs = newPos.toLong()) }
    }

    fun seekBackward() {
        val mp = mediaPlayer ?: return
        val newPos = (mp.currentPosition - SEEK_STEP_MS).coerceAtLeast(0)
        mp.seekTo(newPos)
        _playbackState.update { it.copy(currentPositionMs = newPos.toLong()) }
    }

    private fun startProgressTracking() {
        stopProgressTracking()
        progressJob = viewModelScope.launch {
            while (true) {
                delay(200)
                val mp = mediaPlayer ?: break
                if (!mp.isPlaying) break
                _playbackState.update { it.copy(currentPositionMs = mp.currentPosition.toLong()) }
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel(); progressJob = null
    }

    // ── 削除 ─────────────────────────────────────────────

    fun deleteRecord(id: String) {
        viewModelScope.launch {
            try { recordRepository.deleteById(id); _deleteResult.value = "deleted" }
            catch (e: Exception) { _deleteResult.value = "error: ${e.message}" }
        }
    }

    fun deleteRecords(ids: Set<String>) {
        viewModelScope.launch {
            var ok = 0; var err = 0
            for (id in ids) {
                try { recordRepository.deleteById(id); ok++ }
                catch (e: Exception) { Log.e(TAG, "deleteRecords error $id", e); err++ }
            }
            _deleteResult.value = if (err == 0) "deleted_bulk:$ok" else "error:$err"
        }
    }

    fun clearDeleteResult() { _deleteResult.value = null }

    override fun onCleared() { stopPlayback(); super.onCleared() }
}
