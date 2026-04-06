package com.example.callmemorecorder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.example.callmemorecorder.service.CallMonitorService
import com.example.callmemorecorder.service.CallRecordingService

/**
 * システムの PHONE_STATE_CHANGED ブロードキャストを受信するレシーバー。
 * Android 9+ ではバックグラウンドで CallMonitorService が動いているため
 * 主にバックアップ的な役割を担う。
 * 番号取得は TelephonyManager.EXTRA_INCOMING_NUMBER から行う。
 */
class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        Log.d(TAG, "Phone state changed: $state, number=$incomingNumber")

        val serviceIntent = Intent(context, CallRecordingService::class.java).apply {
            action = state
            if (!incomingNumber.isNullOrEmpty()) {
                putExtra(CallMonitorService.EXTRA_PHONE_NUMBER, incomingNumber)
                putExtra(CallMonitorService.EXTRA_DIRECTION, "INCOMING")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
