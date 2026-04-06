package com.example.callmemorecorder.ui.history

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.callmemorecorder.data.AppContainer
import com.example.callmemorecorder.data.repository.RecordRepository
import com.example.callmemorecorder.domain.model.RecordItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

/** 再生状態を保持するデータクラス */
data class PlaybackState(
    val recordId: String? = null,       // 再生中のレコードID (null=停止)
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L
) {
    val progress: Float
        get() = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f
}

class HistoryViewModel(
    private val recordRepository: RecordRepository,
    private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "HistoryViewModel"
        private const val SEEK_STEP_MS = 10_000  // 早送り/早戻し 10秒

        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HistoryViewModel(container.recordRepository, container.context) as T
        }
    }

    val records: StateFlow<List<RecordItem>> = recordRepository.getAllRecords()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // ── 再生状態 ──────────────────────────────────────────
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    // ── 削除結果 ─────────────────────────────────────────
    private val _deleteResult = MutableStateFlow<String?>(null)
    val deleteResult: StateFlow<String?> = _deleteResult.asStateFlow()

    // ── 再生 / 停止 ───────────────────────────────────────

    /** 再生開始 or 停止トグル */
    fun togglePlayback(record: RecordItem) {
        val path = record.localPath ?: return
        if (!File(path).exists()) {
            Log.w(TAG, "File not found: $path")
            return
        }

        if (_playbackState.value.recordId == record.id && _playbackState.value.isPlaying) {
            // 同じ曲が再生中 → 一時停止
            pausePlayback()
        } else if (_playbackState.value.recordId == record.id && !_playbackState.value.isPlaying) {
            // 同じ曲が一時停止中 → 再開
            resumePlayback()
        } else {
            // 別の曲 → 新規再生
            startPlayback(record)
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
                recordId = record.id,
                isPlaying = true,
                currentPositionMs = 0L,
                durationMs = if (durationMs > 0) durationMs else record.durationMs
            )
            startProgressTracking()
            Log.i(TAG, "Playback started: ${record.id}, duration=${durationMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "startPlayback error", e)
            mediaPlayer?.release()
            mediaPlayer = null
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

    /** 完全停止 */
    fun stopPlayback() {
        stopProgressTracking()
        mediaPlayer?.release()
        mediaPlayer = null
        _playbackState.value = PlaybackState()
    }

    // ── シーク操作 ────────────────────────────────────────

    /** スライダーでシーク (0f〜1f) */
    fun seekTo(progress: Float) {
        val mp = mediaPlayer ?: return
        val duration = _playbackState.value.durationMs
        if (duration <= 0) return
        val targetMs = (progress * duration).toLong().coerceIn(0, duration)
        mp.seekTo(targetMs.toInt())
        _playbackState.update { it.copy(currentPositionMs = targetMs) }
    }

    /** 早送り (+10秒) */
    fun seekForward() {
        val mp = mediaPlayer ?: return
        val duration = _playbackState.value.durationMs
        val newPos = (mp.currentPosition + SEEK_STEP_MS).coerceAtMost(duration.toInt())
        mp.seekTo(newPos)
        _playbackState.update { it.copy(currentPositionMs = newPos.toLong()) }
    }

    /** 早戻し (-10秒) */
    fun seekBackward() {
        val mp = mediaPlayer ?: return
        val newPos = (mp.currentPosition - SEEK_STEP_MS).coerceAtLeast(0)
        mp.seekTo(newPos)
        _playbackState.update { it.copy(currentPositionMs = newPos.toLong()) }
    }

    // ── 進捗トラッキング ──────────────────────────────────

    private fun startProgressTracking() {
        stopProgressTracking()
        progressJob = viewModelScope.launch {
            while (true) {
                delay(200)
                val mp = mediaPlayer ?: break
                if (!mp.isPlaying) break
                val pos = mp.currentPosition.toLong()
                _playbackState.update { it.copy(currentPositionMs = pos) }
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    // ── 削除 ─────────────────────────────────────────────

    fun deleteRecord(id: String) {
        viewModelScope.launch {
            try {
                recordRepository.deleteById(id)
                _deleteResult.value = "deleted"
            } catch (e: Exception) {
                _deleteResult.value = "error: ${e.message}"
            }
        }
    }

    fun clearDeleteResult() { _deleteResult.value = null }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }
}
