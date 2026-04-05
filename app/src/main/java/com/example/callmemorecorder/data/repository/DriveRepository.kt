package com.example.callmemorecorder.data.repository

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Google Drive連携リポジトリ。
 * Google Sign-In + Drive REST APIを使用。
 * google-services.json不要。
 */
class DriveRepository(private val context: Context) {

    companion object {
        private const val TAG = "DriveRepository"
        private const val APP_NAME = "CallMemoRecorder"
        private const val FOLDER_NAME = "CallMemoRecorder"
    }

    val isEnabled: Boolean = true

    /** Google Sign-Inクライアント（Drive権限付き） */
    fun getSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(com.google.android.gms.common.api.Scope(DriveScopes.DRIVE_FILE))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    /** サインインIntentを返す（ActivityからstartActivityForResultで使用） */
    fun getSignInIntent(): Intent = getSignInClient().signInIntent

    /** 現在のサインイン状態を確認 */
    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null && account.grantedScopes.any {
            it.scopeUri.contains("drive")
        }
    }

    /** サインイン中のアカウントのメールアドレスを取得 */
    fun getSignedInEmail(): String? {
        return GoogleSignIn.getLastSignedInAccount(context)?.email
    }

    /** サインアウト */
    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            try {
                getSignInClient().signOut()
                Log.d(TAG, "signed out successfully")
            } catch (e: Exception) {
                Log.e(TAG, "signOut error: ${e.message}")
            }
        }
    }

    /** Drive APIクライアントを取得 */
    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE)
        ).apply {
            selectedAccount = Account(account.email, "com.google")
        }
        return Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(APP_NAME)
            .build()
    }

    /**
     * ファイルをGoogle Driveにアップロード
     * @return (driveFileId, driveWebLink) または null
     */
    suspend fun uploadFile(
        file: File,
        folderName: String = FOLDER_NAME
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            Log.w(TAG, "uploadFile: not signed in")
            return@withContext null
        }
        try {
            val driveService = getDriveService(account)

            // フォルダを作成または取得
            val folderId = getOrCreateFolder(driveService, folderName)

            // ファイルメタデータ
            val metadata = DriveFile().apply {
                name = file.name
                mimeType = "audio/m4a"
                parents = listOf(folderId)
            }

            // アップロード
            val mediaContent = FileContent("audio/m4a", file)
            val uploadedFile = driveService.files().create(metadata, mediaContent)
                .setFields("id, webViewLink")
                .execute()

            val fileId = uploadedFile.id ?: return@withContext null
            val webLink = uploadedFile.webViewLink ?: "https://drive.google.com/file/d/$fileId/view"

            Log.d(TAG, "uploaded: id=$fileId, link=$webLink")
            Pair(fileId, webLink)

        } catch (e: Exception) {
            Log.e(TAG, "uploadFile failed: ${e.message}", e)
            null
        }
    }

    /** DriveフォルダIDを取得または新規作成 */
    private fun getOrCreateFolder(drive: Drive, folderName: String): String {
        // 既存フォルダを検索
        val result = drive.files().list()
            .setQ("mimeType='application/vnd.google-apps.folder' and name='$folderName' and trashed=false")
            .setFields("files(id, name)")
            .execute()

        val existing = result.files?.firstOrNull()
        if (existing != null) {
            Log.d(TAG, "found existing folder: ${existing.id}")
            return existing.id
        }

        // 新規作成
        val folderMetadata = DriveFile().apply {
            name = folderName
            mimeType = "application/vnd.google-apps.folder"
        }
        val folder = drive.files().create(folderMetadata)
            .setFields("id")
            .execute()

        Log.d(TAG, "created new folder: ${folder.id}")
        return folder.id
    }

    /** Drive上のファイルのWebリンクを取得 */
    suspend fun getWebLink(driveFileId: String): String? = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
        try {
            val driveService = getDriveService(account)
            val file = driveService.files().get(driveFileId)
                .setFields("webViewLink")
                .execute()
            file.webViewLink
        } catch (e: Exception) {
            Log.e(TAG, "getWebLink failed: ${e.message}")
            null
        }
    }
}
