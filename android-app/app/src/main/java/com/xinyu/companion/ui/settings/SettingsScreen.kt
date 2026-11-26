package com.xinyu.companion.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.ConversationStyle
import com.xinyu.companion.domain.ReplyLength
import com.xinyu.companion.ui.components.ActionRow
import com.xinyu.companion.ui.components.ActionSheet
import com.xinyu.companion.ui.components.CompanionAvatar
import com.xinyu.companion.ui.components.InfoLine
import com.xinyu.companion.ui.components.IntroHeroCard
import com.xinyu.companion.ui.components.NoticeBanner
import com.xinyu.companion.ui.components.NoticeTone
import com.xinyu.companion.ui.components.SectionContainer
import com.xinyu.companion.ui.components.StatusPill
import com.xinyu.companion.ui.components.StatusTone
import com.xinyu.companion.ui.components.ToggleRow
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
    autoMemoryEnabled: Boolean = state.autoMemoryEnabled,
    memoryCount: Int = state.memoryCount,
    onAutoMemoryChange: (Boolean) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenPerception: () -> Unit,
    onOpenReminders: () -> Unit = {},
    onClearApiKey: () -> Unit,
    onClearChatMessages: () -> Unit,
    onCompanionNameChange: (String) -> Unit = {},
    onSaveCompanionName: () -> Unit = {},
    onPickCompanionAvatar: (Uri) -> Unit = {},
    onResetCompanionAvatar: () -> Unit = {},
) {
    var showClearKeyConfirm by remember { mutableStateOf(false) }
    var showClearChatConfirm by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onPickCompanionAvatar(uri)
    }
    val canLeave = state.hasApiKey
    BackHandler(enabled = canLeave, onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (state.hasApiKey) "设置" else "连接 DeepSeek") },
                navigationIcon = {
                    if (canLeave) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回聊天")
                        }
                    }
                },
                actions = {
                    if (canLeave) {
                        TextButton(onClick = onBack) { Text("开始聊天") }
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
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (!state.hasApiKey) {
                IntroHeroCard(
                    title = "先把连接放好",
                    body = "填写自己的 DeepSeek Key，测试成功后就可以开始聊天。Key 会使用 Android Keystore 加密保存在这台手机。",
                )
            }
            AnimatedVisibility(
                visible = state.message != null,
                enter = expandVertically(tween(180)) + fadeIn(tween(150)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
            ) {
                state.message?.let { NoticeBanner(text = it, tone = NoticeTone.SUCCESS) }
            }

            ProviderConnectionSection(
                state = state,
                onKeyChange = onKeyChange,
                onModelChange = onModelChange,
                onTestAndSave = onTestAndSave,
            )

            AnimatedVisibility(
                visible = state.hasApiKey,
                enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    CompanionProfileSection(
                        profile = state.companionProfile,
                        nameDraft = state.companionNameDraft,
                        isUpdatingAvatar = state.isUpdatingAvatar,
                        onNameChange = onCompanionNameChange,
                        onSaveName = onSaveCompanionName,
                        onPickAvatar = {
                            photoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        onResetAvatar = onResetCompanionAvatar,
                    )

                    ConversationStyleSection(
                        style = state.conversationStyle,
                        onStyleChange = onStyleChange,
                    )

                    SectionContainer(
                        title = "记忆",
                        subtitle = "短期摘要、长期事实和可检索的来源",
                    ) {
                        ToggleRow(
                            icon = Icons.Outlined.Psychology,
                            title = "自动整理记忆",
                            subtitle = "回复后异步执行，会产生额外 API 调用",
                            checked = autoMemoryEnabled,
                            onCheckedChange = onAutoMemoryChange,
                        )
                        TextButton(
                            onClick = onOpenMemory,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("查看和管理 $memoryCount 条记忆")
                        }
                    }

                    SectionContainer(
                        title = "提醒",
                        subtitle = "本机定时通知，不让模型猜时间",
                    ) {
                        NavigationEntryRow(
                            icon = Icons.Outlined.NotificationsActive,
                            title = "定时提醒",
                            subtitle = "提醒文字加密保存在本机，到点由系统通知",
                            onClick = onOpenReminders,
                        )
                    }

                    SectionContainer(
                        title = "环境感知",
                        subtitle = "通知、当前应用、粗略城市和联网搜索",
                    ) {
                        NavigationEntryRow(
                            icon = Icons.Outlined.Visibility,
                            title = "按来源开启",
                            subtitle = "默认全部关闭，观察信息不会自动写入长期记忆",
                            onClick = onOpenPerception,
                        )
                    }

                    SectionContainer(
                        title = "隐私与数据",
                        subtitle = "Key 和聊天记录是两类不同数据",
                    ) {
                        ActionRow(
                            icon = Icons.Outlined.DeleteOutline,
                            title = "删除 API Key",
                            subtitle = "保留聊天记录，但暂停模型调用",
                            destructive = true,
                            onClick = { showClearKeyConfirm = true },
                        )
                        ActionRow(
                            icon = Icons.Outlined.DeleteOutline,
                            title = "清空聊天记录",
                            subtitle = "删除本机全部消息，保留 Key 和手感设置",
                            destructive = true,
                            onClick = { showClearChatConfirm = true },
                        )
                    }

                    SectionContainer(
                        title = "关于",
                        subtitle = "个人自用版",
                    ) {
                        InfoLine("版本", BuildConfig.VERSION_NAME)
                        InfoLine("模型调用", "HTTPS 直连 DeepSeek")
                        InfoLine("本地存储", "Room + SQLCipher")
                        Text(
                            "${state.companionProfile.displayName}是 AI，不是真实人类。公开运营前仍需完成账户、额度、合规和未成年人保护。",
                            modifier = Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
private fun ProviderConnectionSection(
    state: SettingsUiState,
    onKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onTestAndSave: () -> Unit,
) {
    SectionContainer(
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
                        tint = MaterialTheme.colorScheme.primary,
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
            StatusPill(
                label = if (state.hasApiKey) "Key 已保存" else "Key 未保存",
                tone = if (state.hasApiKey) StatusTone.POSITIVE else StatusTone.NEUTRAL,
            )
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
            enter = expandVertically(tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(100)),
        ) {
            state.error?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 9.dp),
                    style = MaterialTheme.typography.bodySmall,
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
}

@Composable
private fun CompanionProfileSection(
    profile: CompanionProfile,
    nameDraft: String,
    isUpdatingAvatar: Boolean,
    onNameChange: (String) -> Unit,
    onSaveName: () -> Unit,
    onPickAvatar: () -> Unit,
    onResetAvatar: () -> Unit,
) {
    SectionContainer(
        title = "伴侣资料",
        subtitle = "头像仅保存在本机应用私有目录，不会上传",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CompanionAvatar(
                name = nameDraft.ifBlank { profile.displayName },
                avatarPath = profile.avatarPath,
                size = 60.dp,
                prominent = true,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onPickAvatar,
                        enabled = !isUpdatingAvatar,
                    ) {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isUpdatingAvatar) "处理中…" else "更换头像")
                    }
                    if (!profile.avatarPath.isNullOrBlank()) {
                        TextButton(
                            onClick = onResetAvatar,
                            enabled = !isUpdatingAvatar,
                        ) {
                            Text("恢复默认")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = nameDraft,
                onValueChange = onNameChange,
                modifier = Modifier.weight(1f),
                label = { Text("AI 昵称") },
                placeholder = { Text(CompanionProfile.DEFAULT_NAME) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSaveName() }),
            )
            Button(
                onClick = onSaveName,
                enabled = nameDraft.trim().isNotBlank() &&
                    nameDraft.trim() != profile.displayName,
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun NavigationEntryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onClick) { Text("管理") }
    }
}

@Composable
private fun ConversationStyleSection(
    style: ConversationStyle,
    onStyleChange: (Float, Float, ReplyLength) -> Unit,
) {
    var temperature by remember(style) { mutableFloatStateOf(style.temperature.toFloat()) }
    var closeness by remember(style) { mutableFloatStateOf(style.closeness.toFloat()) }
    var replyLength by remember(style) { mutableStateOf(style.replyLength) }

    SectionContainer(
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
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
            onValueChangeFinished = { onStyleChange(temperature, closeness, replyLength) },
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
            onValueChangeFinished = { onStyleChange(temperature, closeness, replyLength) },
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
private fun SliderLabels(start: String, end: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            start,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            end,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
