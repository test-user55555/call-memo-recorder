package com.example.callmemorecorder.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.callmemorecorder.domain.model.CallDirection
import com.example.callmemorecorder.domain.model.RecordItem
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
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

    // 画面離脱時に再生を停止
    DisposableEffect(Unit) {
        onDispose { viewModel.stopPlayback() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("録音履歴") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopPlayback()
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
                    Icon(
                        Icons.Filled.Mic, contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "録音履歴がありません",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "ホーム画面で録音を開始してください",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(records, key = { it.id }) { record ->
                    val isCurrentRecord = playbackState.recordId == record.id
                    RecordItemCard(
                        record = record,
                        isPlaying = isCurrentRecord && playbackState.isPlaying,
                        isExpanded = isCurrentRecord,
                        playbackState = if (isCurrentRecord) playbackState else PlaybackState(),
                        onPlayPause = { viewModel.togglePlayback(record) },
                        onSeekForward = { viewModel.seekForward() },
                        onSeekBackward = { viewModel.seekBackward() },
                        onSeekTo = { progress -> viewModel.seekTo(progress) },
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
    isExpanded: Boolean,
    playbackState: PlaybackState,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekTo: (Float) -> Unit,
    onClick: () -> Unit
) {
    val hasFile = record.localPath != null && File(record.localPath).exists()

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // ── 上段: 発着信アイコン + 通話情報 + 再生ボタン ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 発着信アイコン
                CallDirectionIcon(record.callDirection)
                Spacer(Modifier.width(8.dp))

                // 通話情報
                Column(modifier = Modifier.weight(1f)) {
                    // 通話相手名（連絡先名 → 電話番号 → タイトル の順でフォールバック）
                    val displayName = record.callerName
                        ?: record.callerNumber
                        ?: record.title.ifBlank { null }
                    if (displayName != null) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    // 発着信ラベル + 日時
                    val dirLabel = when (record.callDirection) {
                        CallDirection.INCOMING -> "着信"
                        CallDirection.OUTGOING -> "発信"
                        CallDirection.UNKNOWN  -> "録音"
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

                // 再生/一時停止ボタン
                FilledTonalIconButton(
                    onClick = onPlayPause,
                    enabled = hasFile,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = when {
                            isPlaying -> MaterialTheme.colorScheme.errorContainer
                            isExpanded && !isPlaying && playbackState.recordId != null ->
                                MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.primaryContainer
                        }
                    )
                ) {
                    Icon(
                        imageVector = when {
                            isPlaying -> Icons.Filled.Pause
                            isExpanded && playbackState.currentPositionMs > 0 -> Icons.Filled.PlayArrow
                            else -> Icons.Filled.PlayArrow
                        },
                        contentDescription = if (isPlaying) "一時停止" else "再生",
                        tint = when {
                            isPlaying -> MaterialTheme.colorScheme.onErrorContainer
                            isExpanded && !isPlaying && playbackState.recordId != null ->
                                MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                }
            }

            // ── プログレスバー & シークコントロール（再生中のみ展開） ──
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))

                    // 時間表示
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(playbackState.currentPositionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = formatDuration(playbackState.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // シークバー
                    Slider(
                        value = playbackState.progress,
                        onValueChange = { onSeekTo(it) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )

                    // シークボタン行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 早戻し -10秒
                        IconButton(onClick = onSeekBackward) {
                            Icon(
                                Icons.Filled.Replay10,
                                contentDescription = "10秒戻す",
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.width(24.dp))
                        // 再生/一時停止（中央大ボタン）
                        FilledIconButton(
                            onClick = onPlayPause,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "一時停止" else "再生",
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(Modifier.width(24.dp))
                        // 早送り +10秒
                        IconButton(onClick = onSeekForward) {
                            Icon(
                                Icons.Filled.Forward10,
                                contentDescription = "10秒進む",
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // ファイルなし警告
            if (!hasFile) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "録音ファイルが見つかりません",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── ステータスチップ ──
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
        CallDirection.INCOMING -> Triple(Icons.Filled.CallReceived, Color(0xFF2196F3), "着信")
        CallDirection.OUTGOING -> Triple(Icons.Filled.CallMade,     Color(0xFF4CAF50), "発信")
        CallDirection.UNKNOWN  -> Triple(Icons.Filled.Mic, MaterialTheme.colorScheme.onSurfaceVariant, "録音")
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
        UploadStatus.NOT_STARTED -> "未アップロード"  to Icons.Filled.CloudOff
        UploadStatus.QUEUED      -> "アップロード待ち" to Icons.Filled.CloudQueue
        UploadStatus.UPLOADING   -> "アップロード中"  to Icons.Filled.CloudUpload
        UploadStatus.UPLOADED    -> "保存済み"       to Icons.Filled.CloudDone
        UploadStatus.ERROR       -> "アップロードエラー" to Icons.Filled.Warning
    }
    SuggestionChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp)) }
    )
}
