package com.example.callmemorecorder.data.local

data class RecordEntity(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long = 0L,
    val localPath: String? = null,
    val mimeType: String = "audio/mp4",
    val status: String = "IDLE",
    val uploadStatus: String = "NOT_STARTED",
    val transcriptionStatus: String = "NOT_STARTED",
    val driveFileId: String? = null,
    val driveWebLink: String? = null,
    val transcriptText: String? = null,
    val errorMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    // 通話情報（DB v2 で追加）
    val callerNumber: String? = null,
    val callerName: String? = null,
    val callDirection: String = "UNKNOWN"   // INCOMING / OUTGOING / UNKNOWN
)
