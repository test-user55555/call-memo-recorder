package com.example.callmemorecorder.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 通話連動録音サービス（廃止済み）。
 *
 * ■ 変更理由 (v1.8.0)
 *   CallMonitorService が通話監視・録音をすべて担当するよう統一した。
 *   本サービスが同時に録音を行うと1通話に対して複数の録音ファイルが
 *   作成されてしまうため、録音処理をすべて無効化した。
 *
 *   CallStateReceiver → CallMonitorService のバックアップ起動のみ行い、
 *   本サービス (CallRecordingService) は起動されてもすぐに終了する。
 */
class CallRecordingService : Service() {

    companion object {
        private const val TAG = "CallRecordingService"
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "call_recording_channel"

        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_CALLER_NAME  = "extra_caller_name"
        const val EXTRA_DIRECTION    = "extra_direction"

        @Volatile var isRecording = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 録音機能は CallMonitorService に統一されたため、何もせず終了する
        Log.d(TAG, "CallRecordingService.onStartCommand – no-op (録音はCallMonitorServiceが担当)")
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("unused")
    fun startService(ctx: Context) {
        // 互換性のために残しているが実際には何もしない
    }
}
