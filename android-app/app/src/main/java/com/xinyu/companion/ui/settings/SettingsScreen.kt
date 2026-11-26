package com.xinyu.companion.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xinyu.companion.BuildConfig
import com.xinyu.companion.domain.ConversationStyle
import com.xinyu.companion.domain.ReplyLength
import com.xinyu.companion.ui.components.ActionSheet
import com.xinyu.companion.ui.theme.Coral
import com.xinyu.companion.ui.theme.Sage
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onTestAndSave: () -> Unit,
    onStyleChange: (Float, Float, ReplyLength) -> Unit,
    autoMemoryEnabled: Boolean,
    memoryCount: Int,
    onAutoMemoryChange: (Boolean) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenPerception: () -> Unit,
    onOpenReminders: () -> Unit = {},
    onClearApiKey: () -> Unit,
    onClearChatMessages: () -> Unit,
) {
    var showClearKeyConfirm by remember { mutableStateOf(false) }
    var showClearChatConfirm by remember { mutableStateOf(false) }
    val canLeave = state.hasApiKey
    BackHandler(enabled = canLeave, onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.hasApiKey) "设置" else "连接 DeepSeek")
                },
                navigationIcon = {
                    if (canLeave) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回聊天")
                        }
                    }
                },
                actions = {
                    if (canLeave) {
                        TextButton(onClick = onBack) {
                            Text("开始聊天")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (!state.hasApiKey) {
                WelcomeCard()
            }
            AnimatedVisibility(
                visible = state.message != null,
                enter = expandVertically(animationSpec = androidx.compose.animation.core.tween(180)) +
                    fadeIn(animationSpec = androidx.compose.animation.core.tween(150)),
                exit = shrinkVertically(animationSpec = androidx.compose.animation.core.tween(160)) +
                    fadeOut(animationSpec = androidx.compose.animation.core.tween(120)),
            ) {
                state.message?.let { NoticeCard(it) }
            }

            SettingsSection(
                title = "模型连接",
                subtitle = "Key 只在请求时从本机读取",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                        ) {
                            Icon(
                                Icons.Outlined.Key,
                                contentDescription = null,
                                modifier = Modifier.padding(9.dp),
                                tint = Coral,
                            )
                        }
                        Column {
                            Text("DeepSeek", fontWeight = FontWeight.SemiBold)
                            Text(
                                "OpenAI-compatible API",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    StatusPill(connected = state.hasApiKey)
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = state.keyDraft,
                    onValueChange = onKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("DeepSeek API Key") },
                    placeholder = {
                        Text(if (state.hasApiKey) "已安全保存，输入新 Key 可替换" else "sk-...")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.modelDraft,
                    onValueChange = onModelChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模型") },
                    supportingText = { Text("默认 deepseek-chat") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )

                AnimatedVisibility(
                    visible = state.error != null,
                    enter = expandVertically(
                        animationSpec = androidx.compose.animation.core.tween(180),
                    ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(140)),
                    exit = shrinkVertically(
                        animationSpec = androidx.compose.animation.core.tween(150),
                    ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(100)),
                ) {
                    state.error?.let {
                        StatusText(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onTestAndSave,
                    enabled = !state.isTesting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("正在测试…")
                    } else {
                        Text(if (state.hasApiKey) "测试并更新连接" else "测试并保存")
                    }
                }
            }

            AnimatedVisibility(
                visible = state.hasApiKey,
                enter = expandVertically(
                    animationSpec = androidx.compose.animation.core.tween(220),
                ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(180)),
                exit = shrinkVertically(
                    animationSpec = androidx.compose.animation.core.tween(180),
                ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(120)),
            ) {
                ConversationStyleSection(
                    style = state.conversationStyle,
                    onStyleChange = onStyleChange,
                )
            }

            if (state.hasApiKey) {
                SettingsSection(
                    title = "记忆",
                    subtitle = "短期摘要、长期事实和可检索的来源",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Psychology,
                            contentDescription = null,
                            tint = Coral,
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("自动整理记忆", fontWeight = FontWeight.SemiBold)
                            Text(
                                "回复后异步执行，会产生额外 API 调用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = autoMemoryEnabled,
                            onCheckedChange = onAutoMemoryChange,
                        )
                    }
                    TextButton(
                        onClick = onOpenMemory,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("查看和管理 $memoryCount 条记忆")
                    }
                }
            }

            if (state.hasApiKey) {
                SettingsSection(
                    title = "提醒",
                    subtitle = "本机定时通知，不让模型猜时间",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.NotificationsActive,
                            contentDescription = null,
                            tint = Coral,
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("定时提醒", fontWeight = FontWeight.SemiBold)
                            Text(
                                "提醒文字加密保存在本机，到点由系统通知",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onOpenReminders) {
                            Text("管理")
                        }
                    }
                }
            }

            if (state.hasApiKey) {
                SettingsSection(
                    title = "环境感知",
                    subtitle = "通知、当前应用、粗略城市和联网搜索",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Visibility,
                            contentDescription = null,
                            tint = Coral,
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("按来源开启", fontWeight = FontWeight.SemiBold)
                            Text(
                                "默认全部关闭，观察信息不会自动写入长期记忆",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onOpenPerception) {
                            Text("管理")
                        }
                    }
                }
            }

            if (state.hasApiKey) {
                SettingsSection(
                    title = "隐私与数据",
                    subtitle = "Key 和聊天记录是两类不同数据",
                ) {
                    ActionRow(
                        icon = Icons.Outlined.DeleteOutline,
                        title = "删除 API Key",
                        subtitle = "保留聊天记录，但暂停模型调用",
                        onClick = { showClearKeyConfirm = true },
                    )
                    ActionRow(
                        icon = Icons.Outlined.DeleteOutline,
                        title = "清空聊天记录",
                        subtitle = "删除本机全部消息，保留 Key 和手感设置",
                        onClick = { showClearChatConfirm = true },
                    )
                }
            }

            if (state.hasApiKey) {
                SettingsSection(
                    title = "关于",
                    subtitle = "个人自用版",
                ) {
                    InfoLine("版本", BuildConfig.VERSION_NAME)
                    InfoLine("模型调用", "HTTPS 直连 DeepSeek")
                    InfoLine("本地存储", "Room + SQLCipher")
                    Text(
                        "林是 AI，不是真实人类。公开运营前仍需完成账户、额度、合规和未成年人保护。",
                        modifier = Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showClearKeyConfirm) {
        ActionSheet(
            title = "删除 API Key？",
            body = "删除后无法继续聊天，需要重新填写 Key。聊天记录和手感设置会保留。",
            confirmLabel = "删除 Key",
            destructive = true,
            onConfirm = {
                showClearKeyConfirm = false
                onClearApiKey()
            },
            onDismiss = { showClearKeyConfirm = false },
        )
    }
    if (showClearChatConfirm) {
        ActionSheet(
            title = "清空聊天记录？",
            body = "本机全部聊天消息会被删除且无法恢复。API Key 和手感设置会保留。",
            confirmLabel = "清空聊天",
            destructive = true,
            onConfirm = {
                showClearChatConfirm = false
                onClearChatMessages()
            },
            onDismiss = { showClearChatConfirm = false },
        )
    }
}

@Composable
private fun WelcomeCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "先把连接放好",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "填写自己的 DeepSeek Key，测试成功后就可以开始聊天。Key 会使用 Android Keystore 加密保存在这台手机。",
                modifier = Modifier.padding(top = 7.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoticeCard(message: String) {
    Surface(
        color = Sage.copy(alpha = 0.12f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Sage,
        )
    }
}

@Composable
private fun ConversationStyleSection(
    style: ConversationStyle,
    onStyleChange: (Float, Float, ReplyLength) -> Unit,
) {
    var temperature by remember(style) {
        mutableFloatStateOf(style.temperature.toFloat())
    }
    var closeness by remember(style) {
        mutableFloatStateOf(style.closeness.toFloat())
    }
    var replyLength by remember(style) {
        mutableStateOf(style.replyLength)
    }

    SettingsSection(
        title = "对话手感",
        subtitle = "参数自动保存，下一轮消息生效",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = Coral)
                Text("随机性", fontWeight = FontWeight.SemiBold)
            }
            Text(
                String.format(Locale.US, "%.2f", temperature),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Slider(
            value = temperature,
            onValueChange = { temperature = it },
            onValueChangeFinished = {
                onStyleChange(temperature, closeness, replyLength)
            },
            valueRange = 0f..1.5f,
            steps = 29,
        )
        SliderLabels("稳定、少发散", "灵活、有变化")

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("亲近感", fontWeight = FontWeight.SemiBold)
            Text(
                "${closeness.roundToInt()} / 100",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Slider(
            value = closeness,
            onValueChange = { closeness = it },
            onValueChangeFinished = {
                onStyleChange(temperature, closeness, replyLength)
            },
            valueRange = 0f..100f,
            steps = 9,
        )
        SliderLabels("克制、直接", "温和、热络")

        Spacer(Modifier.height(20.dp))
        Text("回复长度", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReplyLength.entries.forEach { length ->
                FilterChip(
                    selected = replyLength == length,
                    onClick = {
                        replyLength = length
                        onStyleChange(temperature, closeness, length)
                    },
                    label = {
                        Text(
                            when (length) {
                                ReplyLength.SHORT -> "简短"
                                ReplyLength.NATURAL -> "适中"
                                ReplyLength.DETAILED -> "详细"
                            },
                        )
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = {
                    val default = ConversationStyle()
                    temperature = default.temperature.toFloat()
                    closeness = default.closeness.toFloat()
                    replyLength = default.replyLength
                    onStyleChange(temperature, closeness, replyLength)
                },
            ) {
                Text("恢复默认")
            }
        }
        Text(
            "随机性控制回答稳定程度，不代表情绪温度。想减少安慰和玩笑，请降低亲近感。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp),
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 9.dp),
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            shape = RoundedCornerShape(22.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun StatusPill(connected: Boolean) {
    Surface(
        color = if (connected) {
            Sage.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = CircleShape,
    ) {
        Text(
            if (connected) "Key 已保存" else "Key 未保存",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (connected) Sage else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Text(
        text,
        modifier = Modifier.padding(top = 9.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
private fun SliderLabels(start: String, end: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(start, style = MaterialTheme.typography.labelSmall)
        Text(end, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

