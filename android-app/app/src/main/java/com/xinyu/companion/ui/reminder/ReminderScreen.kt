package com.xinyu.companion.ui.reminder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xinyu.companion.data.reminder.ReminderNotifications
import com.xinyu.companion.domain.reminder.ReminderPolicy
import com.xinyu.companion.domain.reminder.ReminderRule
import com.xinyu.companion.ui.components.IntroHeroCard
import com.xinyu.companion.ui.components.NoticeBanner
import com.xinyu.companion.ui.components.NoticeTone
import com.xinyu.companion.ui.components.SurfaceCard
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object ReminderDateTimeMapper {
    fun localDateToPickerUtcMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun pickerUtcMillisToLocalDate(utcMillis: Long): LocalDate =
        Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

    fun combineLocalDateTime(
        date: LocalDate,
        hour: Int,
        minute: Int,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long = date.atTime(hour, minute).atZone(zoneId).toInstant().toEpochMilli()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderScreen(
    state: ReminderUiState,
    onBack: () -> Unit,
    onAdd: (String, Long) -> Unit,
    onCancel: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsEnabled by remember {
        mutableStateOf(ReminderNotifications.enabled(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationsEnabled = ReminderNotifications.enabled(context)
        if (!notificationsEnabled) openNotificationSettings(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = ReminderNotifications.enabled(context)
                onRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val initial = remember {
        LocalDateTime.now()
            .plusHours(1)
            .withSecond(0)
            .withNano(0)
    }
    var message by rememberSaveable { mutableStateOf("") }
    var selectedDateUtcMillis by rememberSaveable {
        mutableLongStateOf(ReminderDateTimeMapper.localDateToPickerUtcMillis(initial.toLocalDate()))
    }
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }

    val selectedDate = ReminderDateTimeMapper.pickerUtcMillisToLocalDate(selectedDateUtcMillis)
    val selectedEpochMillis = ReminderDateTimeMapper.combineLocalDateTime(selectedDate, hour, minute)

    fun enableNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings(context)
        }
    }

    fun saveReminder() {
        val error = ReminderPolicy.validate(
            message = message,
            triggerAtMillis = selectedEpochMillis,
            nowMillis = System.currentTimeMillis(),
            existingCount = state.rules.size,
        )
        validationError = when {
            !notificationsEnabled -> "请先开启通知权限，否则到点无法提醒"
            else -> error
        }
        if (validationError == null) {
            onAdd(message, selectedEpochMillis)
            message = ""
        }
    }

    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("提醒") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IntroHeroCard(
                title = "把时间交给规则，到点让${state.companionName}叫你",
                body = "提醒文字加密保存在本机，不上传。到点由 Android 发出系统通知；省电模式下可能晚几分钟。",
            )

            if (!notificationsEnabled) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("系统通知还没有开启", fontWeight = FontWeight.SemiBold)
                        Text(
                            "不开通知，言语就无法在后台发出系统提醒。",
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = ::enableNotifications) { Text("开启通知") }
                    }
                }
            }

            SurfaceCard(contentPadding = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                        ) {
                            Icon(
                                Icons.Outlined.NotificationsActive,
                                contentDescription = null,
                                modifier = Modifier.padding(8.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column {
                            Text("新建提醒", fontWeight = FontWeight.SemiBold)
                            Text(
                                "不会让模型猜时间",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = message,
                        onValueChange = {
                            message = it.take(ReminderPolicy.MAX_MESSAGE_LENGTH)
                            validationError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("提醒我什么") },
                        placeholder = { Text("例如：17:30 去驿站取快递") },
                        minLines = 2,
                        maxLines = 4,
                        supportingText = {
                            Text("${message.length} / ${ReminderPolicy.MAX_MESSAGE_LENGTH}")
                        },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showDatePicker = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Event, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(formatDate(selectedDate))
                        }
                        OutlinedButton(
                            onClick = { showTimePicker = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.AccessTime, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(String.format(Locale.US, "%02d:%02d", hour, minute))
                        }
                    }

                    AnimatedVisibility(validationError != null) {
                        Text(
                            validationError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Button(
                        onClick = ::saveReminder,
                        enabled = !state.isSaving && notificationsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("正在保存…")
                        } else {
                            Text("保存提醒")
                        }
                    }
                }
            }

            val feedback = state.error ?: state.message
            AnimatedVisibility(visible = feedback != null) {
                NoticeBanner(
                    text = feedback.orEmpty(),
                    tone = if (state.error != null) NoticeTone.ERROR else NoticeTone.SUCCESS,
                )
            }

            Text(
                "待处理",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else if (state.rules.isEmpty()) {
                SurfaceCard(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    elevation = 0.dp,
                    border = null,
                ) {
                    Text(
                        "还没有提醒。它不会根据身份或习惯替你猜一个时间。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                state.rules.forEach { rule ->
                    ReminderRuleCard(
                        rule = rule,
                        overdue = rule.triggerAtMillis <= System.currentTimeMillis(),
                        onCancel = { onCancel(rule.id) },
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDateUtcMillis,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            selectedDateUtcMillis = it
                            validationError = null
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择提醒时间") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        hour = timePickerState.hour
                        minute = timePickerState.minute
                        validationError = null
                        showTimePicker = false
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ReminderRuleCard(
    rule: ReminderRule,
    overdue: Boolean,
    onCancel: () -> Unit,
) {
    SurfaceCard(
        containerColor = if (overdue) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(
                        if (overdue) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        CircleShape,
                    ),
            )
            Column(Modifier.weight(1f)) {
                Text(rule.message, fontWeight = FontWeight.Medium)
                Text(
                    if (overdue) {
                        "${formatDateTime(rule.triggerAtMillis)} · 时间已过，未确认送达"
                    } else {
                        formatDateTime(rule.triggerAtMillis)
                    },
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "删除提醒",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun openNotificationSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun formatDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("M/d E", Locale.SIMPLIFIED_CHINESE))

private fun formatDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M月d日 E HH:mm", Locale.SIMPLIFIED_CHINESE))
