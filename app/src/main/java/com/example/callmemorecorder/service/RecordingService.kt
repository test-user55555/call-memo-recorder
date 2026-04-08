package com.example.callmemorecorder.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.callmemorecorder.MainActivity
import com.example.callmemorecorder.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Foreground Service for audio recording.
 *
 * IMPORTANT: Only records user's own voice via MediaRecorder.AudioSource.MIC
 * Call party (remote) audio is NOT captured - this is by design and OS limitation.
 * Android OS restricts CAPTURE_AUDIO_OUTPUT to system/privileged apps only.
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "recording_channel"
        const val CHANNEL_NAME = "Recording"

        const val ACTION_START_RECORDING = "com.example.callmemorecorder.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.callmemorecorder.STOP_RECORDING"

        // 通話録音最適化: 16kHz モノラル 48kbps
        private const val SAMPLE_RATE = 16000
        private const val BIT_RATE = 48000

        fun startIntent(context: Context): Intent {
            return Intent(context, RecordingService::class.java).apply {
                action = ACTION_START_RECORDING
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
        }
    }

    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = RecordingBinder()
    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var recordingStartTime: Long = 0L
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _elapsedTimeMs = MutableStateFlow(0L)
    val elapsedTimeMs: StateFlow<Long> = _elapsedTimeMs.asStateFlow()

    private var elapsedTimeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "RecordingService created")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "RecordingService destroyed")
    }

    fun startRecording() {
        if (_recordingState.value is RecordingState.Recording) {
            Log.w(TAG, "Already recording, ignoring start request")
            return
        }

        try {
            val outputFile = createOutputFile()
            currentOutputFile = outputFile

            @Suppress("DEPRECATION")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }

            recorder.apply {
                // MIC source - records user's own voice only
                // Note: VOICE_COMMUNICATION might be better for call scenarios
                // but MIC is most compatible across devices
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(BIT_RATE)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioChannels(1)  // モノラル録音
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            recordingStartTime = System.currentTimeMillis()
            _recordingState.value = RecordingState.Recording(outputFile.absolutePath)

            startElapsedTimeTracking()
            startForeground(NOTIFICATION_ID, createNotification())

            Log.i(TAG, "Recording started: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _recordingState.value = RecordingState.Error(e.message ?: "Unknown error")
            cleanupMediaRecorder()
        }
    }

    fun stopRecording(): RecordingResult? {
        if (_recordingState.value !is RecordingState.Recording) {
            Log.w(TAG, "Not recording, ignoring stop request")
            stopSelf()
            return null
        }

        elapsedTimeJob?.cancel()

        val durationMs = System.currentTimeMillis() - recordingStartTime
        val filePath = currentOutputFile?.absolutePath

        return try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            mediaRecorder = null

            val result = if (filePath != null && currentOutputFile?.exists() == true) {
                RecordingResult.Success(
                    filePath = filePath,
                    durationMs = durationMs
                )
            } else {
                RecordingResult.Failure("Output file not found")
            }

            _recordingState.value = RecordingState.Idle
            _elapsedTimeMs.value = 0L

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

            Log.i(TAG, "Recording stopped. Duration: ${durationMs}ms, File: $filePath")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            _recordingState.value = RecordingState.Error(e.message ?: "Stop failed")
            cleanupMediaRecorder()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            null
        }
    }

    private fun cleanupMediaRecorder() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up MediaRecorder", e)
        } finally {
            mediaRecorder = null
        }
    }

    private fun createOutputFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
            .format(Date())
        val fileName = "tmp_$timestamp.m4a"  // 一時ファイル名（CallMonitorServiceと統一）
        val recordingDir = File(filesDir, "recordings").also { it.mkdirs() }
        return File(recordingDir, fileName)
    }

    private fun startElapsedTimeTracking() {
        elapsedTimeJob?.cancel()
        elapsedTimeJob = serviceScope.launch {
            while (isActive) {
                _elapsedTimeMs.value = System.currentTimeMillis() - recordingStartTime
                delay(200L)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows current recording status"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}

/**
 * States for recording process
 */
sealed class RecordingState {
    object Idle : RecordingState()
    data class Recording(val outputPath: String) : RecordingState()
    data class Error(val message: String) : RecordingState()
}

/**
 * Results after stopping recording
 */
sealed class RecordingResult {
    data class Success(val filePath: String, val durationMs: Long) : RecordingResult()
    data class Failure(val reason: String) : RecordingResult()
}
