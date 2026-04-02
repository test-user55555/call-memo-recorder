package com.example.callmemorecorder.ui.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.example.callmemorecorder.BuildConfig
import com.example.callmemorecorder.data.AppContainer
import com.example.callmemorecorder.data.repository.DriveRepository
import com.example.callmemorecorder.data.repository.RecordRepository
import com.example.callmemorecorder.domain.model.*
import com.example.callmemorecorder.service.RecordingService
import com.example.callmemorecorder.service.RecordingState
import com.example.callmemorecorder.worker.UploadWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class HomeViewModel(
    private val context: Context,
    private val recordRepository: RecordRepository,
    private val driveRepository: DriveRepository,
    private val workManager: WorkManager
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"

        fun factory(container: AppContainer, context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(
                    context = context.applicationContext,
                    recordRepository = container.recordRepository,
                    driveRepository = container.driveRepository,
                    workManager = WorkManager.getInstance(context)
                ) as T
            }
        }
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var recordingService: RecordingService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.RecordingBinder
            recordingService = binder.getService()
            isBound = true

            viewModelScope.launch {
                recordingService?.recordingState?.collect { state ->
                    _uiState.update { it.copy(recordingState = state) }
                }
            }
            viewModelScope.launch {
                recordingService?.elapsedTimeMs?.collect { elapsed ->
                    _uiState.update { it.copy(elapsedTimeMs = elapsed) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
        }
    }

    init {
        checkDriveStatus()
    }

    fun bindService() {
        val intent = Intent(context, RecordingService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
        }
    }

    fun startRecording() {
        Log.d(TAG, "startRecording called")
        val intent = RecordingService.startIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        if (!isBound) bindService()
    }

    fun stopRecording() {
        Log.d(TAG, "stopRecording called")
        val result = recordingService?.stopRecording()
        result?.let {
            when (it) {
                is com.example.callmemorecorder.service.RecordingResult.Success -> {
                    saveRecording(it.filePath, it.durationMs)
                }
                is com.example.callmemorecorder.service.RecordingResult.Failure -> {
                    Log.e(TAG, "Recording failed: ${it.reason}")
                    _uiState.update { s -> s.copy(errorMessage = it.reason) }
                }
            }
        } ?: context.startService(RecordingService.stopIntent(context))
    }

    private fun saveRecording(filePath: String, durationMs: Long) {
        viewModelScope.launch {
            try {
                val fileName = File(filePath).name
                val record = RecordItem(
                    id = UUID.randomUUID().toString(),
                    title = fileName.removeSuffix(".m4a"),
                    createdAt = System.currentTimeMillis(),
                    durationMs = durationMs,
                    localPath = filePath,
                    mimeType = "audio/mp4",
                    status = RecordingStatus.SAVED,
                    uploadStatus = UploadStatus.NOT_STARTED,
                    transcriptionStatus = TranscriptionStatus.NOT_STARTED,
                    driveFileId = null,
                    driveWebLink = null,
                    transcriptText = null,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
                recordRepository.insertRecord(record)
                if (BuildConfig.DRIVE_ENABLED && driveRepository.isSignedIn()) {
                    val req = UploadWorker.buildWorkRequest(record.id, filePath, fileName)
                    workManager.enqueue(req)
                }
                _uiState.update { it.copy(lastSavedRecordId = record.id) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save recording", e)
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    private fun checkDriveStatus() {
        _uiState.update { it.copy(isDriveConnected = driveRepository.isSignedIn()) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        unbindService()
        super.onCleared()
    }
}

data class HomeUiState(
    val recordingState: RecordingState = RecordingState.Idle,
    val elapsedTimeMs: Long = 0L,
    val isDriveConnected: Boolean = false,
    val lastSavedRecordId: String? = null,
    val errorMessage: String? = null
)
