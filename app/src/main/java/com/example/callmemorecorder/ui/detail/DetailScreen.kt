package com.example.callmemorecorder.ui.detail

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
fun DetailScreen(
    recordId: String,
    onNavigateBack: () -> Unit,
    viewModel: DetailViewModel
) {
    val record by viewModel.record.collectAsStateWithLifecycle()
    val uiMessage by viewModel.uiMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 再生状態
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var progressTrackActive by remember { mutableStateOf(false) }

    // 進捗更新
    LaunchedEffect(progressTrackActive) {
        if (progressTrackActive) {
            while (progressTrackActive) {
                kotlinx.coroutines.delay(200)
                val mp = mediaPlayer ?: break
                if (!mp.isPlaying) break
                currentPositionMs = mp.currentPosition.toLong()
            }
        }
    }

    DisposableEffect(recordId) {
        viewModel.loadRecord(recordId)
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
            isPlaying = false
            progressTrackActive = false
        }
    }

    uiMessage?.let {
        LaunchedEffect(it) { viewModel.clearMessage() }
    }

    fun stopPlayback() {
        progressTrackActive = false
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        currentPositionMs = 0L
    }

    fun startPlayback(path: String, dur: Long) {
        stopPlayback()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
                durationMs = this.duration.toLong().let { if (it > 0) it else dur }
                setOnCompletionListener {
                    isPlaying = false
                    progressTrackActive = false
                    currentPositionMs = 0L
                }
            }
            isPlaying = true
            progressTrackActive = true
        } catch (e: Exception) {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("録音詳細") },
                navigationIcon = {
                    IconButton(onClick = {
                        stopPlayback()
                        onNavigateBack()
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        val currentRecord = record

        if (currentRecord == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            val hasFile = currentRecord.localPath != null && File(currentRecord.localPath).exists()
            val fileDuration = if (durationMs > 0) durationMs else currentRecord.durationMs
            val progress = if (fileDuration > 0) currentPositionMs.toFloat() / fileDuration else 0f

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                // ── 通話情報カード ──────────────────────────────
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 発着信アイコン + 通話相手
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (dirIcon, dirColor, dirLabel) = when (currentRecord.callDirection) {
                                CallDirection.INCOMING -> Triple(Icons.Filled.CallReceived, Color(0xFF2196F3), "着信")
                                CallDirection.OUTGOING -> Triple(Icons.Filled.CallMade,     Color(0xFF4CAF50), "発信")
                                CallDirection.UNKNOWN  -> Triple(Icons.Filled.Mic, MaterialTheme.colorScheme.primary, "録音")
                            }
                            Icon(
                                dirIcon, contentDescription = dirLabel,
                                modifier = Modifier.size(36.dp), tint = dirColor
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                // 通話相手名
                                val contactDisplay = currentRecord.callerName
                                    ?: currentRecord.callerNumber
                                    ?: currentRecord.title.ifBlank { "不明" }
                                Text(
                                    text = contactDisplay,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                // 電話番号（名前と番号が両方ある場合のみ番号も表示）
                                if (currentRecord.callerName != null && currentRecord.callerNumber != null) {
                                    Text(
                                        text = currentRecord.callerNumber,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                // 発着信ラベル
                                AssistChip(
                                    onClick = {},
                                    label = { Text(dirLabel, style = MaterialTheme.typography.labelSmall) },
                                    leadingIcon = {
                                        Icon(dirIcon, contentDescription = null,
                                            modifier = Modifier.size(14.dp), tint = dirColor)
                                    },
                                    modifier = Modifier.height(28.dp)
                                )
                            }
                        }

                        HorizontalDivider()

                        // 日時・録音時間
                        LabeledText("日時", formatDatetime(currentRecord.createdAt))
                        LabeledText("録音時間", formatDuration(currentRecord.durationMs))
                        LabeledText("ファイル形式", currentRecord.mimeType)
                        currentRecord.localPath?.let { LabeledText("保存先", it) }
                    }
                }

                // ── 再生プレイヤーカード ─────────────────────────
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("再生", fontWeight = FontWeight.Bold)

                        if (!hasFile) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Warning, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "録音ファイルが見つかりません",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        } else {
                            // 時間表示
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    formatDuration(currentPositionMs),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    formatDuration(fileDuration),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // シークバー
                            Slider(
                                value = progress,
                                onValueChange = { p ->
                                    val target = (p * fileDuration).toLong()
                                    mediaPlayer?.seekTo(target.toInt())
                                    currentPositionMs = target
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            // コントロールボタン行
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 早戻し -10秒
                                IconButton(onClick = {
                                    val mp = mediaPlayer ?: return@IconButton
                                    val newPos = (mp.currentPosition - 10_000).coerceAtLeast(0)
                                    mp.seekTo(newPos)
                                    currentPositionMs = newPos.toLong()
                                }) {
                                    Icon(
                                        Icons.Filled.Replay10, contentDescription = "10秒戻す",
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.width(24.dp))

                                // 再生/一時停止（中央大ボタン）
                                FilledIconButton(
                                    onClick = {
                                        val path = currentRecord.localPath ?: return@FilledIconButton
                                        if (isPlaying) {
                                            mediaPlayer?.pause()
                                            isPlaying = false
                                            progressTrackActive = false
                                        } else if (mediaPlayer != null) {
                                            mediaPlayer?.start()
                                            isPlaying = true
                                            progressTrackActive = true
                                        } else {
                                            startPlayback(path, currentRecord.durationMs)
                                        }
                                    },
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Icon(
                                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (isPlaying) "一時停止" else "再生",
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Spacer(Modifier.width(24.dp))

                                // 早送り +10秒
                                IconButton(onClick = {
                                    val mp = mediaPlayer ?: return@IconButton
                                    val newPos = (mp.currentPosition + 10_000).coerceAtMost(fileDuration.toInt())
                                    mp.seekTo(newPos)
                                    currentPositionMs = newPos.toLong()
                                }) {
                                    Icon(
                                        Icons.Filled.Forward10, contentDescription = "10秒進む",
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                // ── アップロード状態カード ────────────────────────
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
                                ) { Text("開く") }
                            }
                        }
                        Button(
                            onClick = { viewModel.reUpload() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.CloudUpload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("再アップロード")
                        }
                    }
                }

                // ── 文字起こしカード ──────────────────────────────
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
                                    TranscriptionStatus.NOT_STARTED -> "文字起こし未設定"
                                    TranscriptionStatus.QUEUED -> "文字起こし待ち..."
                                    TranscriptionStatus.PROCESSING -> "文字起こし中..."
                                    else -> "テキストなし"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // エラー表示
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

                // 削除ボタン
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
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteRecord { onNavigateBack() }
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("キャンセル") }
            }
        )
    }
}

@Composable
private fun LabeledText(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("$label: ", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun uploadStatusText(status: UploadStatus) = when (status) {
    UploadStatus.NOT_STARTED -> "未アップロード"
    UploadStatus.QUEUED      -> "アップロード待ち"
    UploadStatus.UPLOADING   -> "アップロード中"
    UploadStatus.UPLOADED    -> "保存済み"
    UploadStatus.ERROR       -> "エラー"
}

private fun transcriptionStatusText(status: TranscriptionStatus) = when (status) {
    TranscriptionStatus.NOT_STARTED -> "未設定"
    TranscriptionStatus.QUEUED      -> "待ち"
    TranscriptionStatus.PROCESSING  -> "処理中"
    TranscriptionStatus.COMPLETED   -> "完了"
    TranscriptionStatus.ERROR       -> "エラー"
}
