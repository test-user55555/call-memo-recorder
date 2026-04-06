package com.example.callmemorecorder.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {
    companion object {
        const val DATABASE_NAME = "call_memo_recorder_db"
        const val DATABASE_VERSION = 2   // v1→v2: callerNumber/callerName/callDirection 追加

        const val TABLE_RECORDS = "records"
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_CREATED_AT = "createdAt"
        const val COL_DURATION_MS = "durationMs"
        const val COL_LOCAL_PATH = "localPath"
        const val COL_MIME_TYPE = "mimeType"
        const val COL_STATUS = "status"
        const val COL_UPLOAD_STATUS = "uploadStatus"
        const val COL_TRANSCRIPTION_STATUS = "transcriptionStatus"
        const val COL_DRIVE_FILE_ID = "driveFileId"
        const val COL_DRIVE_WEB_LINK = "driveWebLink"
        const val COL_TRANSCRIPT_TEXT = "transcriptText"
        const val COL_ERROR_MESSAGE = "errorMessage"
        const val COL_UPDATED_AT = "updatedAt"
        // v2 追加列
        const val COL_CALLER_NUMBER = "callerNumber"
        const val COL_CALLER_NAME = "callerName"
        const val COL_CALL_DIRECTION = "callDirection"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_RECORDS (
                $COL_ID TEXT PRIMARY KEY,
                $COL_TITLE TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_DURATION_MS INTEGER NOT NULL DEFAULT 0,
                $COL_LOCAL_PATH TEXT,
                $COL_MIME_TYPE TEXT NOT NULL DEFAULT 'audio/mp4',
                $COL_STATUS TEXT NOT NULL DEFAULT 'IDLE',
                $COL_UPLOAD_STATUS TEXT NOT NULL DEFAULT 'NOT_STARTED',
                $COL_TRANSCRIPTION_STATUS TEXT NOT NULL DEFAULT 'NOT_STARTED',
                $COL_DRIVE_FILE_ID TEXT,
                $COL_DRIVE_WEB_LINK TEXT,
                $COL_TRANSCRIPT_TEXT TEXT,
                $COL_ERROR_MESSAGE TEXT,
                $COL_UPDATED_AT INTEGER NOT NULL,
                $COL_CALLER_NUMBER TEXT,
                $COL_CALLER_NAME TEXT,
                $COL_CALL_DIRECTION TEXT NOT NULL DEFAULT 'UNKNOWN'
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 → v2: 通話情報カラムを追加（既存データを保持）
            db.execSQL("ALTER TABLE $TABLE_RECORDS ADD COLUMN $COL_CALLER_NUMBER TEXT")
            db.execSQL("ALTER TABLE $TABLE_RECORDS ADD COLUMN $COL_CALLER_NAME TEXT")
            db.execSQL("ALTER TABLE $TABLE_RECORDS ADD COLUMN $COL_CALL_DIRECTION TEXT NOT NULL DEFAULT 'UNKNOWN'")
        }
    }
}
