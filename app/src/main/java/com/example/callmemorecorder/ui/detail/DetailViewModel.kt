package com.example.callmemorecorder.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.example.callmemorecorder.data.AppContainer
import com.example.callmemorecorder.data.repository.RecordRepository
import com.example.callmemorecorder.domain.model.RecordItem
import com.example.callmemorecorder.worker.TranscriptionWorker
import com.example.callmemorecorder.worker.UploadWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DetailViewModel(
    private val recordRepository: RecordRepository,
    private val workManager: WorkManager
) : ViewModel() {

    companion object {
        fun factory(container: AppContainer, context: android.content.Context) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DetailViewModel(
                        container.recordRepository,
                        WorkManager.getInstance(context)
                    ) as T
            }
    }

    private val _record = MutableStateFlow<RecordItem?>(null)
    val record: StateFlow<RecordItem?> = _record.asStateFlow()

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    fun loadRecord(recordId: String) {
        viewModelScope.launch {
            recordRepository.getRecordById(recordId).collect { _record.value = it }
        }
    }

    fun reUpload() {
        val rec = _record.value ?: return
        val filePath = rec.localPath ?: run { _uiMessage.value = "Local file not found"; return }
        workManager.enqueue(UploadWorker.buildWorkRequest(rec.id, filePath, filePath.substringAfterLast("/")))
        _uiMessage.value = "Upload re-queued"
    }

    fun reTranscribe() {
        val rec = _record.value ?: return
        val driveFileId = rec.driveFileId ?: run { _uiMessage.value = "Drive file ID not available."; return }
        val fileName = rec.localPath?.substringAfterLast("/") ?: "recording.m4a"
        workManager.enqueue(TranscriptionWorker.buildWorkRequest(rec.id, driveFileId, fileName))
        _uiMessage.value = "Transcription re-queued"
    }

    fun deleteRecord(onDeleted: () -> Unit) {
        val rec = _record.value ?: return
        viewModelScope.launch {
            recordRepository.deleteRecord(rec)
            _uiMessage.value = "Deleted"
            onDeleted()
        }
    }

    fun clearMessage() { _uiMessage.value = null }
}
