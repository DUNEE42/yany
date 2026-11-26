package com.xinyu.companion.ui.perception

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.xinyu.companion.data.notification.NotificationAccess
import com.xinyu.companion.domain.perception.NotificationEvent
import com.xinyu.companion.domain.perception.NotificationEventKind
import com.xinyu.companion.domain.perception.NotificationSource
import com.xinyu.companion.ui.components.IntroHeroCard
import com.xinyu.companion.ui.components.NoticeBanner
import com.xinyu.companion.ui.components.NoticeTone
import com.xinyu.companion.ui.components.StatusPill
import com.xinyu.companion.ui.components.StatusTone
import com.xinyu.companion.ui.components.SurfaceCard
import com.xinyu.companion.ui.components.ToggleRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerceptionScreen(
    state: PerceptionUiState,
    onBack: () -> Unit,
    onRefreshAccess: () -> Unit,
    onNotificationSourceChange: (String, Boolean) -> Unit,
    onForegroundAppChange: (Boolean) -> Unit,
    onCoarseLocationChange: (Boolean) -> Unit,
    onWebSearchChange: (Boolean) -> Unit,
    onSaveSearchConfiguration: (String, String) -> Unit,
    onTestSearch: (String) -> Unit,
    onDeleteEvent: (String) -> Unit,
    onClearEvents: () -> Unit,
    onClearSources: () -> Unit,
) {
    val context = LocalContext.current
    var searchUrl by rememberSaveable { mutableStateOf(state.settings.searxngUrl) }
    var preferredCity by rememberSaveable { mutableStateOf(state.settings.preferredCity) }
    BackHandler(onBack = onBack)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        onRefreshAccess()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("环境感知") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回设置")
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
                .navigationBarsPadding()
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                IntroHeroCard(
                    title = "让它看到一点上下文",
                    body = "观察默认关闭。你允许的来源只生成短期摘要；相关摘要会在当轮聊天时发送给 DeepSeek，不会自动变成长期记忆。",
                    footnote = "环境观察只在你发消息时参考。需要主动提醒时，请到“设置 → 提醒”明确设定时间；言语不会根据身份或通知内容猜课表。",
                    icon = Icons.Outlined.Visibility,
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
            item {
                PermissionCard(
                    state = state,
                    onOpenNotificationAccess = {
                        context.startActivity(
                            NotificationAccess.settingsIntent()
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    onOpenUsageAccess = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_USAGE_ACCESS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "通知来源",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp, top = 4.dp),
                    )
                    if (state.sources.isNotEmpty()) {
                        TextButton(onClick = onClearSources) { Text("清除来源记录") }
                    }
                }
                Text(
                    "默认全部关闭。只保留你主动允许的应用，包名以外不会保存通知正文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
            if (state.sources.isEmpty()) {
                item {
                    SurfaceCard {
                        Text(
                            if (state.access.notificationAccess) {
                                "暂时没有发现通知来源。收到通知后，这里会出现应用名称。"
                            } else {
                                "先开启通知使用权，再回到这里选择允许的来源。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(state.sources, key = NotificationSource::packageName) { source ->
                    SurfaceCard {
                        ToggleRow(
                            icon = Icons.Outlined.NotificationsActive,
                            title = source.appLabel,
                            subtitle = source.packageName,
                            checked = source.packageName in state.allowedPackages,
                            onCheckedChange = { onNotificationSourceChange(source.packageName, it) },
                        )
                    }
                }
            }
            item {
                SensorSettingsCard(
                    state = state,
                    preferredCity = preferredCity,
                    onPreferredCityChange = { preferredCity = it.take(80) },
                    onForegroundAppChange = onForegroundAppChange,
                    onCoarseLocationChange = onCoarseLocationChange,
                    onSaveCity = { onSaveSearchConfiguration(searchUrl, preferredCity) },
                )
            }
            item {
                SearchSettingsCard(
                    state = state,
                    searchUrl = searchUrl,
                    onSearchUrlChange = { searchUrl = it.trim().take(500) },
                    onWebSearchChange = onWebSearchChange,
                    onSave = { onSaveSearchConfiguration(searchUrl, preferredCity) },
                    onTest = {
                        onSaveSearchConfiguration(searchUrl, preferredCity)
                        onTestSearch(searchUrl)
                    },
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "近期观察 ${state.events.size} 条",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.events.isNotEmpty()) {
                        TextButton(onClick = onClearEvents) { Text("全部清除") }
                    }
                }
            }
            if (state.events.isEmpty()) {
                item {
                    Text(
                        "还没有近期观察。通知出现后，只会在这里保留 24–72 小时的脱敏摘要。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                    )
                }
            } else {
                items(state.events, key = NotificationEvent::id) { event ->
                    EventRow(event = event, onDelete = { onDeleteEvent(event.id) })
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    state: PerceptionUiState,
    onOpenNotificationAccess: () -> Unit,
    onOpenUsageAccess: () -> Unit,
) {
    SurfaceCard {
        Text("系统权限", fontWeight = FontWeight.SemiBold)
        Text(
            "这些权限由 Android 管理，言语不能绕过。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp, bottom = 10.dp),
        )
        PermissionRow(
            title = "通知使用权",
            active = state.access.notificationAccess,
            onAction = onOpenNotificationAccess,
        )
        PermissionRow(
            title = "使用情况访问",
            active = state.access.usageAccess,
            onAction = onOpenUsageAccess,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    active: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(
            label = if (active) "已授权" else "未授权",
            tone = if (active) StatusTone.POSITIVE else StatusTone.NEUTRAL,
        )
        Spacer(Modifier.size(10.dp))
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onAction) { Text(if (active) "已开启" else "去开启") }
    }
}

@Composable
private fun SensorSettingsCard(
    state: PerceptionUiState,
    preferredCity: String,
    onPreferredCityChange: (String) -> Unit,
    onForegroundAppChange: (Boolean) -> Unit,
    onCoarseLocationChange: (Boolean) -> Unit,
    onSaveCity: () -> Unit,
) {
    SurfaceCard {
        ToggleRow(
            icon = Icons.Outlined.Visibility,
            title = "当前前台应用",
            subtitle = "只在你询问时读取应用名称，不读取其他应用内容",
            checked = state.settings.foregroundAppEnabled,
            onCheckedChange = onForegroundAppChange,
        )
        Spacer(Modifier.height(12.dp))
        ToggleRow(
            icon = Icons.Outlined.LocationOn,
            title = "粗粒度网络位置",
            subtitle = "仅在天气需要时由 ipwho.is 估算城市；不保存原始 IP，城市会交给 Open-Meteo",
            checked = state.settings.coarseLocationEnabled,
            onCheckedChange = onCoarseLocationChange,
        )
        OutlinedTextField(
            value = preferredCity,
            onValueChange = onPreferredCityChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            label = { Text("常用城市（优先于 IP 推测）") },
            singleLine = true,
        )
        TextButton(
            onClick = onSaveCity,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("保存城市及搜索设置")
        }
    }
}

@Composable
private fun SearchSettingsCard(
    state: PerceptionUiState,
    searchUrl: String,
    onSearchUrlChange: (String) -> Unit,
    onWebSearchChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    SurfaceCard {
        ToggleRow(
            icon = Icons.Outlined.Search,
            title = "联网搜索",
            subtitle = "搜索词会发送到你填写的 SearXNG，最多返回 5 条结果",
            checked = state.settings.webSearchEnabled,
            onCheckedChange = onWebSearchChange,
        )
        OutlinedTextField(
            value = searchUrl,
            onValueChange = onSearchUrlChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            label = { Text("SearXNG HTTPS 地址") },
            placeholder = { Text("https://your-searx.example.com") },
            singleLine = true,
        )
        Text(
            "言语不上传 SearXNG Key。JSON 输出需要由你的实例开启。",
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onSave) { Text("保存") }
            Button(onClick = onTest, enabled = !state.isTestingSearch) {
                if (state.isTestingSearch) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(7.dp))
                    Text("测试中")
                } else {
                    Text("测试连接")
                }
            }
        }
    }
}

@Composable
private fun EventRow(
    event: NotificationEvent,
    onDelete: () -> Unit,
) {
    SurfaceCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = when (event.kind) {
                    NotificationEventKind.PARCEL_READY -> Icons.Outlined.CloudQueue
                    else -> Icons.Outlined.NotificationsActive
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    event.sourceAppLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(event.summary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${isoDate(event.observedAt)} · ${event.kind.label()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除观察")
            }
        }
    }
}

private fun NotificationEventKind.label(): String = when (this) {
    NotificationEventKind.PARCEL_READY -> "包裹"
    NotificationEventKind.COURSE_UPDATE -> "课程"
    NotificationEventKind.CALENDAR_UPDATE -> "日程"
    NotificationEventKind.GENERIC -> "通知"
}

private fun isoDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ISO_LOCAL_DATE)
