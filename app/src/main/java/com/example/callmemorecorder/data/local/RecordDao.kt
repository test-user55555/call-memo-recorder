package com.example.callmemorecorder.data.local

import android.content.ContentValues
import android.database.Cursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordDao(private val db: AppDatabase) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _recordsFlow = MutableStateFlow<List<RecordEntity>>(emptyList())

    init { scope.launch { refreshFlow() } }

    private suspend fun refreshFlow() { _recordsFlow.value = queryAll() }

    fun getAllRecords(): Flow<List<RecordEntity>> = _recordsFlow
    fun getRecordById(id: String): Flow<RecordEntity?> =
        _recordsFlow.map { list -> list.firstOrNull { it.id == id } }

    suspend fun getRecordByIdOnce(id: String): RecordEntity? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            AppDatabase.TABLE_RECORDS, null,
            "${AppDatabase.COL_ID} = ?", arrayOf(id),
            null, null, null
        ).use { if (it.moveToFirst()) it.toEntity() else null }
    }

    suspend fun insertRecord(entity: RecordEntity) = withContext(Dispatchers.IO) {
        db.writableDatabase.insert(AppDatabase.TABLE_RECORDS, null, entity.toContentValues())
        refreshFlow()
    }

    suspend fun updateRecord(entity: RecordEntity) = withContext(Dispatchers.IO) {
        db.writableDatabase.update(
            AppDatabase.TABLE_RECORDS, entity.toContentValues(),
            "${AppDatabase.COL_ID} = ?", arrayOf(entity.id)
        )
        refreshFlow()
    }

    suspend fun deleteRecord(entity: RecordEntity) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete(
            AppDatabase.TABLE_RECORDS,
            "${AppDatabase.COL_ID} = ?", arrayOf(entity.id)
        )
        refreshFlow()
    }

    suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete(
            AppDatabase.TABLE_RECORDS,
            "${AppDatabase.COL_ID} = ?", arrayOf(id)
        )
        refreshFlow()
    }

    suspend fun updateUploadStatus(id: String, status: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.update(
            AppDatabase.TABLE_RECORDS,
            ContentValues().apply {
                put(AppDatabase.COL_UPLOAD_STATUS, status)
                put(AppDatabase.COL_UPDATED_AT, System.currentTimeMillis())
            },
            "${AppDatabase.COL_ID} = ?", arrayOf(id)
        )
        refreshFlow()
    }

    suspend fun updateTranscriptionStatus(id: String, status: String, text: String?) =
        withContext(Dispatchers.IO) {
            db.writableDatabase.update(
                AppDatabase.TABLE_RECORDS,
                ContentValues().apply {
                    put(AppDatabase.COL_TRANSCRIPTION_STATUS, status)
                    if (text != null) put(AppDatabase.COL_TRANSCRIPT_TEXT, text)
                    put(AppDatabase.COL_UPDATED_AT, System.currentTimeMillis())
                },
                "${AppDatabase.COL_ID} = ?", arrayOf(id)
            )
            refreshFlow()
        }

    suspend fun updateDriveInfo(
        id: String, driveFileId: String?, driveWebLink: String?, uploadStatus: String
    ) = withContext(Dispatchers.IO) {
        db.writableDatabase.update(
            AppDatabase.TABLE_RECORDS,
            ContentValues().apply {
                put(AppDatabase.COL_DRIVE_FILE_ID, driveFileId)
                put(AppDatabase.COL_DRIVE_WEB_LINK, driveWebLink)
                put(AppDatabase.COL_UPLOAD_STATUS, uploadStatus)
                put(AppDatabase.COL_UPDATED_AT, System.currentTimeMillis())
            },
            "${AppDatabase.COL_ID} = ?", arrayOf(id)
        )
        refreshFlow()
    }

    suspend fun updateErrorMessage(id: String, errorMessage: String?) = withContext(Dispatchers.IO) {
        db.writableDatabase.update(
            AppDatabase.TABLE_RECORDS,
            ContentValues().apply {
                put(AppDatabase.COL_ERROR_MESSAGE, errorMessage)
                put(AppDatabase.COL_UPDATED_AT, System.currentTimeMillis())
            },
            "${AppDatabase.COL_ID} = ?", arrayOf(id)
        )
        refreshFlow()
    }

    private suspend fun queryAll(): List<RecordEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            AppDatabase.TABLE_RECORDS, null, null, null,
            null, null, "${AppDatabase.COL_CREATED_AT} DESC"
        ).use { c ->
            val list = mutableListOf<RecordEntity>()
            while (c.moveToNext()) list.add(c.toEntity())
            list
        }
    }

    private fun Cursor.toEntity(): RecordEntity {
        fun strOrNull(col: String): String? {
            val idx = getColumnIndex(col)
            return if (idx >= 0 && !isNull(idx)) getString(idx) else null
        }
        fun str(col: String, default: String = ""): String = strOrNull(col) ?: default
        fun lng(col: String, default: Long = 0L): Long {
            val idx = getColumnIndex(col)
            return if (idx >= 0) getLong(idx) else default
        }
        return RecordEntity(
            id = str(AppDatabase.COL_ID),
            title = str(AppDatabase.COL_TITLE),
            createdAt = lng(AppDatabase.COL_CREATED_AT),
            durationMs = lng(AppDatabase.COL_DURATION_MS),
            localPath = strOrNull(AppDatabase.COL_LOCAL_PATH),
            mimeType = str(AppDatabase.COL_MIME_TYPE, "audio/mp4"),
            status = str(AppDatabase.COL_STATUS, "IDLE"),
            uploadStatus = str(AppDatabase.COL_UPLOAD_STATUS, "NOT_STARTED"),
            transcriptionStatus = str(AppDatabase.COL_TRANSCRIPTION_STATUS, "NOT_STARTED"),
            driveFileId = strOrNull(AppDatabase.COL_DRIVE_FILE_ID),
            driveWebLink = strOrNull(AppDatabase.COL_DRIVE_WEB_LINK),
            transcriptText = strOrNull(AppDatabase.COL_TRANSCRIPT_TEXT),
            errorMessage = strOrNull(AppDatabase.COL_ERROR_MESSAGE),
            updatedAt = lng(AppDatabase.COL_UPDATED_AT),
            callerNumber = strOrNull(AppDatabase.COL_CALLER_NUMBER),
            callerName = strOrNull(AppDatabase.COL_CALLER_NAME),
            callDirection = str(AppDatabase.COL_CALL_DIRECTION, "UNKNOWN")
        )
    }

    private fun RecordEntity.toContentValues() = ContentValues().apply {
        put(AppDatabase.COL_ID, id)
        put(AppDatabase.COL_TITLE, title)
        put(AppDatabase.COL_CREATED_AT, createdAt)
        put(AppDatabase.COL_DURATION_MS, durationMs)
        put(AppDatabase.COL_LOCAL_PATH, localPath)
        put(AppDatabase.COL_MIME_TYPE, mimeType)
        put(AppDatabase.COL_STATUS, status)
        put(AppDatabase.COL_UPLOAD_STATUS, uploadStatus)
        put(AppDatabase.COL_TRANSCRIPTION_STATUS, transcriptionStatus)
        put(AppDatabase.COL_DRIVE_FILE_ID, driveFileId)
        put(AppDatabase.COL_DRIVE_WEB_LINK, driveWebLink)
        put(AppDatabase.COL_TRANSCRIPT_TEXT, transcriptText)
        put(AppDatabase.COL_ERROR_MESSAGE, errorMessage)
        put(AppDatabase.COL_UPDATED_AT, updatedAt)
        put(AppDatabase.COL_CALLER_NUMBER, callerNumber)
        put(AppDatabase.COL_CALLER_NAME, callerName)
        put(AppDatabase.COL_CALL_DIRECTION, callDirection)
    }
}
