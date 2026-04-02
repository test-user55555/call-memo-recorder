package com.example.callmemorecorder.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.callmemorecorder.domain.model.RecordItem
import com.example.callmemorecorder.domain.model.TranscriptionStatus
import com.example.callmemorecorder.domain.model.UploadStatus
import com.example.callmemorecorder.util.formatDatetime
import com.example.callmemorecorder.util.formatDuration

@Composable
fun HistoryScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: HistoryViewModel
) {
    val records by viewModel.records.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("録音履歴") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "録音履歴がありません",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "ホーム画面で録音を開始してください",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(records, key = { it.id }) { record ->
                    RecordItemCard(
                        record = record,
                        onClick = { onNavigateToDetail(record.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordItemCard(
    record: RecordItem,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = formatDatetime(record.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "録音時間: ${formatDuration(record.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UploadStatusChip(record.uploadStatus)
                TranscriptionStatusChip(record.transcriptionStatus)
            }

            record.errorMessage?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "エラー: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun UploadStatusChip(status: UploadStatus) {
    val label = when (status) {
        UploadStatus.NOT_STARTED -> "未アップロード"
        UploadStatus.QUEUED -> "アップロード待ち"
        UploadStatus.UPLOADING -> "アップロード中"
        UploadStatus.UPLOADED -> "Drive保存済み"
        UploadStatus.ERROR -> "アップロードエラー"
    }
    SuggestionChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        icon = {
            Icon(
                if (status == UploadStatus.UPLOADED) Icons.Filled.Check
                else if (status == UploadStatus.ERROR) Icons.Filled.Warning
                else Icons.Filled.Cloud,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    )
}

@Composable
private fun TranscriptionStatusChip(status: TranscriptionStatus) {
    val label = when (status) {
        TranscriptionStatus.NOT_STARTED -> "文字起こし未設定"
        TranscriptionStatus.QUEUED -> "文字起こし待ち"
        TranscriptionStatus.PROCESSING -> "文字起こし中"
        TranscriptionStatus.COMPLETED -> "文字起こし完了"
        TranscriptionStatus.ERROR -> "文字起こしエラー"
    }
    SuggestionChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        icon = {
            Icon(
                Icons.Filled.Edit,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    )
}
