package com.example.callmemorecorder.data.repository

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
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
 */
class DriveRepository(private val context: Context) {

    companion object {
        private const val TAG = "DriveRepository"
        private const val APP_NAME = "CallMemoRecorder"
    }

    val isEnabled: Boolean = true

    fun getSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(com.google.android.gms.common.api.Scope(DriveScopes.DRIVE_FILE))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun getSignInIntent(): Intent = getSignInClient().signInIntent

    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return account.grantedScopes.any { it.scopeUri.contains("drive") }
    }

    fun getSignedInEmail(): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    suspend fun signOut() = withContext(Dispatchers.IO) {
        try { getSignInClient().signOut() } catch (e: Exception) { Log.e(TAG, "signOut: ${e.message}") }
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE)
        ).apply {
            selectedAccount = Account(account.email, "com.google")
        }
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName(APP_NAME).build()
    }

    suspend fun uploadFile(file: File, folderName: String = "CallMemoRecorder"): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
            try {
                val drive = getDriveService(account)
                val folderId = getOrCreateFolder(drive, folderName)
                val meta = DriveFile().apply {
                    name = file.name
                    mimeType = "audio/m4a"
                    parents = listOf(folderId)
                }
                val content = FileContent("audio/m4a", file)
                val uploaded = drive.files().create(meta, content)
                    .setFields("id, webViewLink").execute()
                val id = uploaded.id ?: return@withContext null
                val link = uploaded.webViewLink ?: "https://drive.google.com/file/d/$id/view"
                Pair(id, link)
            } catch (e: Exception) {
                Log.e(TAG, "uploadFile failed: ${e.message}", e)
                null
            }
        }

    private fun getOrCreateFolder(drive: Drive, name: String): String {
        val list = drive.files().list()
            .setQ("mimeType='application/vnd.google-apps.folder' and name='$name' and trashed=false")
            .setFields("files(id)").execute()
        list.files?.firstOrNull()?.let { return it.id }
        val folder = DriveFile().apply {
            this.name = name
            mimeType = "application/vnd.google-apps.folder"
        }
        return drive.files().create(folder).setFields("id").execute().id
    }

    suspend fun getWebLink(driveFileId: String): String? = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
        try {
            getDriveService(account).files().get(driveFileId).setFields("webViewLink").execute().webViewLink
        } catch (e: Exception) { null }
    }
}
