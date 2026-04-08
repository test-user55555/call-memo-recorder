package com.example.callmemorecorder.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.callmemorecorder.data.AppContainer
import com.example.callmemorecorder.data.repository.DriveRepository
import com.example.callmemorecorder.data.repository.FtpsConfig
import com.example.callmemorecorder.data.repository.FtpsRepository
import com.example.callmemorecorder.service.CallMonitorService
import com.example.callmemorecorder.worker.AutoDeleteWorker
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    private val driveRepository: DriveRepository,
    private val ftpsRepository: FtpsRepository,
    private val context: Context
) : ViewModel() {

    companion object {
        val KEY_AUTO_UPLOAD          = booleanPreferencesKey("auto_upload")
        val KEY_UPLOAD_TYPE          = stringPreferencesKey("upload_type")
        val KEY_AUTO_TRANSCRIBE      = booleanPreferencesKey("auto_transcribe")
        val KEY_DELETE_AFTER_UPLOAD  = booleanPreferencesKey("delete_after_upload")
        val KEY_EXPERIMENTAL         = booleanPreferencesKey("experimental_features")
        val KEY_SETUP_COMPLETED      = booleanPreferencesKey("setup_completed")
        val KEY_AUTO_RECORD_CALL     = booleanPreferencesKey("auto_record_call")
        val KEY_AUTO_START_ON_BOOT   = booleanPreferencesKey("auto_start_on_boot")
        val KEY_DRIVE_FOLDER_NAME    = stringPreferencesKey("drive_folder_name")
        val KEY_AUDIO_SOURCE         = stringPreferencesKey("audio_source")
        val KEY_FTPS_HOST            = stringPreferencesKey("ftps_host")
        val KEY_FTPS_PORT            = intPreferencesKey("ftps_port")
        val KEY_FTPS_USERNAME        = stringPreferencesKey("ftps_username")
        val KEY_FTPS_PASSWORD        = stringPreferencesKey("ftps_password")
        val KEY_FTPS_PATH            = stringPreferencesKey("ftps_path")
        val KEY_AUTO_DELETE_ENABLED  = booleanPreferencesKey("auto_delete_enabled")
        val KEY_AUTO_DELETE_DAYS     = intPreferencesKey("auto_delete_days")

        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(
                    container.dataStore,
                    container.driveRepository,
                    container.ftpsRepository,
                    container.context
                ) as T
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Drive サインイン状態
    //   - settings StateFlow とは独立した MutableStateFlow
    //   - init ブロックで trySilentSignIn() を呼んで最新化する
    // ─────────────────────────────────────────────────────────────────────
    private val _driveSignedIn = MutableStateFlow(false)
    private val _driveEmail    = MutableStateFlow<String?>(null)

    /** UI が直接 collect するサインイン状態 */
    val driveSignedIn: StateFlow<Boolean>  = _driveSignedIn.asStateFlow()
    val driveEmail:    StateFlow<String?>  = _driveEmail.asStateFlow()

    init {
        // ViewModel 生成時に即座に同期チェック
        val syncResult = driveRepository.isSignedIn()
        _driveSignedIn.value = syncResult
        _driveEmail.value    = driveRepository.getSignedInEmail()

        // さらに silentSignIn で非同期に最新状態を取得
        viewModelScope.launch {
            val account = driveRepository.trySilentSignIn()
            if (account != null) {
                _driveSignedIn.value = true
                _driveEmail.value    = account.email
            }
            // silentSignIn が失敗しても isSignedIn() が true なら状態を維持
        }
    }

    // DataStore 由来の設定値
    val settings: StateFlow<SettingsState> =
        dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs ->
                SettingsState(
                    audioSource          = prefs[KEY_AUDIO_SOURCE]           ?: "VOICE_COMMUNICATION",
                    autoRecordCall       = prefs[KEY_AUTO_RECORD_CALL]       ?: false,
                    autoStartOnBoot      = prefs[KEY_AUTO_START_ON_BOOT]     ?: true,
                    autoUpload           = prefs[KEY_AUTO_UPLOAD]            ?: false,
                    uploadType           = prefs[KEY_UPLOAD_TYPE]            ?: "none",
                    autoTranscribe       = prefs[KEY_AUTO_TRANSCRIBE]        ?: false,
                    deleteAfterUpload    = prefs[KEY_DELETE_AFTER_UPLOAD]    ?: false,
                    experimentalFeatures = prefs[KEY_EXPERIMENTAL]           ?: false,
                    driveFolderName      = prefs[KEY_DRIVE_FOLDER_NAME]      ?: "CallMemoRecorder",
                    ftpsHost             = prefs[KEY_FTPS_HOST]              ?: "",
                    ftpsPort             = prefs[KEY_FTPS_PORT]              ?: 21,
                    ftpsUsername         = prefs[KEY_FTPS_USERNAME]          ?: "",
                    ftpsPassword         = prefs[KEY_FTPS_PASSWORD]          ?: "",
                    ftpsPath             = prefs[KEY_FTPS_PATH]              ?: "/recordings",
                    autoDeleteEnabled    = prefs[KEY_AUTO_DELETE_ENABLED]    ?: false,
                    autoDeleteDays       = prefs[KEY_AUTO_DELETE_DAYS]       ?: 30,
                )
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    // テスト結果
    private val _ftpsTestResult  = MutableStateFlow<String?>(null)
    val ftpsTestResult: StateFlow<String?> = _ftpsTestResult.asStateFlow()

    private val _driveTestResult = MutableStateFlow<String?>(null)
    val driveTestResult: StateFlow<String?> = _driveTestResult.asStateFlow()

    // ── Google Sign-In ────────────────────────────────────────────────────

    fun getGoogleSignInIntent(): Intent = driveRepository.getSignInIntent()

    /**
     * サインインランチャーの結果を受けて呼ぶ。
     * account が null でも email だけあれば "接続済み" とみなす。
     */
    fun onGoogleSignInSuccess(account: GoogleSignInAccount?, email: String?) {
        if (account != null) {
            driveRepository.cacheSignedInAccount(account)
            _driveSignedIn.value = true
            _driveEmail.value    = account.email ?: email
        } else if (email != null) {
            // account は取れなかったが email がある → 部分成功
            _driveSignedIn.value = true
            _driveEmail.value    = email
        }
        // どちらもなければ何もしない（サインイン失敗）
    }

    fun signOutGoogle() = viewModelScope.launch {
        driveRepository.signOut()
        _driveSignedIn.value = false
        _driveEmail.value    = null
    }

    /**
     * 画面復帰時（ON_RESUME）に Drive サインイン状態を再評価する。
     * trySilentSignIn() で最新のアカウント情報を取得する。
     */
    fun refreshDriveSignInState() {
        // まず同期チェックで即座に反映
        val signedIn = driveRepository.isSignedIn()
        _driveSignedIn.value = signedIn
        _driveEmail.value    = driveRepository.getSignedInEmail()

        // silentSignIn で非同期にアカウントを最新化
        viewModelScope.launch {
            val account = driveRepository.trySilentSignIn()
            if (account != null) {
                _driveSignedIn.value = true
                _driveEmail.value    = account.email
            }
        }
    }

    /** Drive 接続テスト（progressCallback で各ステップの進捗を UI に通知） */
    fun testDriveConnection(folderName: String) {
        viewModelScope.launch {
            _driveTestResult.value = "⏳ テスト開始..."
            val error = driveRepository.testConnection(folderName) { stepMsg ->
                // IO スレッドから呼ばれるので Main に切り替えて StateFlow を更新
                viewModelScope.launch(Dispatchers.Main) {
                    _driveTestResult.value = stepMsg
                }
            }
            // 最終結果を上書き
            _driveTestResult.value = if (error == null)
                "✅ 接続成功！「$folderName」フォルダに「接続テスト.txt」を作成しました"
            else
                error // DriveRepository 側ですでに ❌ プレフィックスを付けている
        }
    }

    fun clearDriveTestResult() { _driveTestResult.value = null }

    // ── 設定保存 ──────────────────────────────────────────────────────────

    fun setAutoStartOnBoot(v: Boolean)    = save { it[KEY_AUTO_START_ON_BOOT] = v }

    fun setAutoRecordCall(v: Boolean) {
        save { it[KEY_AUTO_RECORD_CALL] = v }
        if (v) {
            val intent = CallMonitorService.startIntent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } else {
            context.startService(CallMonitorService.stopIntent(context))
        }
    }

    fun setAutoUpload(v: Boolean)         = save { it[KEY_AUTO_UPLOAD] = v }
    fun setUploadType(v: String)          = save { it[KEY_UPLOAD_TYPE] = v }
    fun setAutoTranscribe(v: Boolean)     = save { it[KEY_AUTO_TRANSCRIBE] = v }
    fun setDeleteAfterUpload(v: Boolean)  = save { it[KEY_DELETE_AFTER_UPLOAD] = v }
    fun setExperimentalFeatures(v: Boolean) = save { it[KEY_EXPERIMENTAL] = v }
    fun setSetupCompleted(v: Boolean)     = save { it[KEY_SETUP_COMPLETED] = v }
    fun setDriveFolderName(v: String)     = save { it[KEY_DRIVE_FOLDER_NAME] = v }
    fun setAudioSource(v: String)         = save { it[KEY_AUDIO_SOURCE] = v }
    fun setFtpsHost(v: String)            = save { it[KEY_FTPS_HOST] = v }
    fun setFtpsPort(v: Int)               = save { it[KEY_FTPS_PORT] = v }
    fun setFtpsUsername(v: String)        = save { it[KEY_FTPS_USERNAME] = v }
    fun setFtpsPassword(v: String)        = save { it[KEY_FTPS_PASSWORD] = v }
    fun setFtpsPath(v: String)            = save { it[KEY_FTPS_PATH] = v }

    fun setAutoDeleteEnabled(v: Boolean) {
        save { it[KEY_AUTO_DELETE_ENABLED] = v }
        AutoDeleteWorker.reschedule(context, v)
    }

    fun setAutoDeleteDays(v: Int) = save { it[KEY_AUTO_DELETE_DAYS] = v }

    fun testFtpsConnection(host: String, port: Int, user: String, pass: String, path: String) {
        viewModelScope.launch {
            _ftpsTestResult.value = "テスト中..."
            val config = FtpsConfig(host, port, user, pass, path)
            val result = ftpsRepository.testConnection(config)
            _ftpsTestResult.value = if (result == null) "✅ 接続成功！" else "❌ $result"
        }
    }

    fun clearFtpsTestResult() { _ftpsTestResult.value = null }

    private fun save(block: (MutablePreferences) -> Unit) = viewModelScope.launch {
        dataStore.edit(block)
    }
}

data class SettingsState(
    val audioSource:          String  = "VOICE_COMMUNICATION",
    val autoStartOnBoot:      Boolean = true,
    val autoRecordCall:       Boolean = false,
    val autoUpload:           Boolean = false,
    val uploadType:           String  = "none",
    val autoTranscribe:       Boolean = false,
    val deleteAfterUpload:    Boolean = false,
    val experimentalFeatures: Boolean = false,
    val driveFolderName:      String  = "CallMemoRecorder",
    val ftpsHost:             String  = "",
    val ftpsPort:             Int     = 21,
    val ftpsUsername:         String  = "",
    val ftpsPassword:         String  = "",
    val ftpsPath:             String  = "/recordings",
    val autoDeleteEnabled:    Boolean = false,
    val autoDeleteDays:       Int     = 30,
)
