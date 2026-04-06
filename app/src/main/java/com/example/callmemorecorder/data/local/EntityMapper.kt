package com.example.callmemorecorder.data.local

import com.example.callmemorecorder.domain.model.*

fun RecordEntity.toDomain(): RecordItem = RecordItem(
    id = id,
    title = title,
    createdAt = createdAt,
    durationMs = durationMs,
    localPath = localPath,
    mimeType = mimeType,
    status = runCatching { RecordingStatus.valueOf(status) }.getOrDefault(RecordingStatus.IDLE),
    uploadStatus = runCatching { UploadStatus.valueOf(uploadStatus) }.getOrDefault(UploadStatus.NOT_STARTED),
    transcriptionStatus = runCatching { TranscriptionStatus.valueOf(transcriptionStatus) }.getOrDefault(TranscriptionStatus.NOT_STARTED),
    driveFileId = driveFileId,
    driveWebLink = driveWebLink,
    transcriptText = transcriptText,
    errorMessage = errorMessage,
    updatedAt = updatedAt,
    callerNumber = callerNumber,
    callerName = callerName,
    callDirection = runCatching { CallDirection.valueOf(callDirection) }.getOrDefault(CallDirection.UNKNOWN)
)

fun RecordItem.toEntity(): RecordEntity = RecordEntity(
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
    updatedAt = updatedAt,
    callerNumber = callerNumber,
    callerName = callerName,
    callDirection = callDirection.name
)
