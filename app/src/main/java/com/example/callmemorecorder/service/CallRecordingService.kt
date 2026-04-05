package com.example.callmemorecorder.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.WorkManager
import com.example.callmemorecorder.CallMemoApp
import com.example.callmemorecorder.MainActivity
import com.example.callmemorecorder.domain.model.*
import com.example.callmemorecorder.worker.UploadWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 通話連動の録音管理サービス。
 * CallStateReceiver から電話状態を受け取り、自動で録音開始・停止・アップロードを行う。
 */
class CallRecordingService : Service() {

    companion object {
        private const val TAG = "CallRecordingService"
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "call_recording_channel"

        @Volatile
        var isRecording = false
    }

    private val container get() = (application as CallMemoApp).container
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var recordingService: RecordingService? = null
    private var isBound = false

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
            recordingService = (binder as RecordingService.RecordingBinder).getService()
            isBound = true
            Log.d(TAG, "RecordingService connected")
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            recordingService = null
            isBound = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val bindIntent = Intent(this, RecordingService::class.java)
        bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = intent?.action ?: return START_NOT_STICKY
        Log.d(TAG, "Phone state: $state")
        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> handleCallStarted()
            TelephonyManager.EXTRA_STATE_IDLE     -> handleCallEnded()
            TelephonyManager.EXTRA_STATE_RINGING  -> Log.d(TAG, "Ringing - waiting")
        }
        return START_NOT_STICKY
    }

    private fun handleCallStarted() {
        if (isRecording) return
        Log.i(TAG, "Call started - starting auto recording")
        startForeground(NOTIFICATION_ID, buildNotification("通話録音中..."))

        serviceScope.launch {
            delay(800L) // 通話確立を待つ
            withContext(Dispatchers.Main) {
                if (!isBound) {
                    bindService(Intent(this@CallRecordingService, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
                    delay(500L)
                }
                recordingService?.startRecording()
                isRecording = true
            }
        }
    }

    private fun handleCallEnded() {
        if (!isRecording) { stopSelf(); return }
        Log.i(TAG, "Call ended - stopping recording")

        serviceScope.launch {
            withContext(Dispatchers.Main) {
                val result = recordingService?.stopRecording()
                isRecording = false
                when (result) {
                    is RecordingResult.Success -> saveAndUpload(result.filePath, result.durationMs)
                    is RecordingResult.Failure -> Log.e(TAG, "Recording failed: ${result.reason}")
                    null -> Log.w(TAG, "No recording result")
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun saveAndUpload(filePath: String, durationMs: Long) {
        try {
            val fileName = File(filePath).name
            val title = "通話録音_${SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())}"
            val record = RecordItem(
                id = UUID.randomUUID().toString(),
                title = title,
                createdAt = System.currentTimeMillis(),
                durationMs = durationMs,
                localPath = filePath,
                mimeType = "audio/mp4",
                status = RecordingStatus.SAVED,
                uploadStatus = UploadStatus.NOT_STARTED,
                transcriptionStatus = TranscriptionStatus.NOT_STARTED,
                driveFileId = null, driveWebLink = null,
                transcriptText = null, errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
            withContext(Dispatchers.IO) { container.recordRepository.insertRecord(record) }

            // 自動アップロード判定
            val prefs = container.dataStore.data.first()
            val uploadType = prefs[stringPreferencesKey("upload_type")] ?: "none"
            val autoUpload = prefs[booleanPreferencesKey("auto_upload")] ?: false

            if (autoUpload && uploadType != "none") {
                Log.i(TAG, "Auto upload queued (type=$uploadType)")
                val req = UploadWorker.buildWorkRequest(record.id, filePath, fileName)
                WorkManager.getInstance(applicationContext).enqueue(req)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveAndUpload error", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "通話録音", NotificationManager.IMPORTANCE_LOW).apply {
                description = "通話中の自動録音"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Memo Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true).setSilent(true).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (isBound) { unbindService(connection); isBound = false }
        serviceScope.cancel()
        super.onDestroy()
    }
}
