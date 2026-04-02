package com.example.callmemorecorder.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.callmemorecorder.CallMemoApp
import com.example.callmemorecorder.data.repository.RecordRepository
import com.example.callmemorecorder.data.repository.TranscriptionRepository
import com.example.callmemorecorder.domain.model.TranscriptionStatus
import java.util.concurrent.TimeUnit

/**
 * WorkManager Worker for transcription.
 * Uses AppContainer for dependency injection (no Hilt).
 */
class TranscriptionWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val container get() = (applicationContext as CallMemoApp).container
    private val transcriptionRepository: TranscriptionRepository get() = container.transcriptionRepository
    private val recordRepository: RecordRepository get() = container.recordRepository

    companion object {
        private const val TAG = "TranscriptionWorker"
        const val KEY_RECORD_ID = "record_id"
        const val KEY_DRIVE_FILE_ID = "drive_file_id"
        const val KEY_FILE_NAME = "file_name"

        fun buildWorkRequest(recordId: String, driveFileId: String, fileName: String): OneTimeWorkRequest {
            val inputData = workDataOf(
                KEY_RECORD_ID to recordId,
                KEY_DRIVE_FILE_ID to driveFileId,
                KEY_FILE_NAME to fileName
            )
            return OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(inputData)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("transcription_$recordId")
                .build()
        }
    }

    override suspend fun doWork(): Result {
        val recordId = inputData.getString(KEY_RECORD_ID)
            ?: return Result.failure(workDataOf("error" to "Missing record ID"))
        val driveFileId = inputData.getString(KEY_DRIVE_FILE_ID)
            ?: return Result.failure(workDataOf("error" to "Missing drive file ID"))
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "recording.m4a"

        Log.i(TAG, "Starting transcription for record: $recordId")

        if (!transcriptionRepository.isEnabled) {
            Log.d(TAG, "Transcription not enabled, skipping: $recordId")
            recordRepository.updateTranscriptionStatus(recordId, TranscriptionStatus.NOT_STARTED)
            return Result.success(workDataOf("skipped" to true))
        }

        recordRepository.updateTranscriptionStatus(recordId, TranscriptionStatus.QUEUED)

        return try {
            val status = transcriptionRepository.submitTranscription(recordId, driveFileId, fileName)
            when (status) {
                TranscriptionStatus.COMPLETED -> {
                    val (finalStatus, text) = transcriptionRepository.pollStatus(recordId)
                    recordRepository.updateTranscriptionStatus(recordId, finalStatus, text)
                    Result.success(workDataOf("status" to finalStatus.name))
                }
                TranscriptionStatus.ERROR -> {
                    recordRepository.updateTranscriptionStatus(recordId, TranscriptionStatus.ERROR)
                    if (runAttemptCount < 3) Result.retry()
                    else Result.failure(workDataOf("error" to "Transcription failed"))
                }
                else -> {
                    recordRepository.updateTranscriptionStatus(recordId, status)
                    Result.success(workDataOf("status" to status.name))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription exception for record: $recordId", e)
            recordRepository.updateTranscriptionStatus(recordId, TranscriptionStatus.ERROR)
            recordRepository.updateErrorMessage(recordId, e.message)
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf("error" to (e.message ?: "Transcription exception")))
        }
    }
}
