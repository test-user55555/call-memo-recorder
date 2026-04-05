package com.example.callmemorecorder.worker

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.*
import com.example.callmemorecorder.CallMemoApp
import com.example.callmemorecorder.data.repository.FtpsConfig
import com.example.callmemorecorder.domain.model.UploadStatus
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Drive / FTPS 共通のアップロードワーカー。
 * DataStore の upload_type ("drive" / "ftps" / "none") に応じて処理を分岐する。
 */
class UploadWorker(context: Context, workerParams: WorkerParameters)
    : CoroutineWorker(context, workerParams) {

    private val container get() = (applicationContext as CallMemoApp).container
    private val driveRepo    get() = container.driveRepository
    private val ftpsRepo     get() = container.ftpsRepository
    private val recordRepo   get() = container.recordRepository

    companion object {
        private const val TAG = "UploadWorker"
        const val KEY_RECORD_ID = "record_id"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_FILE_NAME = "file_name"

        fun buildWorkRequest(recordId: String, filePath: String, fileName: String): OneTimeWorkRequest {
            val data = workDataOf(
                KEY_RECORD_ID to recordId,
                KEY_FILE_PATH to filePath,
                KEY_FILE_NAME to fileName
            )
            return OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(data)
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

        val file = File(filePath)
        if (!file.exists()) {
            recordRepo.updateUploadStatus(recordId, UploadStatus.ERROR)
            return Result.failure(workDataOf("error" to "File not found: $filePath"))
        }

        // アップロード種別を DataStore から取得
        val prefs = container.dataStore.data.first()
        val uploadType = prefs[stringPreferencesKey("upload_type")] ?: "none"

        Log.i(TAG, "Starting upload: type=$uploadType, record=$recordId")
        recordRepo.updateUploadStatus(recordId, UploadStatus.UPLOADING)

        return when (uploadType) {
            "drive" -> uploadToDrive(recordId, file, prefs)
            "ftps"  -> uploadToFtps(recordId, file, prefs)
            else    -> {
                Log.d(TAG, "Upload type is 'none', skipping")
                recordRepo.updateUploadStatus(recordId, UploadStatus.NOT_STARTED)
                Result.success(workDataOf("skipped" to true))
            }
        }
    }

    // ── Google Drive ──────────────────────────────────────────
    private suspend fun uploadToDrive(
        recordId: String,
        file: File,
        prefs: androidx.datastore.preferences.core.Preferences
    ): Result {
        if (!driveRepo.isSignedIn()) {
            Log.w(TAG, "Drive: not signed in")
            recordRepo.updateUploadStatus(recordId, UploadStatus.ERROR)
            recordRepo.updateErrorMessage(recordId, "Google Drive に未サインイン")
            return Result.failure(workDataOf("error" to "Not signed in to Drive"))
        }
        val folderName = prefs[stringPreferencesKey("drive_folder_name")]?.takeIf { it.isNotBlank() }
            ?: "CallMemoRecorder"

        return try {
            val result = driveRepo.uploadFile(file, folderName)
            if (result != null) {
                val (fileId, webLink) = result
                recordRepo.updateDriveInfo(recordId, fileId, webLink, UploadStatus.UPLOADED)
                Log.i(TAG, "Drive upload success: $fileId")
                Result.success(workDataOf("driveFileId" to fileId))
            } else {
                handleRetry(recordId, "Drive upload returned null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Drive upload exception", e)
            handleRetry(recordId, e.message)
        }
    }

    // ── FTPS ────────────────────────────────────────────────
    private suspend fun uploadToFtps(
        recordId: String,
        file: File,
        prefs: androidx.datastore.preferences.core.Preferences
    ): Result {
        val host     = prefs[stringPreferencesKey("ftps_host")]     ?: ""
        val port     = prefs[intPreferencesKey("ftps_port")]        ?: 21
        val user     = prefs[stringPreferencesKey("ftps_username")] ?: ""
        val pass     = prefs[stringPreferencesKey("ftps_password")] ?: ""
        val path     = prefs[stringPreferencesKey("ftps_path")]     ?: "/recordings"

        val config = FtpsConfig(host, port, user, pass, path)
        if (!config.isValid()) {
            recordRepo.updateUploadStatus(recordId, UploadStatus.ERROR)
            recordRepo.updateErrorMessage(recordId, "FTPS設定が不完全です")
            return Result.failure(workDataOf("error" to "Invalid FTPS config"))
        }

        return try {
            val uploadedPath = ftpsRepo.uploadFile(file, config)
            if (uploadedPath != null) {
                // FTPS はリンクなし。driveWebLink にパスを記録
                recordRepo.updateDriveInfo(recordId, uploadedPath, "ftps://$host$uploadedPath", UploadStatus.UPLOADED)
                Log.i(TAG, "FTPS upload success: $uploadedPath")
                Result.success(workDataOf("ftpsPath" to uploadedPath))
            } else {
                handleRetry(recordId, "FTPS upload returned null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "FTPS upload exception", e)
            handleRetry(recordId, e.message)
        }
    }

    private suspend fun handleRetry(recordId: String, msg: String?): Result {
        return if (runAttemptCount < 3) {
            Log.w(TAG, "Upload failed (attempt $runAttemptCount), will retry: $msg")
            recordRepo.updateUploadStatus(recordId, UploadStatus.QUEUED)
            Result.retry()
        } else {
            recordRepo.updateUploadStatus(recordId, UploadStatus.ERROR)
            recordRepo.updateErrorMessage(recordId, msg)
            Result.failure(workDataOf("error" to msg))
        }
    }
}
