package com.example.callmemorecorder.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.callmemorecorder.service.CallRecordingAccessibilityService
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ftpsTestResult by viewModel.ftpsTestResult.collectAsStateWithLifecycle()
    val driveTestResult by viewModel.driveTestResult.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 画面が前面に来るたびに Drive サインイン状態を更新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDriveSignInState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 通話自動録音に必要な権限リスト
    val callPermissions = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val callPermState = rememberMultiplePermissionsState(callPermissions)

    // Google Sign-In ランチャー
    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                // account オブジェクトと email を両方渡す
                // → DriveRepository に account をキャッシュし、getLastSignedInAccount() 遅延を完全回避
                viewModel.onGoogleSignInSuccess(account, account.email)
                Toast.makeText(context, "Google アカウントに接続しました", Toast.LENGTH_SHORT).show()
            } catch (e: ApiException) {
                Toast.makeText(context, "サインイン失敗: ${e.statusCode}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── 通話自動録音 ─────────────────────────────────
            SectionCard(title = "通話自動録音") {
                val allGranted = callPermState.allPermissionsGranted
                if (!allGranted) {
                    InfoBox(
                        text = "通話を自動録音するには「電話」「マイク」「連絡先」「通知」の権限が必要です。",
                        isWarning = true
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { callPermState.launchMultiplePermissionRequest() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("権限を許可する")
                    }
                }
                SwitchRow(
                    title = "通話を自動で録音する",
                    description = if (allGranted)
                        "通話開始時に自動録音、終了時に自動停止します"
                    else
                        "権限を許可してから有効化してください",
                    checked = settings.autoRecordCall,
                    enabled = allGranted,
                    onCheckedChange = { viewModel.setAutoRecordCall(it) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SwitchRow(
                    title = "OS起動時に自動起動",
                    description = if (settings.autoStartOnBoot)
                        "端末再起動後に通話監視サービスを自動で開始します"
                    else
                        "端末再起動後は手動で開始する必要があります",
                    checked = settings.autoStartOnBoot,
                    onCheckedChange = { viewModel.setAutoStartOnBoot(it) }
                )
                if (settings.autoRecordCall) {
                    InfoBox(
                        text = "⚠️ 端末により録音できる音声ソースが異なります（通話相手の音声は録音できない場合があります）",
                        isWarning = true
                    )
                    InfoBox(
                        text = "📌 通知バーに「通話を監視中」が表示されている間、着信を自動検知します",
                        isWarning = false
                    )
                }
            }

            // ── ユーザー補助サービス設定 ──────────────────────────
            SectionCard(title = "ユーザー補助サービス（通話録音強化）") {
                val a11yRunning = CallRecordingAccessibilityService.isServiceRunning
                if (a11yRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("ユーザー補助サービス: 有効", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    InfoBox(
                        text = "ユーザー補助サービスを有効にすると、通話中の音声録音精度が向上します。" +
                               "\n以下のボタンから「設定 → ユーザー補助 → Call Memo Recorder」をONにしてください。",
                        isWarning = false
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Accessibility, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("ユーザー補助サービスの設定を開く")
                }
            }

            // ── 録音ソース選択 ───────────────────────────────
            if (settings.autoRecordCall) {
                SectionCard(title = "録音ソース（試験用）") {
                    InfoBox(
                        text = "端末によって録音できるソースが異なります。録音できない場合は別のソースをお試しください。",
                        isWarning = false
                    )
                    Spacer(Modifier.height(8.dp))
                    AudioSourceSelector(
                        selected = settings.audioSource,
                        onSelect = { viewModel.setAudioSource(it) }
                    )
                }
            }

            // ── 自動アップロード設定 ──────────────────────────────
            SectionCard(title = "自動アップロード") {
                SwitchRow(
                    title = "録音後に自動アップロード",
                    description = "録音完了後、選択した宛先にアップロードします",
                    checked = settings.autoUpload,
                    onCheckedChange = { viewModel.setAutoUpload(it) }
                )
                if (settings.autoUpload) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "アップロード先",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    UploadTypeSelector(
                        selected = settings.uploadType,
                        onSelect = { viewModel.setUploadType(it) }
                    )
                }
            }

            // ── Google Drive 設定（autoUpload=true & uploadType="drive" の場合のみ表示） ──
            if (settings.autoUpload && settings.uploadType == "drive") {
                SectionCard(title = "Google Drive 設定") {
                    if (settings.isDriveSignedIn) {
                        // ── サインイン済み ──
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("接続済み", fontWeight = FontWeight.Bold)
                                Text(
                                    settings.driveEmail ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(onClick = {
                                viewModel.signOutGoogle()
                                viewModel.clearDriveTestResult()
                            }) { Text("切断") }
                        }

                        Spacer(Modifier.height(8.dp))
                        LabeledTextField(
                            label = "アップロード先フォルダ名",
                            value = settings.driveFolderName,
                            placeholder = "例: CallMemoRecorder",
                            onValueChange = { viewModel.setDriveFolderName(it) }
                        )
                        Text(
                            "Googleドライブのルートにこの名前のフォルダが作成されます",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))

                        // ── Drive設定枠内の接続テストボタン ──
                        Button(
                            onClick = {
                                viewModel.clearDriveTestResult()
                                viewModel.testDriveConnection(settings.driveFolderName)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.NetworkCheck, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("接続テスト（テストファイルをアップロード）")
                        }
                        driveTestResult?.let { result ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        result.startsWith("✅") -> MaterialTheme.colorScheme.primaryContainer
                                        result == "テスト中..." -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.errorContainer
                                    }
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (result == "テスト中...") {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(text = result, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                    } else {
                        // ── 未サインイン ──
                        InfoBox(
                            text = "Google アカウントでサインインすると、録音ファイルを自動的に Google Drive にアップロードできます。",
                            isWarning = false
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { signInLauncher.launch(viewModel.getGoogleSignInIntent()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.AccountCircle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Google アカウントで接続")
                        }

                        // ── 接続テストボタン（未サインイン時はグレーアウト表示）──
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = {
                                viewModel.clearDriveTestResult()
                                viewModel.testDriveConnection(settings.driveFolderName)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = false   // 未サインイン時は押せない
                        ) {
                            Icon(Icons.Filled.NetworkCheck, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("接続テスト（先にアカウント接続が必要です）")
                        }
                    }
                }
            }

            // ── FTPS 設定（ftps選択時のみ表示） ─────────────────
            if (settings.autoUpload && settings.uploadType == "ftps") {
                SectionCard(title = "FTPS 設定") {
                    FtpsSettingsForm(
                        settings = settings,
                        viewModel = viewModel,
                        ftpsTestResult = ftpsTestResult
                    )
                }
            }

            // ── ストレージ ────────────────────────────────────
            SectionCard(title = "ストレージ") {
                SwitchRow(
                    title = "アップロード後にローカルファイルを削除",
                    description = "Drive/FTPSへの保存後、端末の録音ファイルを削除します",
                    checked = settings.deleteAfterUpload,
                    onCheckedChange = { viewModel.setDeleteAfterUpload(it) }
                )
            }

            // ── アプリ情報 ────────────────────────────────────
            SectionCard(title = "アプリ情報") {
                InfoRow(label = "バージョン", value = "1.3.4")
                InfoRow(label = "ビルドタイプ", value = "DEBUG")
            }
        }
    }
}

// ── 録音ソースセレクター ────────────────────────────────────────
@Composable
private fun AudioSourceSelector(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        Triple("VOICE_COMMUNICATION", "VOICE_COMMUNICATION（推奨）", "通話音声に最適化。ほとんどの端末で動作"),
        Triple("MIC",                 "MIC（マイク直接録音）",         "マイクを直接録音。自分の声のみ"),
        Triple("CAMCORDER",           "CAMCORDER",                   "カメラ録音用ソース。一部端末で有効"),
        Triple("VOICE_RECOGNITION",   "VOICE_RECOGNITION",           "音声認識用ソース"),
        Triple("UNPROCESSED",         "UNPROCESSED（未加工）",         "Android 7.0以上。加工なしの生音声"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (key, label, desc) ->
            OutlinedCard(
                onClick = { onSelect(key) },
                modifier = Modifier.fillMaxWidth(),
                border = if (selected == key)
                    CardDefaults.outlinedCardBorder().copy(width = 2.dp)
                else CardDefaults.outlinedCardBorder()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == key, onClick = { onSelect(key) })
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(desc, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ── アップロード先セレクター ────────────────────────────────────
@Composable
private fun UploadTypeSelector(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        Triple("drive", "Google Drive", Icons.Filled.Cloud),
        Triple("ftps",  "FTPS サーバー", Icons.Filled.Storage),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (key, label, icon) ->
            OutlinedCard(
                onClick = { onSelect(key) },
                modifier = Modifier.fillMaxWidth(),
                border = if (selected == key)
                    CardDefaults.outlinedCardBorder().copy(width = 2.dp)
                else CardDefaults.outlinedCardBorder()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == key, onClick = { onSelect(key) })
                    Spacer(Modifier.width(8.dp))
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ── FTPS設定フォーム ────────────────────────────────────────────
@Composable
private fun FtpsSettingsForm(
    settings: SettingsState,
    viewModel: SettingsViewModel,
    ftpsTestResult: String?
) {
    var showPassword by remember { mutableStateOf(false) }
    var host by remember(settings.ftpsHost) { mutableStateOf(settings.ftpsHost) }
    var port by remember(settings.ftpsPort) { mutableStateOf(settings.ftpsPort.toString()) }
    var user by remember(settings.ftpsUsername) { mutableStateOf(settings.ftpsUsername) }
    var pass by remember(settings.ftpsPassword) { mutableStateOf(settings.ftpsPassword) }
    var path by remember(settings.ftpsPath) { mutableStateOf(settings.ftpsPath) }

    LabeledTextField("ホスト名 / IPアドレス", host, "例: ftp.example.com") {
        host = it; viewModel.setFtpsHost(it)
    }
    LabeledTextField("ポート番号", port, "21", KeyboardType.Number) {
        port = it; it.toIntOrNull()?.let { p -> viewModel.setFtpsPort(p) }
    }
    LabeledTextField("ユーザー名", user, "ftpuser") {
        user = it; viewModel.setFtpsUsername(it)
    }
    OutlinedTextField(
        value = pass,
        onValueChange = { pass = it; viewModel.setFtpsPassword(it) },
        label = { Text("パスワード") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (showPassword) "隠す" else "表示"
                )
            }
        }
    )
    LabeledTextField("アップロード先パス", path, "例: /recordings") {
        path = it; viewModel.setFtpsPath(it)
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { viewModel.testFtpsConnection(host, port.toIntOrNull() ?: 21, user, pass, path) },
        modifier = Modifier.fillMaxWidth(),
        enabled = host.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
    ) {
        Icon(Icons.Filled.NetworkCheck, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("接続テスト")
    }
    ftpsTestResult?.let { result ->
        Spacer(Modifier.height(4.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (result.startsWith("✅"))
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = result, modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ── 共通コンポーザブル ─────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String, description: String, checked: Boolean,
    enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun LabeledTextField(
    label: String, value: String, placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.outline) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
    )
}

@Composable
private fun InfoBox(text: String, isWarning: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isWarning)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (isWarning) Icons.Filled.Warning else Icons.Filled.Info,
                contentDescription = null, modifier = Modifier.size(16.dp),
                tint = if (isWarning) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontWeight = FontWeight.Medium)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
