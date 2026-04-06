package com.example.callmemorecorder.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
 * Android 9+ では BroadcastReceiver だけでは
 * バックグラウンド停止後に着信を検知できないため、
 * このサービスが常時通知を出しながら監視する。
 */
class CallMonitorService : Service() {

    companion object {
        private const val TAG = "CallMonitorService"
        const val NOTIFICATION_ID = 3001
        const val CHANNEL_ID = "call_monitor_channel"

        fun startIntent(ctx: Context) = Intent(ctx, CallMonitorService::class.java)
        fun stopIntent(ctx: Context)  = Intent(ctx, CallMonitorService::class.java).apply {
            action = "STOP"
        }
    }

    private lateinit var telephonyManager: TelephonyManager
    private var phoneStateListener: PhoneStateListener? = null
    private var prevState = TelephonyManager.CALL_STATE_IDLE

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
        return START_STICKY  // 強制停止されても再起動
    }

    @Suppress("DEPRECATION")
    private fun startPhoneStateListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                Log.d(TAG, "onCallStateChanged: $prevState -> $state")
                handleStateTransition(prevState, state)
                prevState = state
            }
        }
        @Suppress("DEPRECATION")
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun handleStateTransition(prev: Int, current: Int) {
        val serviceIntent = Intent(this, CallRecordingService::class.java)
        when {
            // 通話中になった (IDLE/RINGING → OFFHOOK)
            current == TelephonyManager.CALL_STATE_OFFHOOK
                && prev != TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.i(TAG, "Call started")
                serviceIntent.action = TelephonyManager.EXTRA_STATE_OFFHOOK
                startCallRecordingService(serviceIntent)
            }
            // 通話終了 (OFFHOOK → IDLE)
            current == TelephonyManager.CALL_STATE_IDLE
                && prev == TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.i(TAG, "Call ended")
                serviceIntent.action = TelephonyManager.EXTRA_STATE_IDLE
                startCallRecordingService(serviceIntent)
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
