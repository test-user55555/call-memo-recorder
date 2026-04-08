package com.example.callmemorecorder.worker

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.work.*
import com.example.callmemorecorder.CallMemoApp
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ローカル録音ファイルの自動削除 Worker。
 * 日付変更時（深夜0時以降）に1日1回実行するよう WorkManager でスケジュールする。
 *
 * DataStore の設定:
 *   - auto_delete_enabled (Boolean): 自動削除が有効か
 *   - auto_delete_days    (Int):     何日以上経過したファイルを削除するか（デフォルト30日）
 */
class AutoDeleteWorker(context: Context, workerParams: WorkerParameters)
    : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG            = "AutoDeleteWorker"
        private const val WORK_NAME      = "auto_delete_recordings"

        val KEY_ENABLED = booleanPreferencesKey("auto_delete_enabled")
        val KEY_DAYS    = intPreferencesKey("auto_delete_days")

        /**
         * 定期実行をスケジュール/更新する。
         * アプリ起動時や設定変更時に呼ぶこと。
         * enabled=false の場合はキャンセルする。
         */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                Log.i(TAG, "AutoDeleteWorker: cancelled")
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoDeleteWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .setRequiresCharging(false)
                        .build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,   // 既存スケジュールを維持
                request
            )
            Log.i(TAG, "AutoDeleteWorker: scheduled (daily)")
        }

        /** 設定変更時に既存スケジュールを置き換える（REPLACE） */
        fun reschedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                Log.i(TAG, "AutoDeleteWorker: cancelled (reschedule)")
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoDeleteWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .setRequiresCharging(false)
                        .build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,  // 既存を上書き
                request
            )
            Log.i(TAG, "AutoDeleteWorker: rescheduled (daily, REPLACE)")
        }
    }

    override suspend fun doWork(): Result {
        val container = (applicationContext as CallMemoApp).container
        val prefs     = container.dataStore.data.first()

        val enabled = prefs[KEY_ENABLED] ?: false
        if (!enabled) {
            Log.d(TAG, "Auto-delete is disabled, skipping")
            return Result.success()
        }

        val days    = prefs[KEY_DAYS] ?: 30
        val cutoff  = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000L

        Log.i(TAG, "Auto-delete: running, days=$days, cutoff=${java.util.Date(cutoff)}")

        val records = container.recordRepository.getAllRecords().first()
        var deleted = 0
        var errors  = 0

        for (record in records) {
            if (record.createdAt >= cutoff) continue           // 対象外
            val path = record.localPath ?: continue            // パスなし
            val file = File(path)
            if (!file.exists()) {
                // ファイルはもう存在しないが DB にある → DB からも削除
                try {
                    container.recordRepository.deleteById(record.id)
                    deleted++
                } catch (e: Exception) {
                    Log.w(TAG, "DB delete failed for ${record.id}: ${e.message}")
                    errors++
                }
                continue
            }
            try {
                if (file.delete()) {
                    container.recordRepository.deleteById(record.id)
                    Log.d(TAG, "Deleted: ${file.name}")
                    deleted++
                } else {
                    Log.w(TAG, "Could not delete file: $path")
                    errors++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting $path", e)
                errors++
            }
        }

        Log.i(TAG, "Auto-delete done: deleted=$deleted, errors=$errors")
        return if (errors == 0) Result.success()
        else Result.success(workDataOf("errors" to errors, "deleted" to deleted))
    }
}
