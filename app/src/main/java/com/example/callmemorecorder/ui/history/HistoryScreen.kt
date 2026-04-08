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
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: HistoryViewModel
) {
    val records       by viewModel.records.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val filter        by viewModel.filter.collectAsStateWithLifecycle()

    var isSelectionMode    by remember { mutableStateOf(false) }
    var selectedIds        by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm  by remember { mutableStateOf(false) }
    var showFilterPanel    by remember { mutableStateOf(false) }

    // DatePicker dialog state
    var showDateFromPicker by remember { mutableStateOf(false) }
    var showDateToPicker   by remember { mutableStateOf(false) }

    LaunchedEffect(isSelectionMode) {
        if (!isSelectionMode) selectedIds = emptySet()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopPlayback() }
    }

    // ── 一括削除確認ダイアログ ──────────────────────────────────────────────
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = {
                Icon(
                    Icons.Filled.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("一括削除の確認") },
            text = {
                Text(
                    text = "${selectedIds.count()}件の録音を削除します。\nこの操作は取り消せません。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteRecords(selectedIds)
                        selectedIds = emptySet()
                        isSelectionMode = false
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("削除する")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // ── 開始日 DatePicker ──────────────────────────────────────────────────
    if (showDateFromPicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = filter.dateFrom ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDateFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDateFrom(datePickerState.selectedDateMillis)
                    showDateFromPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDateFromPicker = false }) { Text("キャンセル") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // ── 終了日 DatePicker ──────────────────────────────────────────────────
    if (showDateToPicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = filter.dateTo ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDateToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDateTo(datePickerState.selectedDateMillis)
                    showDateToPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDateToPicker = false }) { Text("キャンセル") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // フィルターが有効かどうか
    val isFilterActive = filter.query.isNotBlank() ||
            filter.dateFrom != null ||
            filter.dateTo != null ||
            filter.directionFilter != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text("${selectedIds.count()}件選択中")
                    } else {
                        Text("録音履歴")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSelectionMode) {
                            isSelectionMode = false
                        } else {
                            viewModel.stopPlayback()
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = if (isSelectionMode) Icons.Filled.Close else Icons.Filled.ArrowBack,
                            contentDescription = if (isSelectionMode) "選択解除" else "Back"
                        )
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        val allSelected = records.isNotEmpty() && selectedIds.count() == records.size
                        TextButton(onClick = {
                            selectedIds = if (allSelected) emptySet()
                            else records.map { it.id }.toSet()
                        }) {
                            Text(if (allSelected) "全解除" else "全選択")
                        }
                        IconButton(
                            onClick = { if (selectedIds.isNotEmpty()) showDeleteConfirm = true },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "削除",
                                tint = if (selectedIds.isNotEmpty())
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        // 検索ボタン
                        IconButton(onClick = { showFilterPanel = !showFilterPanel }) {
                            BadgedBox(
                                badge = {
                                    if (isFilterActive) {
                                        Badge()
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = "検索・絞り込み",
                                    tint = if (isFilterActive)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        LocalContentColor.current
                                )
                            }
                        }
                        if (records.isNotEmpty()) {
                            IconButton(onClick = { isSelectionMode = true }) {
                                Icon(Icons.Filled.DeleteSweep, contentDescription = "一括削除")
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── 検索・フィルターパネル ──────────────────────────────────────
            AnimatedVisibility(
                visible = showFilterPanel,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                FilterPanel(
                    filter = filter,
                    onQueryChange = viewModel::setQuery,
                    onDateFromClick = { showDateFromPicker = true },
                    onDateToClick = { showDateToPicker = true },
                    onClearDateFrom = { viewModel.setDateFrom(null) },
                    onClearDateTo = { viewModel.setDateTo(null) },
                    onDirectionChange = viewModel::setDirectionFilter,
                    onSetDateRangeQuick = viewModel::setDateRangeDaysAgo,
                    onClearAll = {
                        viewModel.clearFilter()
                    }
                )
            }

            if (records.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (isFilterActive) Icons.Filled.SearchOff else Icons.Filled.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (isFilterActive) "条件に一致する録音がありません" else "録音履歴がありません",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isFilterActive) {
                            TextButton(onClick = { viewModel.clearFilter() }) {
                                Text("フィルターをクリア")
                            }
                        } else {
                            Text(
                                "ホーム画面で録音を開始してください",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(records, key = { it.id }) { record ->
                        val isCurrentRecord = playbackState.recordId == record.id
                        val isSelected = selectedIds.contains(record.id)
                        RecordItemCard(
                            record = record,
                            isPlaying = isCurrentRecord && playbackState.isPlaying,
                            isExpanded = isCurrentRecord && !isSelectionMode,
                            playbackState = if (isCurrentRecord) playbackState else PlaybackState(),
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onPlayPause = { if (!isSelectionMode) viewModel.togglePlayback(record) },
                            onSeekForward = { viewModel.seekForward() },
                            onSeekBackward = { viewModel.seekBackward() },
                            onSeekTo = { viewModel.seekTo(it) },
                            onClick = {
                                if (isSelectionMode) {
                                    selectedIds = if (isSelected) {
                                        selectedIds - record.id
                                    } else {
                                        selectedIds + record.id
                                    }
                                } else {
                                    onNavigateToDetail(record.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── 検索・絞り込みパネル ──────────────────────────────────────────────────

@Composable
private fun FilterPanel(
    filter: SearchFilter,
    onQueryChange: (String) -> Unit,
    onDateFromClick: () -> Unit,
    onDateToClick: () -> Unit,
    onClearDateFrom: () -> Unit,
    onClearDateTo: () -> Unit,
    onDirectionChange: (CallDirection?) -> Unit,
    onSetDateRangeQuick: (Int) -> Unit,
    onClearAll: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }

    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── フリーワード検索 ──────────────────────────────────────────
            OutlinedTextField(
                value = filter.query,
                onValueChange = onQueryChange,
                label = { Text("名前・番号で検索") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.query.isNotBlank()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "クリア")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // ── 期間絞り込み ──────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 開始日
                OutlinedButton(
                    onClick = onDateFromClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (filter.dateFrom != null)
                            dateFormatter.format(Date(filter.dateFrom))
                        else "開始日",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                    if (filter.dateFrom != null) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "クリア",
                            modifier = Modifier
                                .size(14.dp)
                                .let { mod ->
                                    mod
                                }
                        )
                    }
                }
                Text("〜", style = MaterialTheme.typography.bodyMedium)
                // 終了日
                OutlinedButton(
                    onClick = onDateToClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (filter.dateTo != null)
                            dateFormatter.format(Date(filter.dateTo))
                        else "終了日",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
            }

            // 開始日/終了日 クリアボタン（選択されている場合のみ）
            if (filter.dateFrom != null || filter.dateTo != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (filter.dateFrom != null) {
                        SuggestionChip(
                            onClick = onClearDateFrom,
                            label = { Text("開始日クリア", style = MaterialTheme.typography.labelSmall) },
                            icon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(12.dp)) }
                        )
                    }
                    if (filter.dateTo != null) {
                        SuggestionChip(
                            onClick = onClearDateTo,
                            label = { Text("終了日クリア", style = MaterialTheme.typography.labelSmall) },
                            icon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(12.dp)) }
                        )
                    }
                }
            }

            // ── 期間クイック選択 ──────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(7 to "7日", 30 to "30日", 90 to "3ヶ月").forEach { (days, label) ->
                    FilterChip(
                        selected = false,
                        onClick = { onSetDateRangeQuick(days) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // ── 発着信フィルター ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "方向:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(
                    null to "すべて",
                    CallDirection.INCOMING to "着信",
                    CallDirection.OUTGOING to "発信"
                ).forEach { (dir, label) ->
                    FilterChip(
                        selected = filter.directionFilter == dir,
                        onClick = { onDirectionChange(dir) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // ── 全クリアボタン ──────────────────────────────────────────
            val isFilterActive = filter.query.isNotBlank() ||
                    filter.dateFrom != null ||
                    filter.dateTo != null ||
                    filter.directionFilter != null
            if (isFilterActive) {
                TextButton(
                    onClick = onClearAll,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Filled.ClearAll, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("すべてクリア", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ── レコードカード ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordItemCard(
    record: RecordItem,
    isPlaying: Boolean,
    isExpanded: Boolean,
    playbackState: PlaybackState,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekTo: (Float) -> Unit,
    onClick: () -> Unit
) {
    val hasFile = record.localPath != null && File(record.localPath).exists()

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = if (isSelectionMode) 0.dp else 12.dp,
                        end = 12.dp,
                        top = 12.dp,
                        bottom = 12.dp
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallDirectionIcon(record.callDirection)
                    Spacer(Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
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

                    if (!isSelectionMode) {
                        FilledTonalIconButton(
                            onClick = onPlayPause,
                            enabled = hasFile,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (isPlaying)
                                    MaterialTheme.colorScheme.errorContainer
                                else
                                    MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "一時停止" else "再生",
                                tint = if (isPlaying)
                                    MaterialTheme.colorScheme.onErrorContainer
                                else
                                    MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(8.dp))

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

                        Slider(
                            value = playbackState.progress,
                            onValueChange = { onSeekTo(it) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onSeekBackward) {
                                Icon(
                                    Icons.Filled.Replay10,
                                    contentDescription = "10秒戻す",
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.width(24.dp))
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

                if (!hasFile && !isSelectionMode) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "録音ファイルが見つかりません",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(8.dp))

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
        UploadStatus.NOT_STARTED -> "未アップロード"   to Icons.Filled.CloudOff
        UploadStatus.QUEUED      -> "アップロード待ち"  to Icons.Filled.CloudQueue
        UploadStatus.UPLOADING   -> "アップロード中"   to Icons.Filled.CloudUpload
        UploadStatus.UPLOADED    -> "保存済み"        to Icons.Filled.CloudDone
        UploadStatus.ERROR       -> "アップロードエラー" to Icons.Filled.Warning
    }
    SuggestionChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp)) }
    )
}
