package com.example.callmemorecorder.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneCallback
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.callmemorecorder.service.RecordingState
import com.example.callmemorecorder.util.formatDuration
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val micPermission = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO)

    // 設定画面から戻った時に Drive/Upload ステータスを更新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        viewModel.bindService()
        onDispose { viewModel.unbindService() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call Memo Recorder") },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Filled.List, contentDescription = "History")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // アップロード設定 / Drive 状態カード
            UploadStatusCard(
                uploadType = uiState.uploadType,
                isDriveConnected = uiState.isDriveConnected,
                autoRecordCall = uiState.autoRecordCall,
                autoUpload = uiState.autoUpload
            )

            Spacer(modifier = Modifier.weight(1f))
            RecordingStatusDisplay(
                recordingState = uiState.recordingState,
                elapsedTimeMs = uiState.elapsedTimeMs
            )
            Spacer(modifier = Modifier.height(32.dp))
            val isRecording = uiState.recordingState is RecordingState.Recording
            RecordButton(
                isRecording = isRecording,
                onClick = {
                    if (!micPermission.status.isGranted) {
                        micPermission.launchPermissionRequest()
                        return@RecordButton
                    }
                    if (isRecording) viewModel.stopRecording() else viewModel.startRecording()
                }
            )
            Spacer(modifier = Modifier.weight(1f))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "録音はあなたの声のみを対象とします。通話相手の音声は録音されません。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    uiState.errorMessage?.let {
        LaunchedEffect(it) { viewModel.clearError() }
    }
}

@Composable
private fun UploadStatusCard(
    uploadType: String,        // "drive" / "ftps" / "none"
    isDriveConnected: Boolean,
    autoRecordCall: Boolean,
    autoUpload: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                autoRecordCall -> MaterialTheme.colorScheme.secondaryContainer
                else           -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {

            // 通話自動録音 状態
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.PhoneCallback,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (autoRecordCall) MaterialTheme.colorScheme.secondary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (autoRecordCall) "通話自動録音: 有効" else "通話自動録音: 無効",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (autoRecordCall) FontWeight.Bold else FontWeight.Normal
                )
            }

            // アップロード先状態
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    !autoUpload -> {
                        Icon(Icons.Filled.Info, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text("自動アップロード: 無効",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    uploadType == "drive" && isDriveConnected -> {
                        Icon(Icons.Filled.Cloud, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Google Drive: 接続済み・自動アップロード有効",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold)
                    }
                    uploadType == "drive" && !isDriveConnected -> {
                        Icon(Icons.Filled.Warning, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Google Drive: 未接続（設定画面でサインインしてください）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    uploadType == "ftps" -> {
                        Icon(Icons.Filled.Cloud, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text("FTPSサーバー: 自動アップロード有効",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold)
                    }
                    else -> {
                        Icon(Icons.Filled.Warning, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text("アップロード先が未設定（設定画面で設定してください）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingStatusDisplay(recordingState: RecordingState, elapsedTimeMs: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (recordingState) {
            is RecordingState.Idle -> {
                Icon(Icons.Filled.Mic, contentDescription = "Ready", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("録音準備完了", style = MaterialTheme.typography.titleMedium)
            }
            is RecordingState.Recording -> {
                Icon(Icons.Filled.Mic, contentDescription = "Recording", modifier = Modifier.size(64.dp), tint = Color.Red)
                Spacer(Modifier.height(8.dp))
                Text("録音中", style = MaterialTheme.typography.titleMedium, color = Color.Red, fontWeight = FontWeight.Bold)
                Text(formatDuration(elapsedTimeMs), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            is RecordingState.Error -> {
                Icon(Icons.Filled.Warning, contentDescription = "Error", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text("エラー: ${recordingState.message}", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(96.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary
        )
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
            modifier = Modifier.size(40.dp)
        )
    }
}
