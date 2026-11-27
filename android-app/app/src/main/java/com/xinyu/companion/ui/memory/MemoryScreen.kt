package com.xinyu.companion.ui.memory

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xinyu.companion.domain.memory.LongTermMemory
import com.xinyu.companion.domain.memory.MemoryConfirmationState
import com.xinyu.companion.domain.memory.MemoryJobStatus
import com.xinyu.companion.domain.memory.MemoryKind
import com.xinyu.companion.domain.memory.MemoryPolicy
import com.xinyu.companion.domain.memory.MemorySensitivity
import com.xinyu.companion.domain.memory.MemorySourceKind
import com.xinyu.companion.domain.memory.MemoryStatus
import com.xinyu.companion.ui.components.ActionSheet
import com.xinyu.companion.ui.components.NoticeBanner
import com.xinyu.companion.ui.components.NoticeTone
import com.xinyu.companion.ui.components.SurfaceCard
import com.xinyu.companion.ui.components.ToggleRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MemoryScreen(
    state: MemoryUiState,
    onBack: () -> Unit,
    onAutoMemoryChange: (Boolean) -> Unit,
    onConsolidateNow: () -> Unit,
    onAdd: (MemoryKind, String) -> Unit,
    onUpdate: (String, String) -> Unit,
    onConfirm: (String) -> Unit,
    onReject: (String) -> Unit,
    onDelete: (String) -> Unit,
    onForgetRecent: () -> Unit,
    onDiscardPending: () -> Unit,
    onClearAll: () -> Unit,
) {
    var showClearAll by remember { mutableStateOf(false) }
    var showForgetRecent by remember { mutableStateOf(false) }
    var showDiscardPending by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LongTermMemory?>(null) }
    var selectedSection by rememberSaveable { mutableStateOf(MemorySection.PENDING) }
    var sectionChosenByUser by rememberSaveable { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val counts = MemorySection.entries.associateWith { section ->
        state.memories.count { memorySection(it, now) == section }
    }
    val visibleMemories = state.memories.filter { memorySection(it, now) == selectedSection }

    LaunchedEffect(counts[MemorySection.PENDING], counts[MemorySection.ACTIVE]) {
        if (!sectionChosenByUser && (counts[MemorySection.PENDING] ?: 0) == 0) {
            selectedSection = when {
                (counts[MemorySection.ACTIVE] ?: 0) > 0 -> MemorySection.ACTIVE
                (counts[MemorySection.RECENT] ?: 0) > 0 -> MemorySection.RECENT
                else -> MemorySection.ARCHIVED
            }
        }
    }
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("记忆") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回设置")
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "手动添加记忆")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                MemoryControlCard(
                    state = state,
                    onAutoMemoryChange = onAutoMemoryChange,
                    onConsolidateNow = onConsolidateNow,
                )
            }
            if (state.message != null || state.error != null) {
                item {
                    NoticeBanner(
                        text = state.error ?: state.message.orEmpty(),
                        tone = if (state.error != null) NoticeTone.ERROR else NoticeTone.SUCCESS,
                    )
                }
            }
            state.latestSummary?.let { summary ->
                item {
                    SummaryCard(
                        title = "最近短期摘要",
                        range = rangeLabel(summary.periodStart, summary.periodEnd),
                        content = summary.content,
                    )
                }
            }

            stickyHeader(key = "memory-sections") {
                Surface(
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.97f),
                    tonalElevation = 1.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MemorySection.entries.forEach { section ->
                                FilterChip(
                                    selected = selectedSection == section,
                                    onClick = {
                                        sectionChosenByUser = true
                                        selectedSection = section
                                    },
                                    label = { Text("${section.label} ${counts[section] ?: 0}") },
                                )
                            }
                        }
                        if ((counts[MemorySection.PENDING] ?: 0) > 0) {
                            TextButton(onClick = { showDiscardPending = true }) {
                                Text("清理全部待确认")
                            }
                        }
                    }
                }
            }
            if (visibleMemories.isEmpty()) {
                item { EmptyMemoryCard(selectedSection) }
            } else {
                items(visibleMemories, key = LongTermMemory::id) { memory ->
                    MemoryCard(
                        modifier = Modifier.animateItem(),
                        memory = memory,
                        now = now,
                        onEdit = { editing = memory },
                        onConfirm = { onConfirm(memory.id) },
                        onReject = { onReject(memory.id) },
                        onDelete = { onDelete(memory.id) },
                    )
                }
            }
            item {
                TextButton(
                    onClick = { showForgetRecent = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("忘记最近三天的记忆")
                }
            }
            item {
                TextButton(
                    onClick = { showClearAll = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("清空全部记忆和摘要")
                }
            }
        }
    }

    if (showClearAll) {
        ActionSheet(
            title = "清空全部记忆？",
            body = "短期摘要和长期记忆都会被删除且无法恢复。原始聊天保留，自动记忆会暂停，避免旧对话立刻被重新整理。",
            confirmLabel = "清空记忆",
            destructive = true,
            onConfirm = {
                showClearAll = false
                onClearAll()
            },
            onDismiss = { showClearAll = false },
        )
    }
    if (showForgetRecent) {
        ActionSheet(
            title = "忘记最近三天的记忆？",
            body = "这会删除最近三天形成的长期记忆，原始聊天记录不会删除。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                showForgetRecent = false
                onForgetRecent()
            },
            onDismiss = { showForgetRecent = false },
        )
    }
    if (showDiscardPending) {
        ActionSheet(
            title = "清理全部待确认记忆？",
            body = "只删除处于“待确认”的 AI 候选，不会删除已生效的记忆、短期摘要或原始聊天。",
            confirmLabel = "清理",
            destructive = true,
            onConfirm = {
                showDiscardPending = false
                onDiscardPending()
            },
            onDismiss = { showDiscardPending = false },
        )
    }
    if (showAdd) {
        MemoryEditorDialog(
            title = "添加一条记忆",
            initialStatement = "",
            initialKind = MemoryKind.PREFERENCE,
            kindEditable = true,
            onDismiss = { showAdd = false },
            onSave = { kind, text ->
                showAdd = false
                onAdd(kind, text)
            },
        )
    }
    editing?.let { memory ->
        MemoryEditorDialog(
            title = "修改记忆",
            initialStatement = memory.statement,
            initialKind = memory.kind,
            kindEditable = false,
            onDismiss = { editing = null },
            onSave = { _, text ->
                editing = null
                onUpdate(memory.id, text)
            },
        )
    }
}

@Composable
private fun MemoryControlCard(
    state: MemoryUiState,
    onAutoMemoryChange: (Boolean) -> Unit,
    onConsolidateNow: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(16.dp)) {
            ToggleRow(
                icon = Icons.Outlined.Psychology,
                title = "自动记忆",
                subtitle = "空闲时整理最近对话，会产生额外 API 调用",
                checked = state.autoMemoryEnabled,
                onCheckedChange = onAutoMemoryChange,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onConsolidateNow,
                    enabled = !state.isProcessing,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("整理中…")
                    } else {
                        Text("现在整理")
                    }
                }
                state.latestJob?.let { job ->
                    Text(
                        when (job.status) {
                            MemoryJobStatus.PENDING -> "等待整理"
                            MemoryJobStatus.RUNNING -> "正在整理"
                            MemoryJobStatus.COMPLETED -> "上次整理完成"
                            MemoryJobStatus.FAILED -> "上次整理失败"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.latestJob?.lastError?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun MemoryCard(
    modifier: Modifier = Modifier,
    memory: LongTermMemory,
    now: Long,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onDelete: () -> Unit,
) {
    SurfaceCard(
        modifier = modifier,
        contentPadding = PaddingValues(15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                kindLabel(memory.kind),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                statusLabel(memory, now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            memory.statement,
            modifier = Modifier.padding(top = 7.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            sourceLabel(memory),
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        validUntilLabel(memory)?.let { label ->
            Text(
                label,
                modifier = Modifier.padding(top = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (memory.status == MemoryStatus.CANDIDATE &&
                memory.confirmationState != MemoryConfirmationState.REJECTED
            ) {
                TextButton(onClick = onReject) { Text("拒绝") }
                TextButton(onClick = onConfirm) { Text("确认") }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "修改记忆")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除记忆")
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    range: String,
    content: String,
) {
    var expanded by remember(content) { mutableStateOf(false) }
    SurfaceCard(modifier = Modifier.animateContentSize()) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            range,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp, bottom = 8.dp),
        )
        Text(
            content,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (content.length > 120 || content.lineSequence().count() > 4) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (expanded) "收起" else "展开")
            }
        }
    }
}

@Composable
private fun EmptyMemoryCard(section: MemorySection) {
    SurfaceCard(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        elevation = 0.dp,
        border = null,
        contentPadding = PaddingValues(18.dp),
    ) {
        Text(section.emptyTitle, fontWeight = FontWeight.SemiBold)
        Text(
            section.emptyHint,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemoryEditorDialog(
    title: String,
    initialStatement: String,
    initialKind: MemoryKind,
    kindEditable: Boolean,
    onDismiss: () -> Unit,
    onSave: (MemoryKind, String) -> Unit,
) {
    var statement by remember { mutableStateOf(initialStatement) }
    var kind by remember { mutableStateOf(initialKind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val options = listOf(
                    MemoryKind.FACT,
                    MemoryKind.PREFERENCE,
                    MemoryKind.BOUNDARY,
                    MemoryKind.PLAN,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.take(2).forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(kindLabel(option)) },
                            enabled = kindEditable,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.drop(2).forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(kindLabel(option)) },
                            enabled = kindEditable,
                        )
                    }
                }
                OutlinedTextField(
                    value = statement,
                    onValueChange = { statement = it.take(320) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    label = { Text("一条独立、明确的事实") },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(kind, statement) },
                enabled = statement.isNotBlank(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun kindLabel(kind: MemoryKind): String = when (kind) {
    MemoryKind.FACT -> "事实"
    MemoryKind.PREFERENCE -> "偏好"
    MemoryKind.BOUNDARY -> "边界"
    MemoryKind.PLAN -> "计划"
    MemoryKind.EVENT -> "经历"
    MemoryKind.RELATIONSHIP_PREFERENCE -> "相处方式"
    MemoryKind.SUMMARY -> "摘要"
}

private fun statusLabel(memory: LongTermMemory, now: Long): String = when {
    memory.confirmationState == MemoryConfirmationState.REJECTED -> "已拒绝"
    effectiveStatus(memory, now) == MemoryStatus.ACTIVE &&
        !MemoryPolicy.isEligibleForPrompt(memory, now) -> "已停用"
    effectiveStatus(memory, now) == MemoryStatus.ACTIVE -> "已生效"
    effectiveStatus(memory, now) == MemoryStatus.CANDIDATE -> "待确认"
    memory.status == MemoryStatus.SUPERSEDED -> "已被更新"
    effectiveStatus(memory, now) == MemoryStatus.EXPIRED -> "已过期"
    else -> "已删除"
}

private fun sourceLabel(memory: LongTermMemory): String {
    val source = when (memory.sourceKind) {
        MemorySourceKind.USER_EXPLICIT -> "用户明说"
        MemorySourceKind.COMPANION_EXPLICIT -> "AI 提及"
        MemorySourceKind.USER_FEEDBACK -> "用户确认"
        MemorySourceKind.INFERRED -> "AI 推测"
        MemorySourceKind.MANUAL -> "手动添加"
    }
    return "$source · ${isoDate(memory.observedAt)}"
}

private fun rangeLabel(start: Long, end: Long): String =
    "${isoDate(start)} – ${isoDate(end)}"

private fun validUntilLabel(memory: LongTermMemory): String? =
    memory.validTo?.let { "有效至 ${isoDate(it)}" }

private fun isoDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ISO_LOCAL_DATE)

private fun effectiveStatus(memory: LongTermMemory, now: Long): MemoryStatus =
    if (
        memory.status in setOf(MemoryStatus.ACTIVE, MemoryStatus.CANDIDATE) &&
        memory.validTo != null &&
        memory.validTo <= now
    ) {
        MemoryStatus.EXPIRED
    } else {
        memory.status
    }

private enum class MemorySection(
    val label: String,
    val emptyTitle: String,
    val emptyHint: String,
) {
    ACTIVE(
        label = "已生效",
        emptyTitle = "还没有已生效的记忆",
        emptyHint = "明确偏好和边界会在整理后出现在这里。",
    ),
    PENDING(
        label = "待确认",
        emptyTitle = "没有待确认记忆",
        emptyHint = "AI 不确定的内容会先放在这里，不会影响聊天。",
    ),
    RECENT(
        label = "近期经历",
        emptyTitle = "近期没有需要保留的经历",
        emptyHint = "普通情绪和日常问答只留在短期摘要里。",
    ),
    ARCHIVED(
        label = "已过期",
        emptyTitle = "没有已过期记忆",
        emptyHint = "有期限的经历、计划和被替代的信息会归档到这里。",
    ),
}

private fun memorySection(memory: LongTermMemory, now: Long): MemorySection {
    val status = effectiveStatus(memory, now)
    return when {
        memory.confirmationState == MemoryConfirmationState.REJECTED -> MemorySection.ARCHIVED
        status in setOf(MemoryStatus.EXPIRED, MemoryStatus.SUPERSEDED, MemoryStatus.DELETED) ->
            MemorySection.ARCHIVED
        memory.kind == MemoryKind.EVENT -> MemorySection.RECENT
        status == MemoryStatus.ACTIVE && MemoryPolicy.isEligibleForPrompt(memory, now) ->
            MemorySection.ACTIVE
        status == MemoryStatus.ACTIVE &&
            memory.sensitivity == MemorySensitivity.SENSITIVE &&
            memory.confirmationState != MemoryConfirmationState.CONFIRMED ->
            MemorySection.PENDING
        status == MemoryStatus.ACTIVE -> MemorySection.ARCHIVED
        status == MemoryStatus.CANDIDATE -> MemorySection.PENDING
        else -> MemorySection.ARCHIVED
    }
}
