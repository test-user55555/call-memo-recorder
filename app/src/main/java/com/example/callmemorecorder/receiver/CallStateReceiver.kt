package com.example.callmemorecorder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.example.callmemorecorder.service.CallMonitorService

/**
 * システムの PHONE_STATE_CHANGED ブロードキャストを受信するレシーバー。
 * CallMonitorService が起動していない場合のバックアップ起動のみ担当。
 * 実際の通話検知・録音は CallMonitorService (TelephonyCallback/PhoneStateListener) が行う。
 */
class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        Log.d(TAG, "Phone state changed: $state")

        // CallMonitorService が起動していない場合は起動しておく（バックアップ起動）
        // 実際の録音制御は CallMonitorService の Telephony リスナーが担う
        val serviceIntent = CallMonitorService.startIntent(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start CallMonitorService: ${e.message}")
        }
    }
}
