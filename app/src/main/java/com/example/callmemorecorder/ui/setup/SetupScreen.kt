package com.example.callmemorecorder.ui.setup

import android.Manifest
import android.app.Activity
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.callmemorecorder.ui.settings.SettingsViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    var agreed = remember { mutableStateOf(false) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // 各権限の状態
    val micPermission = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO)
    val contactsPermission = rememberPermissionState(android.Manifest.permission.READ_CONTACTS)
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    } else null

    // Google サインイン ランチャー
    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                task.getResult(ApiException::class.java)
                viewModel.onGoogleSignInSuccess()
                Toast.makeText(context, "Google アカウントに接続しました", Toast.LENGTH_SHORT).show()
            } catch (e: ApiException) {
                Toast.makeText(context, "サインイン失敗: ${e.statusCode}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 必須権限（マイク）が許可されているか
    val canProceed = agreed.value && micPermission.status.isGranted

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── タイトル ──────────────────────────────────────
        Text(
            text = "Call Memo Recorder",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "初回セットアップ",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        // ── アプリ説明 ────────────────────────────────────
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
                HorizontalDivider()
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

        // ── 権限の確認 ────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("権限の確認", fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "以下の権限を許可してください。許可しない機能は後から設定できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // マイク権限（必須）
                PermissionRow(
                    title = "マイク権限 ★必須",
                    description = "音声録音に必要です",
                    isGranted = micPermission.status.isGranted,
                    isRequired = true,
                    onRequest = { micPermission.launchPermissionRequest() }
                )

                // 連絡先権限
                PermissionRow(
                    title = "連絡先へのアクセス",
                    description = "録音履歴に通話相手の名前を表示するために使用します",
                    isGranted = contactsPermission.status.isGranted,
                    isRequired = false,
                    onRequest = { contactsPermission.launchPermissionRequest() }
                )

                // 通知権限（Android 13+）
                if (notificationPermission != null) {
                    PermissionRow(
                        title = "通知権限",
                        description = "録音中・通話監視中の通知表示に必要です",
                        isGranted = notificationPermission.status.isGranted,
                        isRequired = false,
                        onRequest = { notificationPermission.launchPermissionRequest() }
                    )
                }
            }
        }

        // ── Google Drive 連携 ──────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (settings.isDriveSignedIn)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Cloud,
                        contentDescription = null,
                        tint = if (settings.isDriveSignedIn)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Google Drive 連携", fontWeight = FontWeight.Bold)
                    if (settings.isDriveSignedIn) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "接続済み",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (settings.isDriveSignedIn) {
                    Text(
                        text = "✅ 接続済み: ${settings.driveEmail ?: ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "録音ファイルを自動的に Google Drive にアップロードできます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    OutlinedButton(
                        onClick = { viewModel.signOutGoogle() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("アカウントを切断")
                    }
                } else {
                    Text(
                        text = "Google Drive に接続すると、録音ファイルを自動的にアップロードできます。\nスキップして後で設定することもできます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { signInLauncher.launch(viewModel.getGoogleSignInIntent()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Google アカウントで接続")
                    }
                }
            }
        }

        // ── 同意チェックボックス ──────────────────────────
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

        // マイク権限が未許可の場合の警告
        if (!micPermission.status.isGranted) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "マイク権限が必要です。「権限の確認」から「マイク権限 ★必須」を許可してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // ── 開始ボタン ────────────────────────────────────
        Button(
            onClick = {
                viewModel.setSetupCompleted(true)
                onSetupComplete()
            },
            enabled = canProceed,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("セットアップ完了 - アプリを開始")
        }

        // スキップボタン
        TextButton(
            onClick = {
                viewModel.setSetupCompleted(true)
                onSetupComplete()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("スキップして続行（後で設定できます）")
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    isRequired: Boolean,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Medium)
                if (!isGranted && isRequired) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "（必須）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isGranted) {
            Icon(
                Icons.Filled.CheckCircle, contentDescription = "許可済み",
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            TextButton(onClick = onRequest) { Text("許可する") }
        }
    }
}
