package com.example.callmemorecorder.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit API interface for transcription backend
 * Backend URL configured via BuildConfig.BACKEND_BASE_URL
 * If not configured, TRANSCRIPTION_ENABLED=false and this is never called
 */
interface TranscriptionApiService {

    /**
     * Submit audio for transcription
     */
    @POST("api/transcriptions")
    suspend fun submitTranscription(
        @Body request: TranscriptionRequest
    ): Response<TranscriptionResponse>

    /**
     * Poll transcription status
     */
    @GET("api/transcriptions/{recordId}")
    suspend fun getTranscriptionStatus(
        @Path("recordId") recordId: String
    ): Response<TranscriptionStatusResponse>
}
