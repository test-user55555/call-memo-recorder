package com.example.callmemorecorder.data.repository

import android.util.Log
import com.example.callmemorecorder.BuildConfig
import com.example.callmemorecorder.data.remote.TranscriptionApiService
import com.example.callmemorecorder.data.remote.TranscriptionRequest
import com.example.callmemorecorder.domain.model.TranscriptionStatus

/**
 * Repository stub for transcription backend operations.
 *
 * NOTE: Transcription requires:
 * 1. TRANSCRIPTION_ENABLED=true in local.properties
 * 2. BACKEND_BASE_URL set to your backend URL
 *
 * Current status: Gracefully degrades when not configured.
 */
class TranscriptionRepository(
    private val apiService: TranscriptionApiService?
) {
    companion object {
        private const val TAG = "TranscriptionRepository"
    }

    val isEnabled: Boolean = BuildConfig.TRANSCRIPTION_ENABLED

    /**
     * Submit a recording for transcription
     * Returns TranscriptionStatus reflecting the result
     */
    suspend fun submitTranscription(
        recordId: String,
        driveFileId: String,
        fileName: String
    ): TranscriptionStatus {
        if (!isEnabled) {
            Log.d(TAG, "submitTranscription: Transcription not enabled")
            return TranscriptionStatus.NOT_STARTED
        }
        if (apiService == null) {
            Log.w(TAG, "submitTranscription: API service not initialized")
            return TranscriptionStatus.ERROR
        }

        return try {
            val request = TranscriptionRequest(
                recordId = recordId,
                driveFileId = driveFileId,
                fileName = fileName
            )
            val response = apiService.submitTranscription(request)
            if (response.isSuccessful) {
                val body = response.body()
                when (body?.status) {
                    "queued" -> TranscriptionStatus.QUEUED
                    "processing" -> TranscriptionStatus.PROCESSING
                    "completed" -> TranscriptionStatus.COMPLETED
                    "error" -> TranscriptionStatus.ERROR
                    else -> TranscriptionStatus.QUEUED
                }
            } else {
                Log.e(TAG, "submitTranscription: HTTP error ${response.code()}")
                TranscriptionStatus.ERROR
            }
        } catch (e: Exception) {
            Log.e(TAG, "submitTranscription: Exception", e)
            TranscriptionStatus.ERROR
        }
    }

    /**
     * Poll transcription status
     * Returns (status, text) pair
     */
    suspend fun pollStatus(recordId: String): Pair<TranscriptionStatus, String?> {
        if (!isEnabled || apiService == null) {
            return Pair(TranscriptionStatus.NOT_STARTED, null)
        }

        return try {
            val response = apiService.getTranscriptionStatus(recordId)
            if (response.isSuccessful) {
                val body = response.body()
                val status = when (body?.status) {
                    "queued" -> TranscriptionStatus.QUEUED
                    "processing" -> TranscriptionStatus.PROCESSING
                    "completed" -> TranscriptionStatus.COMPLETED
                    "error" -> TranscriptionStatus.ERROR
                    else -> TranscriptionStatus.NOT_STARTED
                }
                Pair(status, body?.text)
            } else {
                Pair(TranscriptionStatus.ERROR, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "pollStatus: Exception", e)
            Pair(TranscriptionStatus.ERROR, null)
        }
    }
}
