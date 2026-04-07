package com.example.callmemorecorder.data.repository

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Google Drive 連携リポジトリ。
 *
 * ■ サインイン状態の判定について
 *   - cachedAccount: 明示的なサインイン成功時 (cacheSignedInAccount) または
 *     silentSignIn 成功時にセットされる。
 *   - isSignedIn() は cachedAccount を優先し、なければ getLastSignedInAccount で補完。
 *   - trySilentSignIn(): 起動時・画面復帰時に呼び出し、cachedAccount を最新化する。
 *     これにより ViewModel 再生成後もサインイン状態が正しく復元される。
 */
class DriveRepository(private val context: Context) {

    companion object {
        private const val TAG = "DriveRepository"
        private const val APP_NAME = "CallMemoRecorder"
    }

    val isEnabled: Boolean = true

    /** サインイン成功時にキャッシュするアカウント（volatile で最新値を保証） */
    @Volatile
    var cachedAccount: GoogleSignInAccount? = null
        private set

    // ── Sign-In Client ────────────────────────────────────────────────────

    private fun buildGso() = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(DriveScopes.DRIVE_FILE))
        .build()

    fun getSignInClient(): GoogleSignInClient = GoogleSignIn.getClient(context, buildGso())

    fun getSignInIntent(): Intent = getSignInClient().signInIntent

    // ── サインイン状態 ──────────────────────────────────────────────────────

    /**
     * 現在のサインイン状態を返す。
     * cachedAccount を優先し、なければ getLastSignedInAccount で補完する。
     */
    fun isSignedIn(): Boolean {
        if (cachedAccount?.email != null) return true
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        // getLastSignedInAccount で取得できた場合はキャッシュに昇格させる
        if (account.email != null) {
            cachedAccount = account
            return true
        }
        return false
    }

    fun getSignedInEmail(): String? = cachedAccount?.email
        ?: GoogleSignIn.getLastSignedInAccount(context)?.email

    /**
     * サインイン成功時にアカウントを明示的にキャッシュする（signInLauncher から呼ぶ）。
     */
    fun cacheSignedInAccount(account: GoogleSignInAccount) {
        cachedAccount = account
        Log.i(TAG, "cacheSignedInAccount: ${account.email}")
    }

    /**
     * silentSignIn を試みて cachedAccount を最新化する（suspend 関数）。
     * ViewModel の init / refreshDriveSignInState から呼び出す。
     * @return サインイン済みアカウント or null
     */
    suspend fun trySilentSignIn(): GoogleSignInAccount? = withContext(Dispatchers.Main) {
        // getLastSignedInAccount が有効ならそれを使う（同期・高速）
        val last = GoogleSignIn.getLastSignedInAccount(context)
        if (last?.email != null) {
            cachedAccount = last
            Log.i(TAG, "trySilentSignIn: restored from getLastSignedInAccount (${last.email})")
            return@withContext last
        }
        // silentSignIn は非同期 Task なので coroutine で待機
        return@withContext suspendCancellableCoroutine { cont ->
            getSignInClient().silentSignIn()
                .addOnSuccessListener { account ->
                    cachedAccount = account
                    Log.i(TAG, "trySilentSignIn: silentSignIn success (${account.email})")
                    cont.resume(account)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "trySilentSignIn: silentSignIn failed: ${e.message}")
                    cont.resume(null)
                }
        }
    }

    fun clearCache() {
        cachedAccount = null
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        clearCache()
        try { getSignInClient().signOut() } catch (e: Exception) { Log.e(TAG, "signOut: ${e.message}") }
    }

    // ── Drive サービス ────────────────────────────────────────────────────

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

    // ── 接続テスト ────────────────────────────────────────────────────────

    /**
     * 接続テスト: 指定フォルダに "接続テスト.txt" をアップロードする。
     * cachedAccount を優先し、なければ getLastSignedInAccount にフォールバック。
     * @return null = 成功, エラーメッセージ文字列 = 失敗
     */
    suspend fun testConnection(folderName: String): String? = withContext(Dispatchers.IO) {
        val account = cachedAccount
            ?: GoogleSignIn.getLastSignedInAccount(context)
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
            val account = cachedAccount
                ?: GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
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
        val account = cachedAccount
            ?: GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
        try {
            getDriveService(account).files().get(driveFileId).setFields("webViewLink").execute().webViewLink
        } catch (e: Exception) { null }
    }
}
