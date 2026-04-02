package com.example.callmemorecorder.data.local

import com.example.callmemorecorder.domain.model.RecordItem
import com.example.callmemorecorder.domain.model.RecordingStatus
import com.example.callmemorecorder.domain.model.TranscriptionStatus
import com.example.callmemorecorder.domain.model.UploadStatus

/**
 * Extension function to map RecordEntity to domain model
 */
fun RecordEntity.toDomain(): RecordItem {
    return RecordItem(
        id = id,
        title = title,
        createdAt = createdAt,
        durationMs = durationMs,
        localPath = localPath,
        mimeType = mimeType,
        status = try {
            RecordingStatus.valueOf(status)
        } catch (e: IllegalArgumentException) {
            RecordingStatus.IDLE
        },
        uploadStatus = try {
            UploadStatus.valueOf(uploadStatus)
        } catch (e: IllegalArgumentException) {
            UploadStatus.NOT_STARTED
        },
        transcriptionStatus = try {
            TranscriptionStatus.valueOf(transcriptionStatus)
        } catch (e: IllegalArgumentException) {
            TranscriptionStatus.NOT_STARTED
        },
        driveFileId = driveFileId,
        driveWebLink = driveWebLink,
        transcriptText = transcriptText,
        errorMessage = errorMessage,
        updatedAt = updatedAt
    )
}

/**
 * Extension function to map domain model to RecordEntity
 */
fun RecordItem.toEntity(): RecordEntity {
    return RecordEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        durationMs = durationMs,
        localPath = localPath,
        mimeType = mimeType,
        status = status.name,
        uploadStatus = uploadStatus.name,
        transcriptionStatus = transcriptionStatus.name,
        driveFileId = driveFileId,
        driveWebLink = driveWebLink,
        transcriptText = transcriptText,
        errorMessage = errorMessage,
        updatedAt = updatedAt
    )
}
