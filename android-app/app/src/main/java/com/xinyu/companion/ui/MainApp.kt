package com.xinyu.companion.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xinyu.companion.ui.chat.ChatScreen
import com.xinyu.companion.ui.chat.ChatViewModel
import com.xinyu.companion.ui.settings.SettingsScreen
import com.xinyu.companion.ui.memory.MemoryScreen
import com.xinyu.companion.ui.memory.MemoryViewModel
import com.xinyu.companion.ui.perception.PerceptionScreen
import com.xinyu.companion.ui.perception.PerceptionViewModel
import com.xinyu.companion.ui.reminder.ReminderScreen
import com.xinyu.companion.ui.reminder.ReminderViewModel

@Composable
fun MainApp() {
    val viewModel: ChatViewModel = hiltViewModel()
    val memoryViewModel: MemoryViewModel = hiltViewModel()
    val perceptionViewModel: PerceptionViewModel = hiltViewModel()
    val reminderViewModel: ReminderViewModel = hiltViewModel()
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle()
    val memoryState by memoryViewModel.uiState.collectAsStateWithLifecycle()
    val perceptionState by perceptionViewModel.uiState.collectAsStateWithLifecycle()
    val reminderState by reminderViewModel.uiState.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showMemory by rememberSaveable { mutableStateOf(false) }
    var showPerception by rememberSaveable { mutableStateOf(false) }
    var showReminders by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settingsState.loaded, settingsState.hasApiKey) {
        if (settingsState.loaded && !settingsState.hasApiKey) {
            showSettings = true
        }
    }

    val destination = when {
        !settingsState.loaded -> AppDestination.Loading
        showReminders -> AppDestination.Reminders
        showPerception -> AppDestination.Perception
        showMemory -> AppDestination.Memory
        showSettings -> AppDestination.Settings
        else -> AppDestination.Chat
    }

    AnimatedContent(
        targetState = destination,
        transitionSpec = {
            val forward = targetState.depth >= initialState.depth
            val enter = slideInHorizontally(
                animationSpec = tween(durationMillis = 260),
                initialOffsetX = { if (forward) it / 5 else -it / 8 },
            ) + fadeIn(animationSpec = tween(durationMillis = 180))
            val exit = slideOutHorizontally(
                animationSpec = tween(durationMillis = 220),
                targetOffsetX = { if (forward) -it / 12 else it / 5 },
            ) + fadeOut(animationSpec = tween(durationMillis = 150))
            (enter togetherWith exit).using(SizeTransform(clip = false))
        },
        label = "main-destination",
    ) { screen ->
        when (screen) {
            AppDestination.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            AppDestination.Memory -> {
                MemoryScreen(
                    state = memoryState,
                    onBack = { showMemory = false },
                    onAutoMemoryChange = memoryViewModel::setAutoMemoryEnabled,
                    onConsolidateNow = memoryViewModel::consolidateNow,
                    onAdd = memoryViewModel::addManualMemory,
                    onUpdate = memoryViewModel::updateMemory,
                    onConfirm = memoryViewModel::confirmMemory,
                    onReject = memoryViewModel::rejectMemory,
                    onDelete = memoryViewModel::deleteMemory,
                    onForgetRecent = memoryViewModel::forgetRecentThreeDays,
                    onDiscardPending = memoryViewModel::discardPendingCandidates,
                    onClearAll = memoryViewModel::clearAllMemories,
                )
            }

            AppDestination.Perception -> {
                PerceptionScreen(
                    state = perceptionState,
                    onBack = { showPerception = false },
                    onRefreshAccess = perceptionViewModel::refreshAccess,
                    onNotificationSourceChange =
                        perceptionViewModel::setNotificationPackageAllowed,
                    onForegroundAppChange =
                        perceptionViewModel::setForegroundAppEnabled,
                    onCoarseLocationChange =
                        perceptionViewModel::setCoarseLocationEnabled,
                    onWebSearchChange = perceptionViewModel::setWebSearchEnabled,
                    onSaveSearchConfiguration = perceptionViewModel::saveSearxng,
                    onTestSearch = perceptionViewModel::testSearch,
                    onDeleteEvent = perceptionViewModel::deleteEvent,
                    onClearEvents = perceptionViewModel::clearEvents,
                    onClearSources = perceptionViewModel::clearSources,
                )
            }

            AppDestination.Reminders -> {
                ReminderScreen(
                    state = reminderState,
                    onBack = { showReminders = false },
                    onAdd = reminderViewModel::add,
                    onCancel = reminderViewModel::cancel,
                    onRefresh = reminderViewModel::refresh,
                )
            }

            AppDestination.Settings -> {
                SettingsScreen(
                    state = settingsState,
                    onBack = {
                        if (settingsState.hasApiKey) showSettings = false
                    },
                    onKeyChange = viewModel::updateProviderKey,
                    onModelChange = viewModel::updateProviderModel,
                    onTestAndSave = viewModel::testAndSaveProvider,
                    onStyleChange = viewModel::saveConversationStyle,
                    autoMemoryEnabled = memoryState.autoMemoryEnabled,
                    memoryCount = memoryState.memories.size,
                    onAutoMemoryChange = memoryViewModel::setAutoMemoryEnabled,
                    onOpenMemory = { showMemory = true },
                    onOpenPerception = { showPerception = true },
                    onOpenReminders = { showReminders = true },
                    onClearApiKey = viewModel::clearApiKey,
                    onClearChatMessages = viewModel::clearChatMessages,
                )
            }

            AppDestination.Chat -> {
                ChatDestination(
                    viewModel = viewModel,
                    onOpenSettings = { showSettings = true },
                )
            }
        }
    }
}

private enum class AppDestination(val depth: Int) {
    Loading(1),
    Chat(2),
    Settings(3),
    Memory(4),
    Perception(5),
    Reminders(5),
}

@Composable
private fun ChatDestination(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
) {
    val chatState by viewModel.uiState.collectAsStateWithLifecycle()
    ChatScreen(
        state = chatState,
        companionName = viewModel.companionName,
        onInputChange = viewModel::updateInput,
        onSend = viewModel::send,
        onStop = viewModel::stop,
        onRetry = viewModel::retry,
        onOpenSettings = onOpenSettings,
        onSearchQueryChange = viewModel::updateSearchQuery,
        onConfirmSearch = viewModel::confirmSearchAndSend,
        onSendWithoutSearch = viewModel::sendWithoutSearch,
        onDismissSearchConfirmation = viewModel::dismissSearchConfirmation,
    )
}
