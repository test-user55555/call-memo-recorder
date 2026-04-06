package com.example.callmemorecorder.ui.settings

import android.Manifest
import android.app.Activity
import android.os.Build
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
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
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
                task.getResult(ApiException::class.java)
                viewModel.onGoogleSignInSuccess()
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

                // 必要な権限がすべて付与されているか確認
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
                        "通話開始時に自動録音、終了時に自動停止します（通知バーに監視アイコンが表示されます）"
                    else
                        "権限を許可してから有効化してください",
                    checked = settings.autoRecordCall,
                    enabled = allGranted,
                    onCheckedChange = { viewModel.setAutoRecordCall(it) }
                )
                if (settings.autoRecordCall) {
                    InfoBox(
                        text = "⚠️ 自分の声のみ録音されます（OSの制約により通話相手の声は録音できません）",
                        isWarning = true
                    )
                    InfoBox(
                        text = "📌 通知バーに「通話を監視中」が表示されている間、着信を自動検知します",
                        isWarning = false
                    )
                }
            }

            // ── Google Drive 設定（常時表示） ────────────────────
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
                        }) {
                            Text("切断")
                        }
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

                    Spacer(Modifier.height(8.dp))

                    // ── Drive 接続テストボタン ──
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

                    // テスト結果表示
                    driveTestResult?.let { result ->
                        Spacer(Modifier.height(4.dp))
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
                            Text(
                                text = result,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
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
                    if (settings.uploadType == "drive" && !settings.isDriveSignedIn) {
                        Spacer(Modifier.height(4.dp))
                        InfoBox(
                            text = "⚠️ 「Google Drive 設定」でアカウントに接続してください",
                            isWarning = true
                        )
                    }
                }
            }

            // ── FTPS 設定 ─────────────────────────────────────
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
                InfoRow(label = "バージョン", value = "1.2.0")
                InfoRow(label = "ビルドタイプ", value = "DEBUG")
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
                    RadioButton(
                        selected = selected == key,
                        onClick = { onSelect(key) }
                    )
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

    // ローカルの編集用状態
    var host by remember(settings.ftpsHost) { mutableStateOf(settings.ftpsHost) }
    var port by remember(settings.ftpsPort) { mutableStateOf(settings.ftpsPort.toString()) }
    var user by remember(settings.ftpsUsername) { mutableStateOf(settings.ftpsUsername) }
    var pass by remember(settings.ftpsPassword) { mutableStateOf(settings.ftpsPassword) }
    var path by remember(settings.ftpsPath) { mutableStateOf(settings.ftpsPath) }

    LabeledTextField(
        label = "ホスト名 / IPアドレス",
        value = host,
        placeholder = "例: ftp.example.com",
        onValueChange = { host = it; viewModel.setFtpsHost(it) }
    )
    LabeledTextField(
        label = "ポート番号",
        value = port,
        placeholder = "21",
        keyboardType = KeyboardType.Number,
        onValueChange = {
            port = it
            it.toIntOrNull()?.let { p -> viewModel.setFtpsPort(p) }
        }
    )
    LabeledTextField(
        label = "ユーザー名",
        value = user,
        placeholder = "ftpuser",
        onValueChange = { user = it; viewModel.setFtpsUsername(it) }
    )

    // パスワードフィールド
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

    LabeledTextField(
        label = "アップロード先パス",
        value = path,
        placeholder = "例: /recordings",
        onValueChange = { path = it; viewModel.setFtpsPath(it) }
    )

    Spacer(Modifier.height(8.dp))

    // 接続テストボタン
    Button(
        onClick = {
            val p = port.toIntOrNull() ?: 21
            viewModel.testFtpsConnection(host, p, user, pass, path)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = host.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
    ) {
        Icon(Icons.Filled.NetworkCheck, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("接続テスト")
    }

    // テスト結果
    ftpsTestResult?.let { result ->
        Spacer(Modifier.height(4.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (result.startsWith("✅"))
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = result,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
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
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
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
            containerColor = if (isWarning) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                             else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (isWarning) Icons.Filled.Warning else Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
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
