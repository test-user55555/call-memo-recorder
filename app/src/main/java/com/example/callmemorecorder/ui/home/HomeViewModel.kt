package com.example.callmemorecorder.ui.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
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
    private val workManager: WorkManager,
    private val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
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
                    workManager = WorkManager.getInstance(context),
                    dataStore = container.dataStore
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
        // DataStore の設定変化を監視してホーム画面に反映
        viewModelScope.launch {
            dataStore.data.catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
                .collect { prefs ->
                    val uploadType    = prefs[stringPreferencesKey("upload_type")]  ?: "none"
                    val autoUpload    = prefs[booleanPreferencesKey("auto_upload")] ?: false
                    val autoRecord    = prefs[booleanPreferencesKey("auto_record_call")] ?: false
                    val isDriveSignedIn = driveRepository.isSignedIn()
                    _uiState.update {
                        it.copy(
                            uploadType      = uploadType,
                            autoUpload      = autoUpload,
                            autoRecordCall  = autoRecord,
                            isDriveConnected = isDriveSignedIn
                        )
                    }
                }
        }
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

    /** onResume 相当: 設定画面から戻ったときに Drive サインイン状態を最新化 */
    fun refreshStatus() {
        _uiState.update { it.copy(isDriveConnected = driveRepository.isSignedIn()) }
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

                // アップロード先に応じてワーカーをキュー
                val prefs = dataStore.data.first()
                val uploadType = prefs[stringPreferencesKey("upload_type")] ?: "none"
                val autoUpload = prefs[booleanPreferencesKey("auto_upload")] ?: false
                if (autoUpload && uploadType != "none") {
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
    val uploadType: String = "none",      // "drive" / "ftps" / "none"
    val autoUpload: Boolean = false,
    val autoRecordCall: Boolean = false,
    val lastSavedRecordId: String? = null,
    val errorMessage: String? = null
)
