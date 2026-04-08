package com.example.callmemorecorder.ui.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
    var callLogGranted by remember {
        mutableStateOf(checkPermission(Manifest.permission.READ_CALL_LOG))
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

    // 各権限が「一度拒否されたことがあるか」のフラグ
    // (shouldShowRationale が false かつ未付与 = 「二度と表示しない」を選択済み)
    // SetupScreen は Activity コンテキストを必要とするため、ここでは
    // ランチャーが refused を返した場合にフォールバックするシンプルな方式を採用する
    var notifDeniedPermanently    by remember { mutableStateOf(false) }
    var micDeniedPermanently       by remember { mutableStateOf(false) }
    var phoneDeniedPermanently     by remember { mutableStateOf(false) }
    var contactsDeniedPermanently  by remember { mutableStateOf(false) }
    var callLogDeniedPermanently   by remember { mutableStateOf(false) }

    // ── ランチャーはすべてトップレベルで宣言（Compose の規則） ──────────────
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        if (!granted) micDeniedPermanently = true
    }

    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        contactsGranted = granted
        if (!granted) contactsDeniedPermanently = true
    }

    val callLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        callLogGranted = granted
        if (!granted) callLogDeniedPermanently = true
    }

    val phoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        phoneGranted = granted
        if (!granted) phoneDeniedPermanently = true
    }

    // POST_NOTIFICATIONS ランチャーは API に関わらずトップレベルで宣言する。
    // API 33 未満では launch() しないので無害。
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifGranted = granted
        if (!granted) notifDeniedPermanently = true
    }

    // アプリ設定画面を開くランチャー（「二度と表示しない」選択後のフォールバック用）
    val appSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 設定画面から戻ったら権限を再チェック
        micGranted      = checkPermission(Manifest.permission.RECORD_AUDIO)
        phoneGranted    = checkPermission(Manifest.permission.READ_PHONE_STATE)
        contactsGranted = checkPermission(Manifest.permission.READ_CONTACTS)
        notifGranted    = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            checkPermission(Manifest.permission.POST_NOTIFICATIONS) else true
        callLogGranted  = checkPermission(Manifest.permission.READ_CALL_LOG)
        // 設定から戻ったらフラグをリセット（再試行できるようにする）
        if (micGranted)      micDeniedPermanently      = false
        if (phoneGranted)    phoneDeniedPermanently    = false
        if (contactsGranted) contactsDeniedPermanently = false
        if (notifGranted)    notifDeniedPermanently    = false
        if (callLogGranted)  callLogDeniedPermanently  = false
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appSettingsLauncher.launch(intent)
    }

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
                    isDeniedPermanently = micDeniedPermanently,
                    onRequest = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenSettings = ::openAppSettings
                )

                // 電話状態権限（必須）
                PermissionRow(
                    title = "電話状態の読み取り",
                    description = "通話の開始・終了を検知して自動録音するために必要です",
                    isGranted = phoneGranted,
                    isRequired = true,
                    isDeniedPermanently = phoneDeniedPermanently,
                    onRequest = { phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE) },
                    onOpenSettings = ::openAppSettings
                )

                // 連絡先権限
                PermissionRow(
                    title = "連絡先へのアクセス",
                    description = "録音履歴に通話相手の名前を表示するために使用します",
                    isGranted = contactsGranted,
                    isRequired = false,
                    isDeniedPermanently = contactsDeniedPermanently,
                    onRequest = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
                    onOpenSettings = ::openAppSettings
                )

                // 通話ログ権限
                PermissionRow(
                    title = "通話履歴へのアクセス",
                    description = "発着信の電話番号をファイル名・履歴に記録するために使用します",
                    isGranted = callLogGranted,
                    isRequired = false,
                    isDeniedPermanently = callLogDeniedPermanently,
                    onRequest = { callLogLauncher.launch(Manifest.permission.READ_CALL_LOG) },
                    onOpenSettings = ::openAppSettings
                )

                // 通知権限（Android 13+ のみ表示）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow(
                        title = "通知権限",
                        description = "録音中・通話監視中の通知表示に必要です",
                        isGranted = notifGranted,
                        isRequired = false,
                        isDeniedPermanently = notifDeniedPermanently,
                        onRequest = {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onOpenSettings = ::openAppSettings
                    )
                }
            }
        }

        // 「二度と表示しない」が選択された権限がある場合の案内
        val anyPermanentlyDenied = micDeniedPermanently || phoneDeniedPermanently ||
                contactsDeniedPermanently || notifDeniedPermanently || callLogDeniedPermanently
        if (anyPermanentlyDenied) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Settings, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "「設定を開く」ボタンから端末の設定画面で権限を直接許可してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
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
    isDeniedPermanently: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
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
            // 拒否後のヒント
            if (!isGranted && isDeniedPermanently) {
                Text(
                    "権限が拒否されています。「設定を開く」から許可してください。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            isGranted -> {
                Icon(
                    Icons.Filled.CheckCircle, contentDescription = "許可済み",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            isDeniedPermanently -> {
                // 「二度と表示しない」選択済み → 設定画面へ誘導
                OutlinedButton(
                    onClick = onOpenSettings,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("設定を開く", style = MaterialTheme.typography.labelMedium)
                }
            }
            else -> {
                Button(
                    onClick = onRequest,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("許可する", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
