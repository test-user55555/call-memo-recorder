package com.example.callmemorecorder.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.callmemorecorder.ui.settings.SettingsViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SettingsViewModel
) {
    var agreed = remember { mutableStateOf(false) }

    val micPermission = rememberPermissionState(
        permission = android.Manifest.permission.RECORD_AUDIO
    )
    val notificationPermission = rememberPermissionState(
        permission = android.Manifest.permission.POST_NOTIFICATIONS
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Call Memo Recorder",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("録音について", fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "このアプリは通話前後または通話中に、あなた自身の声をマイクで録音します。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Divider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("重要", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    text = "標準録音は自分の声のみが対象です。通話相手の音声はOSの制約により通常は録音できません。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Permissions section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("権限の確認", fontWeight = FontWeight.Bold)

                PermissionRow(
                    title = "マイク権限",
                    description = "音声録音に必要です",
                    isGranted = micPermission.status.isGranted,
                    onRequest = { micPermission.launchPermissionRequest() }
                )

                PermissionRow(
                    title = "通知権限",
                    description = "録音中の通知表示に必要です",
                    isGranted = notificationPermission.status.isGranted,
                    onRequest = { notificationPermission.launchPermissionRequest() }
                )
            }
        }

        // Agreement
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = agreed.value,
                    onCheckedChange = { agreed.value = it }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "上記の内容を理解しました。自分の声の録音のみを目的として使用します。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Drive skip note
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Cloud, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Google Drive連携", fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "Google Drive連携は後でも設定できます。設定画面から連携してください。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                viewModel.setSetupCompleted(true)
                onSetupComplete()
            },
            enabled = agreed.value && micPermission.status.isGranted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("セットアップ完了 - アプリを開始")
        }

        TextButton(
            onClick = {
                viewModel.setSetupCompleted(true)
                onSetupComplete()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("スキップして続行")
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        if (isGranted) {
            Icon(Icons.Filled.Check, contentDescription = "Granted",
                tint = MaterialTheme.colorScheme.primary)
        } else {
            TextButton(onClick = onRequest) {
                Text("許可する")
            }
        }
    }
}
