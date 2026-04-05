package com.example.callmemorecorder.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Mic
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.callmemorecorder.service.CallRecordingService
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
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            DriveStatusCard(isDriveConnected = uiState.isDriveConnected)
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
private fun DriveStatusCard(isDriveConnected: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDriveConnected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isDriveConnected) Icons.Filled.Cloud else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (isDriveConnected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isDriveConnected) "Google Drive: 接続済み" else "Google Drive: 未設定",
                style = MaterialTheme.typography.bodyMedium
            )
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
