package com.example.callmemorecorder.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.callmemorecorder.CallMemoApp
import com.example.callmemorecorder.data.repository.DriveRepository
import com.example.callmemorecorder.data.repository.RecordRepository
import com.example.callmemorecorder.domain.model.UploadStatus
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager Worker for uploading recordings to Google Drive.
 * Uses AppContainer for dependency injection (no Hilt).
 */
class UploadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val container get() = (applicationContext as CallMemoApp).container
    private val driveRepository: DriveRepository get() = container.driveRepository
    private val recordRepository: RecordRepository get() = container.recordRepository

    companion object {
        private const val TAG = "UploadWorker"
        const val KEY_RECORD_ID = "record_id"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_FILE_NAME = "file_name"

        fun buildWorkRequest(recordId: String, filePath: String, fileName: String): OneTimeWorkRequest {
            val inputData = workDataOf(
                KEY_RECORD_ID to recordId,
                KEY_FILE_PATH to filePath,
                KEY_FILE_NAME to fileName
            )
            return OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(inputData)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("upload_$recordId")
                .build()
        }
    }

    override suspend fun doWork(): Result {
        val recordId = inputData.getString(KEY_RECORD_ID)
            ?: return Result.failure(workDataOf("error" to "Missing record ID"))
        val filePath = inputData.getString(KEY_FILE_PATH)
            ?: return Result.failure(workDataOf("error" to "Missing file path"))
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "recording.m4a"

        Log.i(TAG, "Starting upload for record: $recordId")
        recordRepository.updateUploadStatus(recordId, UploadStatus.UPLOADING)

        if (!driveRepository.isEnabled) {
            Log.d(TAG, "Drive not enabled, skipping upload for: $recordId")
            recordRepository.updateUploadStatus(recordId, UploadStatus.NOT_STARTED)
            return Result.success(workDataOf("skipped" to true))
        }

        val file = File(filePath)
        if (!file.exists()) {
            recordRepository.updateUploadStatus(recordId, UploadStatus.ERROR)
            return Result.failure(workDataOf("error" to "File not found"))
        }

        return try {
            val result = driveRepository.uploadFile(file)
            if (result != null) {
                val (driveFileId, driveWebLink) = result
                recordRepository.updateDriveInfo(recordId, driveFileId, driveWebLink, UploadStatus.UPLOADED)
                Result.success(workDataOf("driveFileId" to driveFileId))
            } else {
                recordRepository.updateUploadStatus(recordId, UploadStatus.ERROR)
                if (runAttemptCount < 3) Result.retry()
                else Result.failure(workDataOf("error" to "Upload failed after retries"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception for record: $recordId", e)
            recordRepository.updateUploadStatus(recordId, UploadStatus.ERROR)
            recordRepository.updateErrorMessage(recordId, e.message)
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf("error" to (e.message ?: "Upload exception")))
        }
    }
}
