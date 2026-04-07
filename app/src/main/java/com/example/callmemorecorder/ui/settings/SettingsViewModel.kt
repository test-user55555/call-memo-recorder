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
        // 共通
        val KEY_AUTO_UPLOAD          = booleanPreferencesKey("auto_upload")
        val KEY_UPLOAD_TYPE          = stringPreferencesKey("upload_type")       // "drive" / "ftps" / "none"
        val KEY_AUTO_TRANSCRIBE      = booleanPreferencesKey("auto_transcribe")
        val KEY_DELETE_AFTER_UPLOAD  = booleanPreferencesKey("delete_after_upload")
        val KEY_EXPERIMENTAL         = booleanPreferencesKey("experimental_features")
        val KEY_SETUP_COMPLETED      = booleanPreferencesKey("setup_completed")
        val KEY_AUTO_RECORD_CALL     = booleanPreferencesKey("auto_record_call")
        val KEY_AUTO_START_ON_BOOT    = booleanPreferencesKey("auto_start_on_boot")

        // Drive
        val KEY_DRIVE_FOLDER_NAME    = stringPreferencesKey("drive_folder_name")

        // 録音ソース
        val KEY_AUDIO_SOURCE         = stringPreferencesKey("audio_source")  // "VOICE_COMMUNICATION" / "MIC" / "VOICE_DOWNLINK"

        // FTPS
        val KEY_FTPS_HOST            = stringPreferencesKey("ftps_host")
        val KEY_FTPS_PORT            = intPreferencesKey("ftps_port")
        val KEY_FTPS_USERNAME        = stringPreferencesKey("ftps_username")
        val KEY_FTPS_PASSWORD        = stringPreferencesKey("ftps_password")
        val KEY_FTPS_PATH            = stringPreferencesKey("ftps_path")

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

    // Drive サインイン状態を保持する MutableStateFlow（refreshDriveSignInState() で更新）
    private val _driveSignedIn = MutableStateFlow(driveRepository.isSignedIn())
    private val _driveEmail = MutableStateFlow(driveRepository.getSignedInEmail())

    // 設定値のFlow
    val settings: StateFlow<SettingsState> = combine(
        dataStore.data.catch { emit(emptyPreferences()) },
        _driveSignedIn,
        _driveEmail
    ) { prefs, signedIn, email ->
        SettingsState(
            audioSource       = prefs[KEY_AUDIO_SOURCE]            ?: "VOICE_COMMUNICATION",
            autoRecordCall    = prefs[KEY_AUTO_RECORD_CALL]        ?: false,
            autoStartOnBoot   = prefs[KEY_AUTO_START_ON_BOOT]      ?: true,
            autoUpload        = prefs[KEY_AUTO_UPLOAD]             ?: false,
            uploadType        = prefs[KEY_UPLOAD_TYPE]             ?: "none",
            autoTranscribe    = prefs[KEY_AUTO_TRANSCRIBE]         ?: false,
            deleteAfterUpload = prefs[KEY_DELETE_AFTER_UPLOAD]     ?: false,
            experimentalFeatures = prefs[KEY_EXPERIMENTAL]         ?: false,
            // Drive（MutableStateFlow から読み出す）
            isDriveSignedIn   = signedIn,
            driveEmail        = email,
            driveFolderName   = prefs[KEY_DRIVE_FOLDER_NAME]       ?: "CallMemoRecorder",
            // FTPS
            ftpsHost          = prefs[KEY_FTPS_HOST]               ?: "",
            ftpsPort          = prefs[KEY_FTPS_PORT]               ?: 21,
            ftpsUsername      = prefs[KEY_FTPS_USERNAME]           ?: "",
            ftpsPassword      = prefs[KEY_FTPS_PASSWORD]           ?: "",
            ftpsPath          = prefs[KEY_FTPS_PATH]               ?: "/recordings",
        )
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsState())

    // FTPS接続テスト結果
    private val _ftpsTestResult = MutableStateFlow<String?>(null)
    val ftpsTestResult: StateFlow<String?> = _ftpsTestResult.asStateFlow()

    // Drive接続テスト結果
    private val _driveTestResult = MutableStateFlow<String?>(null)
    val driveTestResult: StateFlow<String?> = _driveTestResult.asStateFlow()

    // Google Sign-Inのインテントを提供
    fun getGoogleSignInIntent(): Intent = driveRepository.getSignInIntent()

    // Google Sign-In 成功後の処理
    // account: SignInランチャーから直接受け取った GoogleSignInAccount
    fun onGoogleSignInSuccess(email: String?) {
        _driveSignedIn.value = true
        _driveEmail.value = email
    }

    // Google Sign-Out
    fun signOutGoogle() = viewModelScope.launch {
        driveRepository.signOut()
        kotlinx.coroutines.delay(500)
        _driveSignedIn.value = false
        _driveEmail.value = null
    }

    /**
     * 設定画面表示時 (onResume相当) に Drive サインイン状態を強制再評価。
     * GoogleSignIn.getLastSignedInAccount() は Main スレッドから呼ぶ必要あり。
     */
    fun refreshDriveSignInState() {
        viewModelScope.launch {
            val (signedIn, email) = withContext(Dispatchers.Main) {
                Pair(driveRepository.isSignedIn(), driveRepository.getSignedInEmail())
            }
            _driveSignedIn.value = signedIn
            _driveEmail.value = email
        }
    }

    /** Drive 接続テスト: 指定フォルダに "接続テスト.txt" をアップロード */
    fun testDriveConnection(folderName: String) {
        viewModelScope.launch {
            _driveTestResult.value = "テスト中..."
            val result = driveRepository.testConnection(folderName)
            _driveTestResult.value = if (result == null)
                "✅ 接続成功！「$folderName」フォルダに「接続テスト.txt」を作成しました"
            else
                "❌ $result"
        }
    }

    fun clearDriveTestResult() { _driveTestResult.value = null }

    // ── 設定保存メソッド群 ──────────────────────────────────

    fun setAutoStartOnBoot(v: Boolean) = save { it[KEY_AUTO_START_ON_BOOT] = v }

    fun setAutoRecordCall(v: Boolean) {
        save { it[KEY_AUTO_RECORD_CALL] = v }
        // 通話監視サービスをON/OFF連動
        if (v) {
            val intent = CallMonitorService.startIntent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            context.startService(CallMonitorService.stopIntent(context))
        }
    }
    fun setAutoUpload(v: Boolean)     = save { it[KEY_AUTO_UPLOAD] = v }
    fun setUploadType(v: String)      = save { it[KEY_UPLOAD_TYPE] = v }
    fun setAutoTranscribe(v: Boolean) = save { it[KEY_AUTO_TRANSCRIBE] = v }
    fun setDeleteAfterUpload(v: Boolean) = save { it[KEY_DELETE_AFTER_UPLOAD] = v }
    fun setExperimentalFeatures(v: Boolean) = save { it[KEY_EXPERIMENTAL] = v }
    fun setSetupCompleted(v: Boolean) = save { it[KEY_SETUP_COMPLETED] = v }

    // Drive設定
    fun setDriveFolderName(v: String) = save { it[KEY_DRIVE_FOLDER_NAME] = v }

    // 録音ソース設定
    fun setAudioSource(v: String) = save { it[KEY_AUDIO_SOURCE] = v }

    // FTPS設定
    fun setFtpsHost(v: String)     = save { it[KEY_FTPS_HOST] = v }
    fun setFtpsPort(v: Int)        = save { it[KEY_FTPS_PORT] = v }
    fun setFtpsUsername(v: String) = save { it[KEY_FTPS_USERNAME] = v }
    fun setFtpsPassword(v: String) = save { it[KEY_FTPS_PASSWORD] = v }
    fun setFtpsPath(v: String)     = save { it[KEY_FTPS_PATH] = v }

    /** FTPS接続テスト */
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
    val audioSource: String = "VOICE_COMMUNICATION",   // "VOICE_COMMUNICATION" / "MIC" / "VOICE_DOWNLINK" / "CAMCORDER" / "UNPROCESSED"
    val autoStartOnBoot: Boolean = true,
    val autoRecordCall: Boolean = false,
    val autoUpload: Boolean = false,
    val uploadType: String = "none",         // "drive" / "ftps" / "none"
    val autoTranscribe: Boolean = false,
    val deleteAfterUpload: Boolean = false,
    val experimentalFeatures: Boolean = false,
    // Drive
    val isDriveSignedIn: Boolean = false,
    val driveEmail: String? = null,
    val driveFolderName: String = "CallMemoRecorder",
    // FTPS
    val ftpsHost: String = "",
    val ftpsPort: Int = 21,
    val ftpsUsername: String = "",
    val ftpsPassword: String = "",
    val ftpsPath: String = "/recordings",
)
