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
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Google Drive 連携リポジトリ。
 * Google Sign-In + Drive REST API を使用。
 *
 * ■ isSignedIn() の判定について
 *   GoogleSignIn.getLastSignedInAccount() は直近のアカウント情報をローカルキャッシュから
 *   返すだけで、実際に Drive スコープが付与されているかは granted scopes から確認する。
 *   ただし getGrantedScopes() は GoogleSignInAccount が null でなければ
 *   常に空コレクションを返す端末もあるため、email が取れていれば接続済みとみなす
 *   実用的な判定を採用する（テスト接続で実際の認証有無を確認する設計）。
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

    /**
     * Google アカウントにサインイン済みかつ email が取得できているかで判定。
     * 実際の Drive アクセス権は testConnection() で確認できる。
     */
    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return account.email != null
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
            selectedAccount = Account(account.email ?: "", "com.google")
        }
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName(APP_NAME).build()
    }

    /**
     * 接続テスト: 指定フォルダに "接続テスト.txt" をアップロードする。
     * @return null = 成功, エラーメッセージ文字列 = 失敗
     */
    suspend fun testConnection(folderName: String): String? = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: return@withContext "Google アカウントにサインインしていません"
        if (account.email == null)
            return@withContext "アカウントのメールアドレスが取得できません"
        try {
            val drive = getDriveService(account)
            val folderId = getOrCreateFolder(drive, folderName)

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val content = "Call Memo Recorder 接続テスト\n実行日時: $timestamp\nフォルダ: $folderName\n"
            val byteContent = ByteArrayContent("text/plain", content.toByteArray(Charsets.UTF_8))

            val meta = DriveFile().apply {
                name = "接続テスト.txt"
                mimeType = "text/plain"
                parents = listOf(folderId)
            }
            // 同名ファイルがある場合は削除してから再作成
            deleteExistingTestFile(drive, folderId)

            drive.files().create(meta, byteContent)
                .setFields("id, name")
                .execute()
            Log.i(TAG, "testConnection: success (folder=$folderName)")
            null // 成功
        } catch (e: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
            val msg = when (e.statusCode) {
                401 -> "認証エラー: Drive の権限を再確認してください (再サインインが必要な場合があります)"
                403 -> "アクセス拒否: Drive スコープが許可されていません"
                else -> "Google API エラー (${e.statusCode}): ${e.details?.message}"
            }
            Log.e(TAG, "testConnection failed: $msg", e)
            msg
        } catch (e: Exception) {
            Log.e(TAG, "testConnection error", e)
            "接続エラー: ${e.message}"
        }
    }

    private fun deleteExistingTestFile(drive: Drive, folderId: String) {
        try {
            val list = drive.files().list()
                .setQ("name='接続テスト.txt' and '${folderId}' in parents and trashed=false")
                .setFields("files(id)").execute()
            list.files?.forEach { drive.files().delete(it.id).execute() }
        } catch (e: Exception) { /* ignore */ }
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
