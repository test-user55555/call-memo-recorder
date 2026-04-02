package com.example.callmemorecorder.data.remote

import com.google.gson.annotations.SerializedName

/**
 * Request body for transcription API
 * POST /api/transcriptions
 */
data class TranscriptionRequest(
    @SerializedName("recordId")
    val recordId: String,
    @SerializedName("driveFileId")
    val driveFileId: String,
    @SerializedName("fileName")
    val fileName: String
)

/**
 * Response from transcription API
 */
data class TranscriptionResponse(
    @SerializedName("status")
    val status: String,           // "queued" | "processing" | "completed" | "error"
    @SerializedName("text")
    val text: String?,
    @SerializedName("errorMessage")
    val errorMessage: String?
)

/**
 * GET /api/transcriptions/{recordId} response
 */
data class TranscriptionStatusResponse(
    @SerializedName("status")
    val status: String,
    @SerializedName("text")
    val text: String?,
    @SerializedName("errorMessage")
    val errorMessage: String?
)
