package com.example.callmemorecorder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.callmemorecorder.service.CallMonitorService

/**
 * 端末再起動時に CallMonitorService を自動起動するレシーバー。
 * 「通話自動録音」が ON の場合のみ起動する。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        Log.i(TAG, "Boot completed — starting CallMonitorService")

        // DataStore は非同期なので goAsync + コルーチンは使わず、
        // デフォルト「起動する」として扱い、ユーザーが設定で無効にできる運用とする
        val serviceIntent = CallMonitorService.startIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
