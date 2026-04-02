package com.example.callmemorecorder.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.callmemorecorder.BuildConfig
import com.example.callmemorecorder.data.AppContainer
import com.example.callmemorecorder.data.repository.DriveRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val dataStore: DataStore<Preferences>,
    private val driveRepository: DriveRepository
) : ViewModel() {

    companion object {
        val KEY_AUTO_UPLOAD = booleanPreferencesKey("auto_upload")
        val KEY_AUTO_TRANSCRIBE = booleanPreferencesKey("auto_transcribe")
        val KEY_DELETE_AFTER_UPLOAD = booleanPreferencesKey("delete_after_upload")
        val KEY_EXPERIMENTAL_FEATURES = booleanPreferencesKey("experimental_features")
        val KEY_SETUP_COMPLETED = booleanPreferencesKey("setup_completed")

        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(container.dataStore, container.driveRepository) as T
        }
    }

    val settings: StateFlow<SettingsState> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            SettingsState(
                autoUpload = prefs[KEY_AUTO_UPLOAD] ?: false,
                autoTranscribe = prefs[KEY_AUTO_TRANSCRIBE] ?: false,
                deleteAfterUpload = prefs[KEY_DELETE_AFTER_UPLOAD] ?: false,
                experimentalFeatures = prefs[KEY_EXPERIMENTAL_FEATURES] ?: false,
                isDriveConnected = driveRepository.isSignedIn(),
                isDriveEnabled = BuildConfig.DRIVE_ENABLED,
                isTranscriptionEnabled = BuildConfig.TRANSCRIPTION_ENABLED,
                backendBaseUrl = BuildConfig.BACKEND_BASE_URL,
                driveFolderName = BuildConfig.DRIVE_FOLDER_NAME
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsState()
        )

    fun setAutoUpload(enabled: Boolean) = viewModelScope.launch {
        dataStore.edit { it[KEY_AUTO_UPLOAD] = enabled }
    }

    fun setAutoTranscribe(enabled: Boolean) = viewModelScope.launch {
        dataStore.edit { it[KEY_AUTO_TRANSCRIBE] = enabled }
    }

    fun setDeleteAfterUpload(enabled: Boolean) = viewModelScope.launch {
        dataStore.edit { it[KEY_DELETE_AFTER_UPLOAD] = enabled }
    }

    fun setExperimentalFeatures(enabled: Boolean) = viewModelScope.launch {
        dataStore.edit { it[KEY_EXPERIMENTAL_FEATURES] = enabled }
    }

    fun setSetupCompleted(completed: Boolean) = viewModelScope.launch {
        dataStore.edit { it[KEY_SETUP_COMPLETED] = completed }
    }
}

data class SettingsState(
    val autoUpload: Boolean = false,
    val autoTranscribe: Boolean = false,
    val deleteAfterUpload: Boolean = false,
    val experimentalFeatures: Boolean = false,
    val isDriveConnected: Boolean = false,
    val isDriveEnabled: Boolean = false,
    val isTranscriptionEnabled: Boolean = false,
    val backendBaseUrl: String = "",
    val driveFolderName: String = "CallMemoRecorder"
)
