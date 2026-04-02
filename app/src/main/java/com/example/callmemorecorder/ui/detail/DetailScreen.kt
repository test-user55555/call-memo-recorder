package com.example.callmemorecorder.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.callmemorecorder.domain.model.RecordItem
import com.example.callmemorecorder.domain.model.TranscriptionStatus
import com.example.callmemorecorder.domain.model.UploadStatus
import com.example.callmemorecorder.util.formatDatetime
import com.example.callmemorecorder.util.formatDuration

@Composable
fun DetailScreen(
    recordId: String,
    onNavigateBack: () -> Unit,
    viewModel: DetailViewModel
) {
    val record by viewModel.record.collectAsStateWithLifecycle()
    val uiMessage by viewModel.uiMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(recordId) {
        viewModel.loadRecord(recordId)
    }

    uiMessage?.let {
        LaunchedEffect(it) {
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("録音詳細") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        val currentRecord = record

        if (currentRecord == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Basic info
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(currentRecord.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        LabeledText("日時", formatDatetime(currentRecord.createdAt))
                        LabeledText("録音時間", formatDuration(currentRecord.durationMs))
                        LabeledText("ファイル形式", currentRecord.mimeType)
                        currentRecord.localPath?.let { LabeledText("保存先", it) }
                    }
                }

                // Upload status
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Google Drive", fontWeight = FontWeight.Bold)
                        LabeledText("アップロード状態", uploadStatusText(currentRecord.uploadStatus))
                        currentRecord.driveFileId?.let { LabeledText("Drive File ID", it) }
                        currentRecord.driveWebLink?.let { link ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Drive リンク: ", style = MaterialTheme.typography.bodyMedium)
                                TextButton(
                                    onClick = {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                                    }
                                ) {
                                    Text("開く")
                                }
                            }
                        }
                        Button(
                            onClick = { viewModel.reUpload() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("再アップロード")
                        }
                    }
                }

                // Transcription
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("文字起こし", fontWeight = FontWeight.Bold)
                        LabeledText("状態", transcriptionStatusText(currentRecord.transcriptionStatus))

                        if (currentRecord.transcriptText != null) {
                            Text("テキスト:", fontWeight = FontWeight.Medium)
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = currentRecord.transcriptText,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            Text(
                                text = when (currentRecord.transcriptionStatus) {
                                    TranscriptionStatus.NOT_STARTED -> "文字起こし未設定 (TRANSCRIPTION_ENABLED=false)"
                                    TranscriptionStatus.QUEUED -> "文字起こし待ち..."
                                    TranscriptionStatus.PROCESSING -> "文字起こし中..."
                                    else -> "テキストなし"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Button(
                            onClick = { viewModel.reTranscribe() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("再文字起こし")
                        }
                    }
                }

                // Error message
                currentRecord.errorMessage?.let { error ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(modifier = Modifier.padding(16.dp)) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }

                // Delete button
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("削除")
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("録音を削除") },
            text = { Text("この録音を削除しますか？この操作は取り消せません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteRecord { onNavigateBack() }
                    }
                ) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

@Composable
private fun LabeledText(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "$label: ",
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun uploadStatusText(status: UploadStatus) = when (status) {
    UploadStatus.NOT_STARTED -> "未アップロード"
    UploadStatus.QUEUED -> "アップロード待ち"
    UploadStatus.UPLOADING -> "アップロード中"
    UploadStatus.UPLOADED -> "保存済み"
    UploadStatus.ERROR -> "エラー"
}

private fun transcriptionStatusText(status: TranscriptionStatus) = when (status) {
    TranscriptionStatus.NOT_STARTED -> "未設定"
    TranscriptionStatus.QUEUED -> "待ち"
    TranscriptionStatus.PROCESSING -> "処理中"
    TranscriptionStatus.COMPLETED -> "完了"
    TranscriptionStatus.ERROR -> "エラー"
}
