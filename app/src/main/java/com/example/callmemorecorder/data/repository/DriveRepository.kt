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
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
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
import java.util.Collections
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
 *
 * ■ Drive API 認証について
 *   - GoogleAccountCredential.usingOAuth2() を使用
 *   - selectedAccount には Account オブジェクトをセット（メールアドレスで特定）
 *   - これにより Android の AccountManager 経由でトークンが取得される
 */
class DriveRepository(private val context: Context) {

    companion object {
        private const val TAG = "DriveRepository"
        private const val APP_NAME = "CallMemoRecorder"
        // Web アプリケーション用 OAuth クライアントID
        // Google Cloud Console → 認証情報 → OAuth クライアントID（ウェブアプリ）で取得
        private const val WEB_CLIENT_ID =
            "174314400551-uhhmtk96k7j3tgsdtbnssdnihcjleu0u.apps.googleusercontent.com"
    }

    val isEnabled: Boolean = true

    /** サインイン成功時にキャッシュするアカウント（volatile で最新値を保証） */
    @Volatile
    var cachedAccount: GoogleSignInAccount? = null
        private set

    // ── Sign-In Client ────────────────────────────────────────────────────

    private fun buildGso() = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        // Web アプリケーション用クライアントID を指定することで
        // Drive スコープの OAuth トークンが正しく発行される（DEVELOPER_ERROR code=10 の解消）
        .requestIdToken(WEB_CLIENT_ID)
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
        val last = GoogleSignIn.getLastSignedInAccount(context)
        if (last?.email != null) {
            cachedAccount = last
            Log.i(TAG, "trySilentSignIn: restored from getLastSignedInAccount (${last.email})")
            return@withContext last
        }
        return@withContext suspendCancellableCoroutine<GoogleSignInAccount?> { cont ->
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

    /**
     * GoogleAccountCredential を使って Drive サービスを構築する。
     * usingOAuth2 + Collections.singleton(DRIVE_FILE) で正しいスコープを要求。
     */
    private fun getDriveService(account: GoogleSignInAccount): Drive {
        Log.d(TAG, "getDriveService: email=${account.email}")
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            Collections.singleton(DriveScopes.DRIVE_FILE)
        ).apply {
            selectedAccount = Account(account.email ?: "", "com.google")
            Log.d(TAG, "getDriveService: selectedAccount=${selectedAccount?.name}")
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
     * 各ステップの進捗を progressCallback で通知する。
     * @return null = 成功, エラーメッセージ文字列 = 失敗
     */
    suspend fun testConnection(
        folderName: String,
        progressCallback: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {

        fun progress(msg: String) {
            Log.i(TAG, "testConnection: $msg")
            progressCallback?.invoke(msg)
        }

        // ── Step 1: アカウント確認 ──────────────────────────────────────
        progress("Step 1/4: アカウント確認中...")
        val account = cachedAccount
            ?: GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            val err = "❌ Google アカウントにサインインしていません"
            progress(err)
            return@withContext err
        }
        val email = account.email
        if (email == null) {
            val err = "❌ アカウントのメールアドレスが取得できません"
            progress(err)
            return@withContext err
        }
        progress("✅ アカウント確認OK: $email")

        // ── Step 2: Drive サービス初期化 ───────────────────────────────
        progress("Step 2/4: Drive サービス初期化中...")
        val drive: Drive
        try {
            drive = getDriveService(account)
            progress("✅ Drive サービス初期化OK")
        } catch (e: Exception) {
            val err = "❌ Drive サービス初期化失敗: ${e.javaClass.simpleName}: ${e.message ?: "詳細不明"}"
            progress(err)
            Log.e(TAG, "getDriveService failed", e)
            return@withContext err
        }

        // ── Step 3: フォルダ作成/取得 ──────────────────────────────────
        progress("Step 3/4: フォルダ「$folderName」を確認/作成中...")
        val folderId: String
        try {
            folderId = getOrCreateFolder(drive, folderName)
            progress("✅ フォルダID取得OK: $folderId")
        } catch (e: UserRecoverableAuthIOException) {
            val err = "❌ 認証エラー(UserRecoverableAuth): Drive スコープが未承認です。アプリを再インストールするか再サインインしてください。"
            progress(err)
            Log.e(TAG, "UserRecoverableAuthIOException", e)
            return@withContext err
        } catch (e: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
            val err = when (e.statusCode) {
                401 -> "❌ 認証エラー(401): Drive の権限を再確認してください"
                403 -> "❌ アクセス拒否(403): Drive スコープが許可されていません。OAuthスコープを確認してください"
                else -> "❌ Google API エラー(${e.statusCode}): ${e.details?.message ?: e.message ?: "詳細不明"}"
            }
            progress(err)
            Log.e(TAG, "GoogleJsonResponseException status=${e.statusCode}", e)
            return@withContext err
        } catch (e: Exception) {
            val cause = e.cause
            val detail = when {
                e.message != null -> e.message!!
                cause?.message != null -> "${e.javaClass.simpleName} caused by: ${cause.message}"
                else -> "${e.javaClass.simpleName}(原因不明)"
            }
            val err = "❌ フォルダ操作エラー: $detail"
            progress(err)
            Log.e(TAG, "getOrCreateFolder failed: ${e.javaClass.name}", e)
            return@withContext err
        }

        // ── Step 4: テストファイルアップロード ─────────────────────────
        progress("Step 4/4: テストファイルをアップロード中...")
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val content = "Call Memo Recorder 接続テスト\n実行日時: $timestamp\nフォルダ: $folderName\nアカウント: $email\n"
            val byteContent = ByteArrayContent("text/plain", content.toByteArray(Charsets.UTF_8))

            val meta = DriveFile().apply {
                name = "接続テスト.txt"
                mimeType = "text/plain"
                parents = listOf(folderId)
            }

            // 既存テストファイルの削除（エラーは無視）
            try {
                deleteExistingTestFile(drive, folderId)
            } catch (e: Exception) {
                Log.w(TAG, "deleteExistingTestFile warning: ${e.message}")
            }

            val created = drive.files().create(meta, byteContent)
                .setFields("id, name")
                .execute()

            val createdId = created?.id ?: "id不明"
            progress("✅ アップロード成功: fileId=$createdId")
            Log.i(TAG, "testConnection: success (folder=$folderName, fileId=$createdId)")
            null // 成功 (null = エラーなし)

        } catch (e: UserRecoverableAuthIOException) {
            val err = "❌ 認証エラー(UserRecoverableAuth): Drive スコープが未承認。再サインインしてください"
            progress(err)
            Log.e(TAG, "Upload UserRecoverableAuthIOException", e)
            err
        } catch (e: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
            val err = when (e.statusCode) {
                401 -> "❌ 認証エラー(401): アップロード時の認証失敗"
                403 -> "❌ アクセス拒否(403): Drive スコープが不足しています"
                else -> "❌ Google API エラー(${e.statusCode}): ${e.details?.message ?: e.message ?: "詳細不明"}"
            }
            progress(err)
            Log.e(TAG, "Upload GoogleJsonResponseException status=${e.statusCode}", e)
            err
        } catch (e: Exception) {
            val cause = e.cause
            val detail = when {
                e.message != null -> e.message!!
                cause?.message != null -> "${e.javaClass.simpleName} caused by: ${cause.message}"
                else -> "${e.javaClass.simpleName}(原因不明)"
            }
            val err = "❌ アップロードエラー: $detail"
            progress(err)
            Log.e(TAG, "Upload failed: ${e.javaClass.name}", e)
            err
        }
    }

    private fun deleteExistingTestFile(drive: Drive, folderId: String) {
        val list = drive.files().list()
            .setQ("name='接続テスト.txt' and '${folderId}' in parents and trashed=false")
            .setFields("files(id)").execute()
        list.files?.forEach { drive.files().delete(it.id).execute() }
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
                Log.e(TAG, "uploadFile failed: ${e.javaClass.name}: ${e.message}", e)
                null
            }
        }

    private fun getOrCreateFolder(drive: Drive, name: String): String {
        Log.d(TAG, "getOrCreateFolder: name=$name")
        // 既存フォルダを検索
        val listResult = drive.files().list()
            .setQ("mimeType='application/vnd.google-apps.folder' and name='${name.replace("'", "\\'")}' and trashed=false")
            .setFields("files(id, name)")
            .setSpaces("drive")
            .execute()

        val existing = listResult.files?.firstOrNull()
        if (existing != null) {
            Log.d(TAG, "getOrCreateFolder: found existing id=${existing.id}")
            return existing.id
                ?: throw IllegalStateException("フォルダIDがnullです (name=$name)")
        }

        // 新規作成
        Log.d(TAG, "getOrCreateFolder: creating new folder '$name'")
        val folder = DriveFile().apply {
            this.name = name
            mimeType = "application/vnd.google-apps.folder"
        }
        val created = drive.files().create(folder).setFields("id").execute()
        return created.id
            ?: throw IllegalStateException("作成されたフォルダのIDがnullです (name=$name)")
    }

    suspend fun getWebLink(driveFileId: String): String? = withContext(Dispatchers.IO) {
        val account = cachedAccount
            ?: GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
        try {
            getDriveService(account).files().get(driveFileId).setFields("webViewLink").execute().webViewLink
        } catch (e: Exception) { null }
    }
}
