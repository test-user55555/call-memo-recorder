package com.example.callmemorecorder.domain.model

/**
 * Recording status enum
 */
enum class RecordingStatus {
    IDLE,
    RECORDING,
    SAVED,
    FAILED
}

/**
 * Upload status enum
 */
enum class UploadStatus {
    NOT_STARTED,
    QUEUED,
    UPLOADING,
    UPLOADED,
    ERROR
}

/**
 * Transcription status enum
 */
enum class TranscriptionStatus {
    NOT_STARTED,
    QUEUED,
    PROCESSING,
    COMPLETED,
    ERROR
}

/**
 * Domain model for a recording record
 */
data class RecordItem(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val localPath: String?,
    val mimeType: String,
    val status: RecordingStatus,
    val uploadStatus: UploadStatus,
    val transcriptionStatus: TranscriptionStatus,
    val driveFileId: String?,
    val driveWebLink: String?,
    val transcriptText: String?,
    val errorMessage: String?,
    val updatedAt: Long
)
