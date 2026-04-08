package com.example.callmemorecorder.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.core.content.ContextCompat
import com.example.callmemorecorder.ui.settings.SettingsViewModel

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    var agreed = remember { mutableStateOf(false) }

    fun checkPermission(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    var micGranted by remember {
        mutableStateOf(checkPermission(Manifest.permission.RECORD_AUDIO))
    }
    var contactsGranted by remember {
        mutableStateOf(checkPermission(Manifest.permission.READ_CONTACTS))
    }
    var phoneGranted by remember {
        mutableStateOf(checkPermission(Manifest.permission.READ_PHONE_STATE))
    }
    // 通知権限: API 33+ のみ必要、それ未満は常に true
    var notifGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                checkPermission(Manifest.permission.POST_NOTIFICATIONS)
            else true
        )
    }

    // ── ランチャーはすべてトップレベルで宣言（Compose の規則） ──────────────
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> contactsGranted = granted }

    val phoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> phoneGranted = granted }

    // POST_NOTIFICATIONS ランチャーは API に関わらずトップレベルで宣言する。
    // API 33 未満では launch() しないので無害。
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    // 必須権限（マイク + 電話状態）がすべて許可されているか
    val canProceed = agreed.value && micGranted && phoneGranted

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
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                    title = "マイク権限",
                    description = "音声録音に必要です",
                    isGranted = micGranted,
                    isRequired = true,
                    onRequest = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )

                // 電話状態権限（必須）
                PermissionRow(
                    title = "電話状態の読み取り",
                    description = "通話の開始・終了を検知して自動録音するために必要です",
                    isGranted = phoneGranted,
                    isRequired = true,
                    onRequest = { phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE) }
                )

                // 連絡先権限
                PermissionRow(
                    title = "連絡先へのアクセス",
                    description = "録音履歴に通話相手の名前を表示するために使用します",
                    isGranted = contactsGranted,
                    isRequired = false,
                    onRequest = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) }
                )

                // 通知権限（Android 13+ のみ表示）
                // ランチャーはトップレベルで宣言済み。API 33 未満では launch() しない。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow(
                        title = "通知権限",
                        description = "録音中・通話監視中の通知表示に必要です",
                        isGranted = notifGranted,
                        isRequired = false,
                        onRequest = {
                            // API 33+ のみここに到達するので安全
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )
                }
            }
        }

        // ── Drive は後で設定 ──────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Google Drive 連携・FTPS 設定はセットアップ完了後、歯車アイコンから設定できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

        // 必須権限が未許可の場合の警告
        if (!micGranted || !phoneGranted) {
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
                        buildString {
                            if (!micGranted) append("マイク権限")
                            if (!micGranted && !phoneGranted) append("・")
                            if (!phoneGranted) append("電話状態の読み取り権限")
                            append("が必要です。「権限の確認」から許可してください。")
                        },
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
                if (isRequired && !isGranted) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "★必須",
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
            Button(
                onClick = onRequest,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("許可する", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
