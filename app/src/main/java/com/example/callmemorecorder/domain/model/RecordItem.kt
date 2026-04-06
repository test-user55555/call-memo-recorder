package com.example.callmemorecorder.domain.model

/** 録音ステータス */
enum class RecordingStatus { IDLE, RECORDING, SAVED, FAILED }

/** アップロードステータス */
enum class UploadStatus { NOT_STARTED, QUEUED, UPLOADING, UPLOADED, ERROR }

/** 文字起こしステータス */
enum class TranscriptionStatus { NOT_STARTED, QUEUED, PROCESSING, COMPLETED, ERROR }

/** 発着信方向 */
enum class CallDirection { INCOMING, OUTGOING, UNKNOWN }

/** 録音レコードのドメインモデル */
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
    val updatedAt: Long,
    // 通話情報
    val callerNumber: String? = null,   // 電話番号
    val callerName: String? = null,     // 連絡先名（取得できた場合）
    val callDirection: CallDirection = CallDirection.UNKNOWN
)
