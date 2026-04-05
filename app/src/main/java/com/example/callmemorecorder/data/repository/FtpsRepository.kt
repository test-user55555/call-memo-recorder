package com.example.callmemorecorder.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.io.FileInputStream

/**
 * FTPS（FTP over SSL/TLS）でファイルをアップロードするリポジトリ。
 * 設定画面で入力した接続情報を使用する。
 */
class FtpsRepository {

    companion object {
        private const val TAG = "FtpsRepository"
        const val DEFAULT_PORT = 21
        const val TIMEOUT_MS = 30_000
    }

    /**
     * FTPSサーバーへの接続をテストする
     * @return null = 成功, エラーメッセージ文字列 = 失敗
     */
    suspend fun testConnection(config: FtpsConfig): String? = withContext(Dispatchers.IO) {
        val client = FTPSClient("TLS", false)
        try {
            client.connectTimeout = TIMEOUT_MS
            client.defaultTimeout = TIMEOUT_MS
            client.connect(config.host, config.port)

            val replyCode = client.replyCode
            if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(replyCode)) {
                return@withContext "接続拒否 (コード: $replyCode)"
            }

            client.execPBSZ(0)
            client.execPROT("P")

            if (!client.login(config.username, config.password)) {
                return@withContext "認証失敗: ユーザー名またはパスワードが違います"
            }

            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)

            // リモートディレクトリの存在確認
            if (config.remotePath.isNotEmpty() && config.remotePath != "/") {
                val exists = client.changeWorkingDirectory(config.remotePath)
                if (!exists) {
                    return@withContext "リモートパスが見つかりません: ${config.remotePath}"
                }
            }

            Log.d(TAG, "FTPS connection test successful: ${config.host}:${config.port}")
            null // 成功
        } catch (e: Exception) {
            Log.e(TAG, "FTPS connection test failed", e)
            "接続エラー: ${e.message}"
        } finally {
            runCatching {
                if (client.isConnected) {
                    client.logout()
                    client.disconnect()
                }
            }
        }
    }

    /**
     * ファイルをFTPSサーバーにアップロードする
     * @return アップロード先のパス (成功時)、null (失敗時)
     */
    suspend fun uploadFile(file: File, config: FtpsConfig): String? = withContext(Dispatchers.IO) {
        val client = FTPSClient("TLS", false)
        try {
            client.connectTimeout = TIMEOUT_MS
            client.defaultTimeout = TIMEOUT_MS
            client.connect(config.host, config.port)

            if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(client.replyCode)) {
                Log.e(TAG, "Connection refused: ${client.replyCode}")
                return@withContext null
            }

            client.execPBSZ(0)
            client.execPROT("P")

            if (!client.login(config.username, config.password)) {
                Log.e(TAG, "FTPS login failed")
                return@withContext null
            }

            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)

            // リモートディレクトリに移動または作成
            val remotePath = config.remotePath.ifEmpty { "/" }
            ensureRemoteDir(client, remotePath)
            client.changeWorkingDirectory(remotePath)

            // アップロード
            FileInputStream(file).use { inputStream ->
                val success = client.storeFile(file.name, inputStream)
                if (!success) {
                    Log.e(TAG, "FTPS storeFile failed: ${client.replyString}")
                    return@withContext null
                }
            }

            val uploadedPath = "$remotePath/${file.name}"
            Log.i(TAG, "FTPS upload success: $uploadedPath")
            uploadedPath

        } catch (e: Exception) {
            Log.e(TAG, "FTPS upload error", e)
            null
        } finally {
            runCatching {
                if (client.isConnected) {
                    client.logout()
                    client.disconnect()
                }
            }
        }
    }

    /** リモートディレクトリを再帰的に作成 */
    private fun ensureRemoteDir(client: FTPSClient, path: String) {
        val parts = path.split("/").filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current += "/$part"
            client.makeDirectory(current) // 既存でも失敗しないので気にしない
        }
    }
}

/** FTPS接続設定 */
data class FtpsConfig(
    val host: String,
    val port: Int = FtpsRepository.DEFAULT_PORT,
    val username: String,
    val password: String,
    val remotePath: String = "/recordings"
) {
    fun isValid() = host.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}
