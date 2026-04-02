package com.example.callmemorecorder.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.callmemorecorder.BuildConfig

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Google Drive section
            SettingsSection(title = "Google Drive") {
                StatusRow(
                    title = "接続状態",
                    value = if (settings.isDriveConnected) "接続済み" else "未接続",
                    isError = !settings.isDriveConnected
                )
                if (!settings.isDriveEnabled) {
                    InfoRow(
                        text = "Google Drive連携は未設定です。local.propertiesでDRIVE_ENABLED=trueに設定してください。",
                        isWarning = true
                    )
                }
                LabeledSwitchRow(
                    title = "自動アップロード",
                    description = "録音完了後に自動でアップロード",
                    checked = settings.autoUpload,
                    enabled = settings.isDriveEnabled,
                    onCheckedChange = { viewModel.setAutoUpload(it) }
                )
                StatusRow(
                    title = "保存フォルダ名",
                    value = settings.driveFolderName
                )
            }

            // Transcription section
            SettingsSection(title = "文字起こし") {
                if (!settings.isTranscriptionEnabled) {
                    InfoRow(
                        text = "文字起こしは未設定です。TRANSCRIPTION_ENABLED=trueおよびBACKEND_BASE_URLを設定してください。",
                        isWarning = true
                    )
                }
                LabeledSwitchRow(
                    title = "自動文字起こし",
                    description = "アップロード後に自動で文字起こし",
                    checked = settings.autoTranscribe,
                    enabled = settings.isTranscriptionEnabled,
                    onCheckedChange = { viewModel.setAutoTranscribe(it) }
                )
            }

            // Storage section
            SettingsSection(title = "ストレージ") {
                LabeledSwitchRow(
                    title = "アップロード後に削除",
                    description = "Drive保存後にローカルファイルを削除",
                    checked = settings.deleteAfterUpload,
                    onCheckedChange = { viewModel.setDeleteAfterUpload(it) }
                )
            }

            // Experimental features section
            SettingsSection(title = "実験的機能") {
                InfoRow(
                    text = "実験的機能は動作が不安定な場合があります。通話相手の音声録音はOSの制約により保証されません。",
                    isWarning = true
                )
                LabeledSwitchRow(
                    title = "実験的機能を有効化",
                    description = "双方向録音などの実験機能 (動作非保証)",
                    checked = settings.experimentalFeatures,
                    onCheckedChange = { viewModel.setExperimentalFeatures(it) }
                )
            }

            // App info section
            SettingsSection(title = "アプリ情報") {
                StatusRow(title = "バージョン", value = "1.0.0 (debug)")
                StatusRow(
                    title = "バックエンドURL",
                    value = if (BuildConfig.TRANSCRIPTION_ENABLED) settings.backendBaseUrl else "未設定"
                )
                StatusRow(
                    title = "ビルドタイプ",
                    value = if (BuildConfig.DEBUG) "DEBUG" else "RELEASE"
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun LabeledSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.outline
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun StatusRow(
    title: String,
    value: String,
    isError: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontWeight = FontWeight.Medium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun InfoRow(text: String, isWarning: Boolean = false) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isWarning)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                if (isWarning) Icons.Filled.Warning else Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isWarning) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
