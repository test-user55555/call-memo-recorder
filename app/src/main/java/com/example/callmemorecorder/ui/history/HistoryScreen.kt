package com.example.callmemorecorder.ui.history

import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.callmemorecorder.domain.model.CallDirection
import com.example.callmemorecorder.domain.model.RecordItem
import com.example.callmemorecorder.domain.model.TranscriptionStatus
import com.example.callmemorecorder.domain.model.UploadStatus
import com.example.callmemorecorder.util.formatDatetime
import com.example.callmemorecorder.util.formatDuration
import java.io.File

@Composable
fun HistoryScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: HistoryViewModel
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    // 現在再生中のレコードID
    var playingRecordId by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // 画面離脱時にプレイヤーを解放
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("録音履歴") },
                navigationIcon = {
                    IconButton(onClick = {
                        mediaPlayer?.release(); mediaPlayer = null
                        onNavigateBack()
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (records.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("録音履歴がありません",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("ホーム画面で録音を開始してください",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(records, key = { it.id }) { record ->
                    RecordItemCard(
                        record = record,
                        isPlaying = playingRecordId == record.id,
                        onPlayPause = { rec ->
                            if (playingRecordId == rec.id) {
                                // 停止
                                mediaPlayer?.release()
                                mediaPlayer = null
                                playingRecordId = null
                            } else {
                                // 再生
                                mediaPlayer?.release()
                                val path = rec.localPath
                                if (path != null && File(path).exists()) {
                                    mediaPlayer = MediaPlayer().apply {
                                        setDataSource(path)
                                        prepare()
                                        start()
                                        setOnCompletionListener {
                                            release()
                                            mediaPlayer = null
                                            playingRecordId = null
                                        }
                                    }
                                    playingRecordId = rec.id
                                }
                            }
                        },
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
    isPlaying: Boolean,
    onPlayPause: (RecordItem) -> Unit,
    onClick: () -> Unit
) {
    val hasFile = record.localPath != null && File(record.localPath).exists()

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // ── 上段: 発着信アイコン + タイトル + 再生ボタン ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 発着信アイコン
                CallDirectionIcon(record.callDirection)
                Spacer(Modifier.width(8.dp))

                // タイトル（相手名または番号）
                Column(modifier = Modifier.weight(1f)) {
                    val displayName = record.callerName ?: record.callerNumber
                    if (displayName != null) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    val dirLabel = when (record.callDirection) {
                        CallDirection.INCOMING -> "着信"
                        CallDirection.OUTGOING -> "発信"
                        CallDirection.UNKNOWN  -> "通話"
                    }
                    Text(
                        text = "$dirLabel  ${formatDatetime(record.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "録音時間: ${formatDuration(record.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 再生/停止ボタン
                FilledTonalIconButton(
                    onClick = { onPlayPause(record) },
                    enabled = hasFile,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isPlaying)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "停止" else "再生",
                        tint = if (isPlaying)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (record.localPath == null || !File(record.localPath).exists()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "録音ファイルが見つかりません",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── 下段: ステータスチップ ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                UploadStatusChip(record.uploadStatus)
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
private fun CallDirectionIcon(direction: CallDirection) {
    val (icon, tint, label) = when (direction) {
        CallDirection.INCOMING -> Triple(
            Icons.Filled.CallReceived,
            Color(0xFF2196F3),  // 青: 着信
            "着信"
        )
        CallDirection.OUTGOING -> Triple(
            Icons.Filled.CallMade,
            Color(0xFF4CAF50),  // 緑: 発信
            "発信"
        )
        CallDirection.UNKNOWN  -> Triple(
            Icons.Filled.Mic,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "録音"
        )
    }
    Icon(
        imageVector = icon,
        contentDescription = label,
        modifier = Modifier.size(28.dp),
        tint = tint
    )
}

@Composable
private fun UploadStatusChip(status: UploadStatus) {
    val (label, icon) = when (status) {
        UploadStatus.NOT_STARTED -> "未アップロード" to Icons.Filled.CloudOff
        UploadStatus.QUEUED      -> "アップロード待ち" to Icons.Filled.CloudQueue
        UploadStatus.UPLOADING   -> "アップロード中" to Icons.Filled.CloudUpload
        UploadStatus.UPLOADED    -> "保存済み" to Icons.Filled.CloudDone
        UploadStatus.ERROR       -> "アップロードエラー" to Icons.Filled.Warning
    }
    SuggestionChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        icon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    )
}
