package com.example.callmemorecorder.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.example.callmemorecorder.CallMemoApp
import com.example.callmemorecorder.domain.model.*
import com.example.callmemorecorder.worker.UploadWorker
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * ユーザー補助サービスを利用した通話録音サービス。
 *
 * ■ 設計方針
 *   - AccessibilityService を継承し、通話状態の変化を検知する
 *   - TelephonyManager で現在の通話状態を定期ポーリング
 *   - 録音前に AudioManager.mode = MODE_IN_COMMUNICATION へ切替
 *     → 通話中でもマイク入力をアプリが取得できるようになる
 *   - CAPTURE_AUDIO_OUTPUT は特権権限のため一般アプリでは実行時には使えないが
 *     宣言することで一部端末/ROM で有効になる場合がある
 *   - 録音終了後に AudioMode を元に戻す
 *
 * ■ 使い方
 *   1. 設定 → ユーザー補助 → Call Memo Recorder → ON にする
 *   2. アプリの「通話自動録音」スイッチを ON にする
 */
class CallRecordingAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CallRecA11y"
        private const val POLL_INTERVAL_MS = 500L
        private const val RECORD_START_DELAY_MS = 1500L
        private const val MIN_RECORDING_DURATION_MS = 2000L
        private const val SAMPLE_RATE = 44100
        private const val BIT_RATE = 128000

        /** 外部から現在サービスが動作中かを確認するフラグ */
        @Volatile var isServiceRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val container get() = (application as CallMemoApp).container

    private lateinit var audioManager: AudioManager
    private lateinit var telephonyManager: TelephonyManager

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var recordingStartTime = 0L
    private var savedAudioMode = AudioManager.MODE_NORMAL

    @Volatile private var isRecording = false
    @Volatile private var lastCallState = TelephonyManager.CALL_STATE_IDLE
    @Volatile private var lastCallDirection = "UNKNOWN"

    // ── ライフサイクル ────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        // アクセシビリティサービス設定（ウィンドウ状態変化を監視）
        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.notificationTimeout = 100
        }

        // 通話状態の定期ポーリング開始
        startCallStatePolling()

        Log.i(TAG, "AccessibilityService connected, call state polling started")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ウィンドウ状態変化でも通話状態を即座にチェック（補完的）
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            checkCallState()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        isServiceRunning = false
        serviceScope.cancel()
        if (isRecording) {
            runBlocking { stopRecording() }
        }
        Log.i(TAG, "AccessibilityService destroyed")
        super.onDestroy()
    }

    // ── 通話状態ポーリング ────────────────────────────────────

    private fun startCallStatePolling() {
        serviceScope.launch {
            while (isActive) {
                checkCallState()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    private fun checkCallState() {
        // DataStore の自動録音フラグを確認（非同期で取得済みキャッシュを使う）
        val currentState = try {
            telephonyManager.callState
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get call state: ${e.message}")
            return
        }

        if (currentState == lastCallState) return

        Log.d(TAG, "Call state changed: $lastCallState -> $currentState")
        val prev = lastCallState
        lastCallState = currentState

        when {
            // 通話開始: IDLE/RINGING → OFFHOOK
            currentState == TelephonyManager.CALL_STATE_OFFHOOK &&
                    prev != TelephonyManager.CALL_STATE_OFFHOOK -> {
                // 着信か発信かを判断
                lastCallDirection = if (prev == TelephonyManager.CALL_STATE_RINGING) "INCOMING" else "OUTGOING"
                Log.i(TAG, "Call started (OFFHOOK, dir=$lastCallDirection)")
                serviceScope.launch {
                    if (isAutoRecordEnabled()) {
                        delay(RECORD_START_DELAY_MS)
                        startRecording()
                    }
                }
            }
            // 通話終了: OFFHOOK → IDLE
            currentState == TelephonyManager.CALL_STATE_IDLE &&
                    prev == TelephonyManager.CALL_STATE_OFFHOOK -> {
                val direction = lastCallDirection
                Log.i(TAG, "Call ended (IDLE, dir=$direction)")
                serviceScope.launch {
                    val result = stopRecording()
                    if (result is RecordingResult.Success) {
                        // 通話ログから番号・名前を取得（少し遅延して通話ログへの記録を待つ）
                        delay(2000L)
                        val number = resolveNumberFromCallLog(direction)
                        val name   = number?.let { resolveContactName(it) }
                        saveAndUpload(result.filePath, result.durationMs, direction, number, name)
                    }
                }
            }
            // 着信RINGING検知（発着信判断用）
            currentState == TelephonyManager.CALL_STATE_RINGING &&
                    prev == TelephonyManager.CALL_STATE_IDLE -> {
                lastCallDirection = "INCOMING"
            }
        }
    }

    private suspend fun isAutoRecordEnabled(): Boolean {
        return try {
            val prefs = container.dataStore.data.first()
            prefs[booleanPreferencesKey("auto_record_call")] ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read auto_record_call pref: ${e.message}")
            false
        }
    }

    // ── 録音制御 ─────────────────────────────────────────────

    private suspend fun startRecording() {
        if (isRecording) return
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        val prefs = container.dataStore.data.first()
        val sourceKey = prefs[stringPreferencesKey("audio_source")] ?: "VOICE_COMMUNICATION"
        val preferredSource = audioSourceFromKey(sourceKey)

        // AudioMode を MODE_IN_COMMUNICATION に切替
        // → 通話中でもアプリがマイクにアクセスできるようになる
        savedAudioMode = audioManager.mode
        Log.i(TAG, "AudioManager: savedMode=$savedAudioMode → MODE_IN_COMMUNICATION")
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set audio mode: ${e.message}")
        }

        val sourcesToTry = if (preferredSource == MediaRecorder.AudioSource.MIC) {
            listOf(MediaRecorder.AudioSource.MIC)
        } else {
            listOf(preferredSource, MediaRecorder.AudioSource.MIC)
        }

        for (source in sourcesToTry) {
            Log.i(TAG, "Trying AudioSource=$source")
            if (tryStartRecording(source)) {
                Log.i(TAG, "Recording started with source=$source")
                return
            }
        }

        Log.e(TAG, "All audio sources failed")
        isRecording = false
        currentOutputFile = null
        restoreAudioMode()
    }

    private fun audioSourceFromKey(key: String): Int = when (key) {
        "MIC"                 -> MediaRecorder.AudioSource.MIC
        "CAMCORDER"           -> MediaRecorder.AudioSource.CAMCORDER
        "VOICE_RECOGNITION"   -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        "VOICE_COMMUNICATION" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        "UNPROCESSED"         -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                                     MediaRecorder.AudioSource.UNPROCESSED
                                 else MediaRecorder.AudioSource.MIC
        else -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }

    private fun tryStartRecording(audioSource: Int): Boolean {
        var recorder: MediaRecorder? = null
        return try {
            val outputFile = createOutputFile()
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(audioSource)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(outputFile.absolutePath)
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder error: what=$what extra=$extra")
                }
                prepare()
                start()
            }
            mediaRecorder = recorder
            currentOutputFile = outputFile
            recordingStartTime = System.currentTimeMillis()
            isRecording = true
            Log.i(TAG, "Recording started: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "tryStartRecording failed (source=$audioSource): ${e.message}")
            try { recorder?.reset() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            false
        }
    }

    private suspend fun stopRecording(): RecordingResult? {
        if (!isRecording) return null
        isRecording = false

        val durationMs = System.currentTimeMillis() - recordingStartTime
        val filePath = currentOutputFile?.absolutePath
        val capturedRecorder = mediaRecorder
        mediaRecorder = null
        currentOutputFile = null

        return try {
            capturedRecorder?.apply {
                try { stop() } catch (e: RuntimeException) {
                    Log.w(TAG, "stop() threw: ${e.message}")
                    reset(); release()
                    filePath?.let { File(it).delete() }
                    return null
                }
                reset()
                release()
            }

            when {
                filePath == null                   -> { Log.w(TAG, "filePath is null"); null }
                !File(filePath).exists()           -> { Log.w(TAG, "file not found"); null }
                File(filePath).length() == 0L      -> { File(filePath).delete(); null }
                durationMs < MIN_RECORDING_DURATION_MS -> {
                    Log.w(TAG, "too short: ${durationMs}ms")
                    File(filePath).delete(); null
                }
                else -> {
                    Log.i(TAG, "Recording saved: $filePath (${durationMs}ms)")
                    RecordingResult.Success(filePath, durationMs)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording error", e)
            try { capturedRecorder?.release() } catch (_: Exception) {}
            filePath?.let { try { File(it).delete() } catch (_: Exception) {} }
            null
        } finally {
            restoreAudioMode()
        }
    }

    private fun restoreAudioMode() {
        try {
            Log.i(TAG, "AudioManager: restoring mode to $savedAudioMode")
            audioManager.mode = savedAudioMode
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore audio mode: ${e.message}")
        }
    }

    // ── DB保存 & アップロード ─────────────────────────────────

    private suspend fun saveAndUpload(
        filePath: String,
        durationMs: Long,
        direction: String,
        number: String?,
        name: String?
    ) {
        try {
            // ファイルを正式名にリネーム
            val now = System.currentTimeMillis()
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date(now))
            val dirLabel = when (direction) {
                "INCOMING" -> "着"
                "OUTGOING" -> "発"
                else       -> "通話"
            }
            val safeName   = (name ?: "不明").replace(Regex("[/\\\\:*?\"<>|\\s]"), "_")
            val safeNumber = number?.replace(Regex("[/\\\\:*?\"<>|\\s]"), "_")
            val newFileName = if (safeNumber != null) {
                "${timestamp}_${dirLabel}_${safeName}_${safeNumber}.m4a"
            } else {
                "${timestamp}_${dirLabel}_${safeName}.m4a"
            }

            val originalFile = File(filePath)
            val renamedFile  = File(originalFile.parent ?: filesDir.absolutePath, newFileName)
            val finalPath    = if (originalFile.renameTo(renamedFile)) {
                Log.i(TAG, "Renamed: ${originalFile.name} -> $newFileName")
                renamedFile.absolutePath
            } else {
                Log.w(TAG, "Rename failed, keeping: $filePath")
                filePath
            }

            val title = File(finalPath).nameWithoutExtension
            val record = RecordItem(
                id = UUID.randomUUID().toString(),
                title = title,
                createdAt = now,
                durationMs = durationMs,
                localPath = finalPath,
                mimeType = "audio/mp4",
                status = RecordingStatus.SAVED,
                uploadStatus = UploadStatus.NOT_STARTED,
                transcriptionStatus = TranscriptionStatus.NOT_STARTED,
                driveFileId = null, driveWebLink = null,
                transcriptText = null, errorMessage = null,
                updatedAt = now,
                callerNumber = number,
                callerName = name,
                callDirection = runCatching { CallDirection.valueOf(direction) }
                    .getOrDefault(CallDirection.UNKNOWN)
            )
            container.recordRepository.insertRecord(record)
            Log.i(TAG, "Record saved: ${record.id}, title=$title, number=$number")

            val prefs = container.dataStore.data.first()
            val uploadType = prefs[stringPreferencesKey("upload_type")] ?: "none"
            val autoUpload = prefs[booleanPreferencesKey("auto_upload")] ?: false
            if (autoUpload && uploadType != "none") {
                WorkManager.getInstance(applicationContext)
                    .enqueue(UploadWorker.buildWorkRequest(record.id, finalPath, File(finalPath).name))
                Log.i(TAG, "Upload queued: ${record.id}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveAndUpload error", e)
        }
    }

    // ── 通話ログから番号を取得 ────────────────────────────────

    private fun resolveNumberFromCallLog(direction: String, withinMs: Long = 60_000L): String? {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "READ_CALL_LOG not granted")
            return null
        }
        return try {
            val callType = when (direction) {
                "INCOMING" -> CallLog.Calls.INCOMING_TYPE
                "OUTGOING" -> CallLog.Calls.OUTGOING_TYPE
                else       -> null
            }
            val cutoff = System.currentTimeMillis() - withinMs
            val selection = buildString {
                append("${CallLog.Calls.DATE} >= ?")
                if (callType != null) append(" AND ${CallLog.Calls.TYPE} = ?")
            }
            val selectionArgs = if (callType != null) arrayOf(cutoff.toString(), callType.toString())
            else arrayOf(cutoff.toString())

            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                selection, selectionArgs,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                    Log.d(TAG, "Resolved from call log: $number (dir=$direction)")
                    number.takeIf { it.isNotBlank() && it != "-1" }
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveNumberFromCallLog failed: ${e.message}")
            null
        }
    }

    // ── 連絡先名の解決 ────────────────────────────────────────

    private fun resolveContactName(phoneNumber: String): String? {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(phoneNumber)
            )
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveContactName failed: ${e.message}")
            null
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun createOutputFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = File(filesDir, "recordings").also { it.mkdirs() }
        return File(dir, "call_a11y_$ts.m4a")
    }
}
