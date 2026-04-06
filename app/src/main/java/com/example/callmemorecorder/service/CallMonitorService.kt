package com.example.callmemorecorder.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.callmemorecorder.MainActivity

/**
 * 常駐フォアグラウンドサービス。
 * TelephonyManager で通話状態を監視し、
 * 通話開始 / 終了時に CallRecordingService に転送する。
 *
 * START_STICKY により OS に強制終了されても再起動。
 * 通知バーに「通話監視中」を常時表示することで
 * Android のバックグラウンド制限を回避する。
 */
class CallMonitorService : Service() {

    companion object {
        private const val TAG = "CallMonitorService"
        const val NOTIFICATION_ID = 3001
        const val CHANNEL_ID = "call_monitor_channel"

        // Intent extra keys
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_CALLER_NAME  = "extra_caller_name"
        const val EXTRA_DIRECTION    = "extra_direction"   // "INCOMING" / "OUTGOING"

        fun startIntent(ctx: Context) = Intent(ctx, CallMonitorService::class.java)
        fun stopIntent(ctx: Context)  = Intent(ctx, CallMonitorService::class.java).apply {
            action = "STOP"
        }
    }

    private lateinit var telephonyManager: TelephonyManager
    private var phoneStateListener: PhoneStateListener? = null
    private var prevState = TelephonyManager.CALL_STATE_IDLE

    // 通話情報（状態遷移をまたいで保持）
    private var currentPhoneNumber: String? = null
    private var currentCallerName: String? = null
    private var callDirection: String = "UNKNOWN"   // "INCOMING" / "OUTGOING"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startPhoneStateListener()
        Log.i(TAG, "CallMonitorService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            Log.i(TAG, "Stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startPhoneStateListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                Log.d(TAG, "onCallStateChanged: $prevState -> $state, number=$phoneNumber")
                // phoneNumber は RINGING 時のみ非 null になる端末が多い
                if (!phoneNumber.isNullOrEmpty()) {
                    currentPhoneNumber = phoneNumber
                    currentCallerName = resolveContactName(phoneNumber)
                }
                handleStateTransition(prevState, state)
                prevState = state
            }
        }
        @Suppress("DEPRECATION")
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun handleStateTransition(prev: Int, current: Int) {
        when {
            // 着信中 (IDLE → RINGING)
            current == TelephonyManager.CALL_STATE_RINGING &&
                    prev == TelephonyManager.CALL_STATE_IDLE -> {
                callDirection = "INCOMING"
                Log.i(TAG, "Incoming call from $currentPhoneNumber")
            }
            // 通話開始 (RINGING or IDLE → OFFHOOK)
            current == TelephonyManager.CALL_STATE_OFFHOOK &&
                    prev != TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (prev == TelephonyManager.CALL_STATE_IDLE) {
                    callDirection = "OUTGOING"   // 直接 OFFHOOK = 発信
                }
                Log.i(TAG, "Call started: dir=$callDirection, num=$currentPhoneNumber")
                val serviceIntent = Intent(this, CallRecordingService::class.java).apply {
                    action = TelephonyManager.EXTRA_STATE_OFFHOOK
                    putExtra(EXTRA_PHONE_NUMBER, currentPhoneNumber)
                    putExtra(EXTRA_CALLER_NAME, currentCallerName)
                    putExtra(EXTRA_DIRECTION, callDirection)
                }
                startCallRecordingService(serviceIntent)
            }
            // 通話終了 (OFFHOOK → IDLE)
            current == TelephonyManager.CALL_STATE_IDLE &&
                    prev == TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.i(TAG, "Call ended: dir=$callDirection, num=$currentPhoneNumber")
                val serviceIntent = Intent(this, CallRecordingService::class.java).apply {
                    action = TelephonyManager.EXTRA_STATE_IDLE
                    putExtra(EXTRA_PHONE_NUMBER, currentPhoneNumber)
                    putExtra(EXTRA_CALLER_NAME, currentCallerName)
                    putExtra(EXTRA_DIRECTION, callDirection)
                }
                startCallRecordingService(serviceIntent)
                // 通話情報リセット
                currentPhoneNumber = null
                currentCallerName = null
                callDirection = "UNKNOWN"
            }
        }
    }

    private fun startCallRecordingService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /** 電話番号から連絡先名を取得 */
    private fun resolveContactName(phoneNumber: String): String? {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(phoneNumber)
            )
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveContactName failed: ${e.message}")
            null
        }
    }

    override fun onDestroy() {
        @Suppress("DEPRECATION")
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        phoneStateListener = null
        Log.i(TAG, "CallMonitorService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "通話監視",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "通話を検知して自動録音するために常駐しています"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Memo Recorder")
            .setContentText("通話を監視中 — 着信を自動録音します")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
