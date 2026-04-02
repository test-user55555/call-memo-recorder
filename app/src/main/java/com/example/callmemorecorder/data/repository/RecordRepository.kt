package com.example.callmemorecorder.data.repository

import com.example.callmemorecorder.data.local.RecordDao
import com.example.callmemorecorder.data.local.RecordEntity
import com.example.callmemorecorder.data.local.toDomain
import com.example.callmemorecorder.data.local.toEntity
import com.example.callmemorecorder.domain.model.RecordItem
import com.example.callmemorecorder.domain.model.UploadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for local record storage operations
 */
class RecordRepository(
    private val recordDao: RecordDao
) {

    fun getAllRecords(): Flow<List<RecordItem>> {
        return recordDao.getAllRecords().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun getRecordById(id: String): Flow<RecordItem?> {
        return recordDao.getRecordById(id).map { it?.toDomain() }
    }

    suspend fun getRecordByIdOnce(id: String): RecordItem? {
        return recordDao.getRecordByIdOnce(id)?.toDomain()
    }

    suspend fun insertRecord(record: RecordItem) {
        recordDao.insertRecord(record.toEntity())
    }

    suspend fun updateRecord(record: RecordItem) {
        recordDao.updateRecord(record.toEntity())
    }

    suspend fun deleteRecord(record: RecordItem) {
        recordDao.deleteRecord(record.toEntity())
    }

    suspend fun deleteById(id: String) {
        recordDao.deleteById(id)
    }

    suspend fun updateUploadStatus(id: String, status: UploadStatus) {
        recordDao.updateUploadStatus(id, status.name)
    }

    suspend fun updateDriveInfo(
        id: String,
        driveFileId: String?,
        driveWebLink: String?,
        uploadStatus: UploadStatus
    ) {
        recordDao.updateDriveInfo(id, driveFileId, driveWebLink, uploadStatus.name)
    }

    suspend fun updateTranscriptionStatus(
        id: String,
        status: com.example.callmemorecorder.domain.model.TranscriptionStatus,
        text: String? = null
    ) {
        recordDao.updateTranscriptionStatus(id, status.name, text)
    }

    suspend fun updateErrorMessage(id: String, errorMessage: String?) {
        recordDao.updateErrorMessage(id, errorMessage)
    }
}
