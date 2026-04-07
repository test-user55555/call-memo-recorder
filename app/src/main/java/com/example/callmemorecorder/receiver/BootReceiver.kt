package com.example.callmemorecorder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.example.callmemorecorder.CallMemoApp
import com.example.callmemorecorder.service.CallMonitorService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * 端末再起動時に CallMonitorService を自動起動するレシーバー。
 * DataStore の "auto_start_on_boot" が true（デフォルト）の場合のみ起動する。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private val KEY_AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        private val KEY_AUTO_RECORD_CALL   = booleanPreferencesKey("auto_record_call")
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        Log.i(TAG, "Boot completed — checking auto_start_on_boot preference")

        // goAsync() で非同期処理を許可（BroadcastReceiver は 10 秒以内に完了する必要がある）
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as CallMemoApp).container
                val prefs = container.dataStore.data.first()

                val autoStartOnBoot = prefs[KEY_AUTO_START_ON_BOOT] ?: true
                val autoRecordCall  = prefs[KEY_AUTO_RECORD_CALL]   ?: false

                Log.i(TAG, "autoStartOnBoot=$autoStartOnBoot, autoRecordCall=$autoRecordCall")

                if (autoStartOnBoot && autoRecordCall) {
                    val serviceIntent = CallMonitorService.startIntent(context)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.i(TAG, "CallMonitorService started on boot")
                } else {
                    Log.i(TAG, "Auto-start skipped (autoStartOnBoot=$autoStartOnBoot, autoRecordCall=$autoRecordCall)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read preferences on boot: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
