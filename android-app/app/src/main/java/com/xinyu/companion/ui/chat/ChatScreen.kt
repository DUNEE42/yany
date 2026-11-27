package com.xinyu.companion.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xinyu.companion.domain.ChatMessage
import com.xinyu.companion.domain.MessageRole
import com.xinyu.companion.domain.MessageStatus
import com.xinyu.companion.ui.components.ActionSheet
import com.xinyu.companion.ui.components.CompanionAvatar
import com.xinyu.companion.ui.components.IntroHeroCard
import com.xinyu.companion.ui.components.MarkdownMessageText
import com.xinyu.companion.ui.components.StatusPill
import com.xinyu.companion.ui.components.StatusTone
import com.xinyu.companion.ui.components.SubtleThinkingIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    state: ChatUiState,
    companionName: String = state.companionName,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    companionAvatarPath: String? = state.companionAvatarPath,
    onSearchQueryChange: (String) -> Unit = {},
    onConfirmSearch: () -> Unit = {},
    onSendWithoutSearch: () -> Unit = {},
    onDismissSearchConfirmation: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showTimeReminder by rememberSaveable { mutableStateOf(false) }
    val visibleMessages = remember(state.messages) {
        state.messages.filterNot {
            it.content.isBlank() && it.status == MessageStatus.INTERRUPTED
        }
    }
    var autoFollow by remember { mutableStateOf(true) }
    val nearBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            layout.totalItemsCount > 0 &&
                lastVisible.index >= layout.totalItemsCount - 1 &&
                lastVisible.offset + lastVisible.size >= layout.viewportEndOffset - 96
        }
    }

    LaunchedEffect(visibleMessages.size, visibleMessages.lastOrNull()?.id) {
        val last = visibleMessages.lastOrNull()
        if (last != null && (autoFollow || last.role == MessageRole.USER)) {
            autoFollow = true
            listState.animateScrollToItem(visibleMessages.lastIndex)
        }
    }
    LaunchedEffect(visibleMessages.lastOrNull()?.content, state.isStreaming) {
        if (state.isStreaming && autoFollow && visibleMessages.isNotEmpty()) {
            listState.scrollToItem(visibleMessages.lastIndex, scrollOffset = Int.MAX_VALUE)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to nearBottom }
            .collect { (scrolling, atBottom) ->
                if (scrolling && !atBottom) {
                    autoFollow = false
                } else if (!scrolling) {
                    autoFollow = atBottom
                }
            }
    }
    LaunchedEffect(Unit) {
        delay(2 * 60 * 60 * 1000L)
        showTimeReminder = true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CompanionHeader(
                companionName = companionName,
                companionAvatarPath = companionAvatarPath,
                hasApiKey = state.hasApiKey,
                onOpenSettings = onOpenSettings,
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                AnimatedVisibility(visible = state.error != null) {
                    ErrorCard(
                        message = state.error.orEmpty(),
                        canRetry = state.canRetry,
                        onRetry = onRetry,
                    )
                }
                AnimatedVisibility(
                    visible = state.liveContextLabel != null,
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(100)),
                ) {
                    StatusPill(
                        label = state.liveContextLabel.orEmpty(),
                        tone = StatusTone.ACCENT,
                        icon = Icons.Outlined.AutoAwesome,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (state.safetyLevel == "crisis") {
                    CrisisCard()
                    Spacer(Modifier.height(8.dp))
                }
                Composer(
                    companionName = companionName,
                    input = state.input,
                    isStreaming = state.isStreaming,
                    canSend = state.hasApiKey && state.isConversationReady,
                    hasApiKey = state.hasApiKey,
                    isConversationReady = state.isConversationReady,
                    onInputChange = onInputChange,
                    onSend = {
                        autoFollow = true
                        onSend()
                    },
                    onStop = onStop,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (visibleMessages.isEmpty()) {
                    item {
                        IntroHeroCard(
                            title = "只属于你的日常",
                            body = "$companionName 是 AI 角色，不是真人。聊天和记忆加密保存在这台手机上；聊天请求以及开启自动记忆后的整理批次会发送到 DeepSeek。",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                items(visibleMessages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        companionName = companionName,
                        companionAvatarPath = companionAvatarPath,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            AnimatedVisibility(
                visible = !nearBottom && visibleMessages.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                enter = fadeIn() + scaleIn(initialScale = 0.9f),
                exit = fadeOut() + scaleOut(targetScale = 0.9f),
            ) {
                Surface(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable {
                            autoFollow = true
                            if (visibleMessages.isNotEmpty()) {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(visibleMessages.lastIndex)
                                }
                            }
                        },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("回到最新", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }

    if (showTimeReminder) {
        ActionSheet(
            title = "已经聊了一会儿",
            body = "你已经在言语连续停留两小时。要不要伸展一下、喝口水，或给现实中的朋友发条消息？",
            confirmLabel = "休息一下",
            dismissLabel = "继续聊",
            onConfirm = { showTimeReminder = false },
            onDismiss = { showTimeReminder = false },
        )
    }

    state.searchConfirmation?.let { pending ->
        SearchConfirmationSheet(
            state = pending,
            onQueryChange = onSearchQueryChange,
            onConfirm = onConfirmSearch,
            onSendWithoutSearch = onSendWithoutSearch,
            onDismiss = onDismissSearchConfirmation,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchConfirmationSheet(
    state: SearchConfirmationUiState,
    onQueryChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onSendWithoutSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "确认这次要搜什么",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "只会把下面这段搜索词发送到你配置的 SearXNG，不会发送整段聊天。如果其中有姓名、学校或公司，请先删掉不必要的信息。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("将发送的搜索词") },
                supportingText = {
                    Text(
                        state.error ?: "这次确认只对当前消息有效",
                        color = if (state.error == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                },
                minLines = 2,
                maxLines = 4,
                isError = state.error != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
            )
            Button(
                onClick = onConfirm,
                enabled = state.query.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("搜索并发送")
            }
            OutlinedButton(
                onClick = onSendWithoutSearch,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("仅聊天，不搜索")
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("先不发，继续修改")
            }
        }
    }
}

@Composable
private fun CompanionHeader(
    companionName: String,
    companionAvatarPath: String?,
    hasApiKey: Boolean,
    onOpenSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompanionAvatar(
                name = companionName,
                avatarPath = companionAvatarPath,
                size = 44.dp,
                prominent = true,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        companionName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusPill(
                        label = "AI",
                        tone = StatusTone.ACCENT,
                        icon = Icons.Outlined.AutoAwesome,
                    )
                }
                Text(
                    if (hasApiKey) {
                        "本地加密 · 必要上下文会发送给 DeepSeek"
                    } else {
                        "等待连接"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "设置")
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    companionName: String,
    companionAvatarPath: String?,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.USER
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isUser) {
            CompanionAvatar(
                name = companionName,
                avatarPath = companionAvatarPath,
                size = 28.dp,
                prominent = false,
            )
            Spacer(Modifier.width(8.dp))
        }
        Surface(
            shape = if (isUser) {
                RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp)
            } else {
                RoundedCornerShape(5.dp, 20.dp, 20.dp, 20.dp)
            },
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = if (isUser) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            tonalElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier.fillMaxWidth(if (isUser) 0.82f else 0.88f),
        ) {
            SelectionContainer {
                Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
                    when {
                        message.content.isBlank() && message.status == MessageStatus.STREAMING -> {
                            SubtleThinkingIndicator(label = "正在想怎么说…")
                        }
                        message.content.isBlank() && message.status == MessageStatus.FAILED -> {
                            Text(
                                "这次回复没有完成",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        isUser -> {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        else -> {
                            MarkdownMessageText(
                                text = message.content,
                                isUserMessage = false,
                            )
                        }
                    }
                    if (message.status == MessageStatus.INTERRUPTED) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "已停止生成",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    companionName: String,
    input: String,
    isStreaming: Boolean,
    canSend: Boolean,
    hasApiKey: Boolean,
    isConversationReady: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val sendEnabled = canSend && !isStreaming && input.isNotBlank()
    val actionColor by animateColorAsState(
        targetValue = when {
            isStreaming -> MaterialTheme.colorScheme.surfaceVariant
            sendEnabled -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(durationMillis = 180),
        label = "composer-action-color",
    )
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        when {
                            !hasApiKey -> "请先完成连接"
                            !isConversationReady -> "正在准备本地聊天…"
                            else -> "和${companionName}说点什么"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                minLines = 1,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            IconButton(
                onClick = {
                    if (isStreaming) onStop() else if (sendEnabled) onSend()
                },
                enabled = isStreaming || sendEnabled,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(actionColor),
            ) {
                AnimatedContent(
                    targetState = isStreaming,
                    transitionSpec = {
                        (fadeIn(tween(120)) + scaleIn(tween(150), initialScale = 0.7f))
                            .togetherWith(
                                fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.7f),
                            )
                    },
                    label = "composer-action",
                ) { streaming ->
                    Icon(
                        imageVector = if (streaming) {
                            Icons.Rounded.Stop
                        } else {
                            Icons.Rounded.ArrowUpward
                        },
                        contentDescription = if (streaming) "停止生成" else "发送",
                        tint = when {
                            streaming -> MaterialTheme.colorScheme.onSurfaceVariant
                            sendEnabled -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.78f),
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            if (canRetry) {
                TextButton(onClick = onRetry) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("重试")
                }
            }
        }
    }
}

@Composable
private fun CrisisCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "先暂停角色扮演",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "你的安全比这段对话更重要。如果现在有立即危险，请拨打 120 或 110；也可以联系身边可信任的人，或尝试全国统一心理援助热线 12356。AI 不能代替现实中的急救和专业支持。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
