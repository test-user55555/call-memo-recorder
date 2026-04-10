package com.example.callmemorecorder.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import java.util.concurrent.Executors

/**
 * 通話監視 + 自動録音 を一体化したフォアグラウンドサービス。
 *
 * ■ 設計方針
 *   - foregroundServiceType = "microphone" のみ使用
 *   - API 31+ は TelephonyCallback、それ未満は PhoneStateListener(deprecated) を使用
 *   - API 31+ の TelephonyCallback.CallStateListener では phoneNumber が渡されないため
 *     CallStateListener ではなく TelephonyCallback を継承して EXTRA 番号を自分で取得
 *   - RecordingService/CallRecordingService への依存を排除し MediaRecorder を直接管理
 *   - START_STICKY で OS 強制終了後も自動再起動
 *
 * ■ 通話中録音の仕組み
 *   - 通話中は Android の AudioManager が MODE_IN_CALL に切り替わり、
 *     マイクがテレフォニーシステムに専有されるため MediaRecorder からアクセスできない。
 *   - 録音開始前に AudioManager.mode = MODE_IN_COMMUNICATION へ変更することで
 *     マイクをアプリに解放させる（通話自体は継続）。
 *   - 録音終了後は元のモードに戻す。
 *   - ※ 相手側音声（DOWNLINK）は Android の権限設計上、一般アプリでは取得不可。
 */
class CallMonitorService : Service() {

    companion object {
        private const val TAG = "CallMonitorService"
        const val NOTIFICATION_ID = 3001
        const val CHANNEL_ID = "call_monitor_channel"

        // 通話録音最適化: 16kHz モノラル 48kbps（ファイルサイズ削減 + 通話音声に十分な品質）
        private const val SAMPLE_RATE = 16000
        private const val BIT_RATE = 48000

        // 通話開始から録音を開始するまでの遅延（ms）
        // 通話が確立するまでに少し時間が必要（短すぎると録音が始まる前に通話が終わることも）
        private const val RECORD_START_DELAY_MS = 1500L

        // 有効な録音と見なす最小長（ms）
        private const val MIN_RECORDING_DURATION_MS = 2000L

        fun startIntent(ctx: Context) = Intent(ctx, CallMonitorService::class.java)
        fun stopIntent(ctx: Context) = Intent(ctx, CallMonitorService::class.java).apply {
            action = "STOP"
        }
    }

    // ── Telephony ──────────────────────────────────────────────
    private lateinit var telephonyManager: TelephonyManager

    // API 31+ 用
    private var telephonyCallback: TelephonyCallback? = null
    // API 30 以下用
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    private var prevState = TelephonyManager.CALL_STATE_IDLE

    // ── 通話情報 ─────────────────────────────────────────────
    private var currentPhoneNumber: String? = null
    private var currentCallerName: String? = null
    private var callDirection: String = "UNKNOWN"
    private var callStartTime: Long = 0L  // 通話開始時刻（通話ログ検索の基準時刻）

    // ── AudioManager ──────────────────────────────────────────
    private lateinit var audioManager: AudioManager
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL

    // ── 録音 ─────────────────────────────────────────────────
    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var recordingStartTime: Long = 0L

    @Volatile private var isRecording = false

    // ── コルーチン ────────────────────────────────────────────
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val container get() = (application as CallMemoApp).container

    // ── ライフサイクル ────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        // 最優先: onCreate で即座にフォアグラウンド通知を表示（5秒ルール対応）
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildMonitorNotification())
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Log.i(TAG, "CallMonitorService onCreate (API ${Build.VERSION.SDK_INT})")
        // READ_PHONE_STATE 権限チェック後にリスナー登録
        if (hasPhoneStatePermission()) {
            startTelephonyListener()
        } else {
            Log.w(TAG, "READ_PHONE_STATE permission not granted – stopping service")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            Log.i(TAG, "Stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        // 二重起動時も通知を再表示（ForegroundServiceDidNotStartInTimeException 対策）
        startForeground(NOTIFICATION_ID, buildMonitorNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        stopTelephonyListener()
        if (isRecording) runBlocking { stopRecording() }
        serviceScope.cancel()
        Log.i(TAG, "CallMonitorService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 権限チェック ──────────────────────────────────────────

    private fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasReadContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasReadCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED

    // ── 通話状態リスナー設定 ──────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startTelephonyListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+ : TelephonyCallback
            // Note: API31以降、TelephonyCallback.CallStateListenerでは
            // phoneNumberが渡されない（プライバシー保護のため）
            // 着信番号は RINGING時にTelephonyManager.getLine1Number()か
            // PhoneStateListenerの互換経由で取得を試みる
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    Log.d(TAG, "TelephonyCallback.onCallStateChanged: $prevState -> $state")
                    // API 31+ では phoneNumber は渡されないため null を渡す
                    handleStateTransition(prevState, state, null)
                    prevState = state
                }
            }
            telephonyCallback = cb
            telephonyManager.registerTelephonyCallback(
                Executors.newSingleThreadExecutor(), cb
            )
            // 現在の状態を初期値として取得
            try {
                prevState = telephonyManager.callState
            } catch (e: Exception) {
                Log.w(TAG, "Could not get initial call state: ${e.message}")
            }
        } else {
            // API 29-30 : PhoneStateListener (deprecated だが利用可能)
            // このバージョンでは phoneNumber が着信時に渡される
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    Log.d(TAG, "PhoneStateListener.onCallStateChanged: $prevState -> $state, num=$phoneNumber")
                    handleStateTransition(prevState, state, phoneNumber)
                    prevState = state
                }
            }
            phoneStateListener = listener
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
        Log.i(TAG, "Telephony listener registered")
    }

    private fun stopTelephonyListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                try { telephonyManager.unregisterTelephonyCallback(it) } catch (e: Exception) {}
            }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let {
                try {
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                } catch (e: Exception) {}
            }
            phoneStateListener = null
        }
    }

    // ── 通話状態遷移ハンドラ ──────────────────────────────────

    private fun handleStateTransition(prev: Int, current: Int, phoneNumber: String?) {
        // 番号が渡された場合（主にAPI 30以下の PhoneStateListener からの着信番号）
        if (!phoneNumber.isNullOrEmpty()) {
            currentPhoneNumber = phoneNumber
            currentCallerName = resolveContactName(phoneNumber)
            Log.d(TAG, "Phone number from listener: $phoneNumber, name: $currentCallerName")
        }

        when {
            // 着信検知 (IDLE → RINGING)
            current == TelephonyManager.CALL_STATE_RINGING &&
                    prev == TelephonyManager.CALL_STATE_IDLE -> {
                callDirection = "INCOMING"
                // API 31+ では phoneNumber が渡されないため TelephonyManager から直接取得を試みる
                if (phoneNumber.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    tryGetIncomingNumberFromTelephony()
                }
                Log.i(TAG, "Incoming call from ${currentPhoneNumber ?: "unknown"}")
            }

            // 通話開始 (RINGING or IDLE → OFFHOOK)
            current == TelephonyManager.CALL_STATE_OFFHOOK &&
                    prev != TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (prev == TelephonyManager.CALL_STATE_IDLE) {
                    callDirection = "OUTGOING"
                    // 発信の場合、API 31+ では番号が取得できないことが多い
                    Log.d(TAG, "Outgoing call - number may be unknown on API 31+")
                }
                callStartTime = System.currentTimeMillis()  // 通話開始時刻を記録
                Log.i(TAG, "Call started: dir=$callDirection, num=${currentPhoneNumber ?: "unknown"}")
                onCallStarted()
            }

            // 通話終了 (OFFHOOK → IDLE)
            current == TelephonyManager.CALL_STATE_IDLE &&
                    prev == TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.i(TAG, "Call ended: dir=$callDirection, num=${currentPhoneNumber ?: "unknown"}")
                onCallEnded()
                currentPhoneNumber = null
                currentCallerName = null
                callDirection = "UNKNOWN"
                callStartTime = 0L
            }
        }
    }

    // ── 通話開始 ─────────────────────────────────────────────

    private fun onCallStarted() {
        if (isRecording) return
        updateNotification("録音中")
        serviceScope.launch {
            delay(RECORD_START_DELAY_MS)
            startRecordingWithSource()  // DataStoreから設定ソースを読み起動
        }
    }

    // ── 通話終了 ─────────────────────────────────────────────

    private fun onCallEnded() {
        // 通話終了時点の番号・名前・方向・開始時刻をスナップショットとして保存
        val snapshotNumber    = currentPhoneNumber
        val snapshotName      = currentCallerName
        val snapshotDirection = callDirection
        val snapshotStartTime = callStartTime

        serviceScope.launch {
            val result = stopRecording()
            updateNotification("通話を監視中")
            if (result is RecordingResult.Success) {
                // API 31+ で番号が取得できなかった場合は通話ログから補完（遅延後）
                val finalNumber = if (snapshotNumber.isNullOrEmpty()) {
                    delay(5000L)   // 通話ログに記録されるまで待つ
                    resolveNumberFromCallLog(snapshotDirection, snapshotStartTime).also { resolved ->
                        if (resolved != null) Log.i(TAG, "Phone number resolved from call log: $resolved")
                        else Log.w(TAG, "Phone number could not be resolved from call log")
                    }
                } else {
                    snapshotNumber
                }

                // 番号が取れたら連絡先名も補完
                val finalName = snapshotName
                    ?: finalNumber?.let { resolveContactName(it) }

                saveAndUpload(
                    filePath  = result.filePath,
                    durationMs = result.durationMs,
                    number    = finalNumber,
                    name      = finalName,
                    direction = snapshotDirection
                )
            }
        }
    }

    // ── 録音制御 ─────────────────────────────────────────────

    private suspend fun startRecordingWithSource() {
        if (isRecording) return
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "RECORD_AUDIO permission not granted – cannot record")
            return
        }
        val prefs = container.dataStore.data.first()
        val sourceKey = prefs[stringPreferencesKey("audio_source")] ?: "VOICE_COMMUNICATION"
        val preferredSource = audioSourceFromKey(sourceKey)

        // ── AudioMode を MODE_IN_COMMUNICATION に切り替え ──────────────────
        // 通話中は AudioManager が MODE_IN_CALL になっており、マイクがテレフォニーに専有される。
        // MODE_IN_COMMUNICATION に変更することでアプリがマイクにアクセス可能になる。
        savedAudioMode = audioManager.mode
        Log.i(TAG, "AudioManager: savedMode=$savedAudioMode, switching to MODE_IN_COMMUNICATION")
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set AudioManager mode: ${e.message}")
        }

        // 選択ソースを最優先に試行、失敗時は MIC でフォールバック
        val sources = if (preferredSource == MediaRecorder.AudioSource.MIC) {
            listOf(MediaRecorder.AudioSource.MIC)
        } else {
            listOf(preferredSource, MediaRecorder.AudioSource.MIC)
        }

        for (source in sources) {
            Log.i(TAG, "startRecording: trying source=$source (key=$sourceKey)")
            val result = tryStartRecording(source)
            if (result != null) {
                Log.i(TAG, "Recording started successfully with source=$source")
                return
            }
        }

        Log.e(TAG, "All audio sources failed – recording not started")
        isRecording = false
        currentOutputFile = null
        // 失敗時はAudioModeを元に戻す
        restoreAudioMode()
        updateNotification("通話を監視中")
    }

    private fun audioSourceFromKey(key: String): Int = when (key) {
        "MIC"                 -> MediaRecorder.AudioSource.MIC
        "CAMCORDER"           -> MediaRecorder.AudioSource.CAMCORDER
        "VOICE_RECOGNITION"   -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        "VOICE_COMMUNICATION" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        "UNPROCESSED"         -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                                     MediaRecorder.AudioSource.UNPROCESSED
                                 else MediaRecorder.AudioSource.MIC
        else                  -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }

    @Synchronized
    private fun startRecording() {
        if (isRecording) return
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "RECORD_AUDIO permission not granted – cannot record")
            return
        }

        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )

        for (source in sources) {
            Log.i(TAG, "startRecording: trying source=$source")
            val result = tryStartRecording(source)
            if (result != null) {
                Log.i(TAG, "Recording started successfully with source=$source")
                return
            }
        }

        Log.e(TAG, "All audio sources failed – recording not started")
        isRecording = false
        currentOutputFile = null
        updateNotification("通話を監視中")
    }

    /**
     * 指定した AudioSource で録音を試みる。
     * 成功したらその File を返す、失敗したら null を返す。
     * 失敗時は MediaRecorder を完全にリリースして null を返す。
     */
    private fun tryStartRecording(audioSource: Int): File? {
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
                setAudioEncodingBitRate(BIT_RATE)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioChannels(1)  // モノラル録音
                setOutputFile(outputFile.absolutePath)
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder error: what=$what, extra=$extra")
                }
                prepare()
                start()
            }
            mediaRecorder = recorder
            currentOutputFile = outputFile
            recordingStartTime = System.currentTimeMillis()
            isRecording = true
            Log.i(TAG, "Recording started (source=$audioSource): ${outputFile.absolutePath}")
            outputFile
        } catch (e: Exception) {
            Log.w(TAG, "tryStartRecording failed (source=$audioSource): ${e.javaClass.simpleName}: ${e.message}")
            try { recorder?.reset() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
            null
        }
    }

    /** AudioMode を録音前の状態に戻す */
    private fun restoreAudioMode() {
        try {
            Log.i(TAG, "AudioManager: restoring mode to $savedAudioMode")
            audioManager.mode = savedAudioMode
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore AudioManager mode: ${e.message}")
        }
    }

    /** @return RecordingResult? – null なら録音していなかった or 短すぎた */
    @Synchronized
    private fun stopRecording(): RecordingResult? {
        if (!isRecording) return null
        isRecording = false

        val durationMs = System.currentTimeMillis() - recordingStartTime
        val filePath = currentOutputFile?.absolutePath
        val capturedRecorder = mediaRecorder
        mediaRecorder = null
        currentOutputFile = null

        Log.i(TAG, "stopRecording: durationMs=$durationMs, file=$filePath")

        return try {
            capturedRecorder?.apply {
                try {
                    stop()
                } catch (stopEx: RuntimeException) {
                    // stop() が失敗する場合（録音が極めて短い場合など）
                    Log.w(TAG, "MediaRecorder.stop() threw RuntimeException: ${stopEx.message}")
                    reset()
                    release()
                    filePath?.let { File(it).delete() }
                    return null
                }
                reset()
                release()
            }

            when {
                filePath == null -> {
                    Log.w(TAG, "stopRecording: filePath is null")
                    null
                }
                !File(filePath).exists() -> {
                    Log.w(TAG, "stopRecording: file does not exist: $filePath")
                    null
                }
                File(filePath).length() == 0L -> {
                    Log.w(TAG, "stopRecording: file is empty: $filePath")
                    File(filePath).delete()
                    null
                }
                durationMs < MIN_RECORDING_DURATION_MS -> {
                    Log.w(TAG, "stopRecording: too short (${durationMs}ms < ${MIN_RECORDING_DURATION_MS}ms)")
                    File(filePath).delete()
                    null
                }
                else -> {
                    Log.i(TAG, "Recording saved: $filePath (${durationMs}ms, ${File(filePath).length()} bytes)")
                    RecordingResult.Success(filePath, durationMs)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording error", e)
            try { capturedRecorder?.release() } catch (_: Exception) {}
            filePath?.let { try { File(it).delete() } catch (_: Exception) {} }
            null
        } finally {
            // 録音終了後は必ず AudioMode を元に戻す
            restoreAudioMode()
        }
    }

    // ── DB 保存 & アップロード ────────────────────────────────

    private suspend fun saveAndUpload(
        filePath: String,
        durationMs: Long,
        number: String?,
        name: String?,
        direction: String
    ) {
        try {
            // ── ファイルを正式名にリネーム ──────────────────────────────────
            val now = System.currentTimeMillis()
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date(now))
            val newFileName = buildFileName(timestamp, direction, name, number, durationMs)
            val originalFile = File(filePath)
            val renamedFile = File(originalFile.parent ?: filesDir.absolutePath, newFileName)
            val finalPath = if (originalFile.renameTo(renamedFile)) {
                Log.i(TAG, "Renamed: ${originalFile.name} -> $newFileName")
                renamedFile.absolutePath
            } else {
                Log.w(TAG, "Rename failed, keeping original: $filePath")
                filePath
            }

            // タイトルはファイル名から拡張子を除いたもの
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
                updatedAt = System.currentTimeMillis(),
                callerNumber = number,
                callerName = name,
                callDirection = runCatching { CallDirection.valueOf(direction) }
                    .getOrDefault(CallDirection.UNKNOWN)
            )
            container.recordRepository.insertRecord(record)
            Log.i(TAG, "Record saved: ${record.id}, title=$title")

            val prefs = container.dataStore.data.first()
            val uploadType = prefs[stringPreferencesKey("upload_type")] ?: "none"
            val autoUpload = prefs[booleanPreferencesKey("auto_upload")] ?: false
            if (autoUpload && uploadType != "none") {
                WorkManager.getInstance(applicationContext)
                    .enqueue(UploadWorker.buildWorkRequest(record.id, finalPath, File(finalPath).name))
                Log.i(TAG, "Upload queued for ${record.id}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveAndUpload error", e)
        }
    }

    // ── ファイル ──────────────────────────────────────────────

    /**
     * 一時録音ファイルを作成する（仮名: tmp_yyyymmdd-hhmmss.m4a）。
     * 通話終了後に saveAndUpload() 内で正式ファイル名にリネームする。
     */
    private fun createOutputFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val dir = File(filesDir, "recordings").also { it.mkdirs() }
        return File(dir, "tmp_$timestamp.m4a")
    }

    /**
     * 正式ファイル名を生成する。
     * 形式: yyyyMMdd-HHmmss_発着_名前_番号_T[分].m4a
     *   - 着信: 「着」、発信: 「発」、不明/手動: 「通話」
     *   - 名前が取得できない場合: 「-」
     *   - 番号が取得できない場合: 「-」（省略しない）
     *   - 録音時間: 分単位で切り上げ（例: 1分20秒→T2, 1時間半→T90）
     *   - ファイル名に使えない文字（/ \ : * ? " < > | 空白等）はアンダースコアに置換
     *
     * 例:
     *   20260410-082858_着_山田太郎_09012345678_T2.m4a  （名前あり・番号あり）
     *   20260410-082858_着_-_09012345678_T2.m4a          （名前なし・番号あり）
     *   20260410-082858_着_-_-_T2.m4a                    （名前なし・番号なし）
     */
    private fun buildFileName(timestamp: String, direction: String, name: String?, number: String?, durationMs: Long): String {
        val dirLabel = when (direction) {
            "INCOMING" -> "着"
            "OUTGOING" -> "発"
            else       -> "通話"
        }
        val safeName   = (name?.takeIf { it.isNotBlank() } ?: "-")
            .replace(Regex("[/\\\\:*?\"<>|\\s]"), "_")
        // 番号が取れない場合も "-" で明示的にフィールドを埋める
        val safeNumber = (number?.takeIf { it.isNotBlank() && it != "-1" } ?: "-")
            .replace(Regex("[/\\\\:*?\"<>|\\s]"), "_")
        
        // 録音時間を分単位で切り上げ（例: 80秒→2分, 30秒→1分, 0秒→0分）
        val durationMinutes = ((durationMs / 1000.0) / 60.0).let { kotlin.math.ceil(it).toInt() }
        val timeLabel = "T${durationMinutes}"
        
        return "${timestamp}_${dirLabel}_${safeName}_${safeNumber}_${timeLabel}.m4a"
    }

    // ── API 31+ 着信番号の直接取得試行 ──────────────────────────

    /**
     * API 31+ で RINGING 状態時に TelephonyManager から着信番号の取得を試みる。
     * Android 12+ では READ_CALL_LOG 権限があれば getLine1Number 以外の方法も使用可能。
     * ※ セキュリティ上、一般アプリへの着信番号提供は制限されているため、
     *    取得できない場合は通話ログフォールバックで補完する。
     */
    @SuppressLint("MissingPermission")
    private fun tryGetIncomingNumberFromTelephony() {
        try {
            // Android 5.1+ : EXTRA_INCOMING_NUMBER は API 29 で deprecated になったが
            // BroadcastReceiver 経由では取得可能な場合がある（CallStateReceiver で対応）
            // ここでは READ_CALL_LOG 権限がある場合に限り通話ログから即時取得を試みる
            if (hasReadCallLogPermission()) {
                // 直前の着信ログを取得（まだ通話中なので DATE は今に近い）
                contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE),
                    "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?",
                    arrayOf(CallLog.Calls.INCOMING_TYPE.toString(),
                        (System.currentTimeMillis() - 30_000L).toString()),
                    "${CallLog.Calls.DATE} DESC"
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val num = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                        if (!num.isNullOrBlank() && num != "-1") {
                            currentPhoneNumber = num
                            currentCallerName = resolveContactName(num)
                            Log.i(TAG, "Incoming number from call log (RINGING): $num")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryGetIncomingNumberFromTelephony failed: ${e.message}")
        }
    }

    // ── 通話ログから番号を取得（API 31+ で TelephonyCallback に番号が渡されない場合の補完） ─────

    /**
     * 通話終了直後に通話ログから該当通話の番号を取得する。
     * READ_CALL_LOG 権限が必要。
     * @param direction "INCOMING" or "OUTGOING"
     * @param callStartTime 通話開始時刻（ms）。0の場合は直近120秒以内を検索
     */
    private fun resolveNumberFromCallLog(direction: String, callStartTime: Long = 0L): String? {
        if (!hasReadCallLogPermission()) {
            Log.d(TAG, "READ_CALL_LOG permission not granted")
            return null
        }
        return try {
            val callType = when (direction) {
                "INCOMING" -> CallLog.Calls.INCOMING_TYPE
                "OUTGOING" -> CallLog.Calls.OUTGOING_TYPE
                else       -> null
            }
            // 通話開始時刻の30秒前から現在までを検索（ログ記録タイムスタンプのズレを考慮）
            val cutoff = if (callStartTime > 0L) {
                callStartTime - 30_000L
            } else {
                System.currentTimeMillis() - 120_000L
            }
            val selection = buildString {
                append("${CallLog.Calls.DATE} >= ?")
                if (callType != null) append(" AND ${CallLog.Calls.TYPE} = ?")
            }
            val selectionArgs = if (callType != null) {
                arrayOf(cutoff.toString(), callType.toString())
            } else {
                arrayOf(cutoff.toString())
            }

            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE),
                selection,
                selectionArgs,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                    Log.d(TAG, "Resolved from call log: $number (dir=$direction)")
                    number.takeIf { it.isNotBlank() && it != "-1" }
                } else {
                    Log.d(TAG, "No call log entry found (dir=$direction, cutoff=$cutoff)")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveNumberFromCallLog failed: ${e.message}")
            null
        }
    }

    // ── 連絡先解決 ─────────────────────────────────────────────

    private fun resolveContactName(phoneNumber: String): String? {
        if (!hasReadContactsPermission()) {
            Log.d(TAG, "READ_CONTACTS permission not granted")
            return null
        }
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
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    )
                    Log.d(TAG, "Resolved contact: $phoneNumber -> $name")
                    name
                } else {
                    Log.d(TAG, "No contact found for $phoneNumber")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveContactName failed: ${e.message}")
            null
        }
    }

    // ── 通知 ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID, "通話監視・自動録音",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "通話を検知して自動録音します"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }.also {
                getSystemService(NotificationManager::class.java).createNotificationChannel(it)
            }
        }
    }

    private fun buildMonitorNotification(text: String = "通話を監視中 — 着信/発信を自動録音します"): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Memo Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildMonitorNotification(text))
    }
}
