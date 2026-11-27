package com.xinyu.companion.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xinyu.companion.ui.chat.ChatScreen
import com.xinyu.companion.ui.chat.ChatViewModel
import com.xinyu.companion.ui.memory.MemoryScreen
import com.xinyu.companion.ui.memory.MemoryViewModel
import com.xinyu.companion.ui.perception.PerceptionScreen
import com.xinyu.companion.ui.perception.PerceptionViewModel
import com.xinyu.companion.ui.reminder.ReminderScreen
import com.xinyu.companion.ui.reminder.ReminderViewModel
import com.xinyu.companion.ui.settings.SettingsScreen
import com.xinyu.companion.ui.settings.SettingsViewModel

private object AppRoute {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val MEMORY = "memory"
    const val PERCEPTION = "perception"
    const val REMINDERS = "reminders"
}

@Composable
fun MainApp() {
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()

    if (!settingsState.loaded) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val navController = rememberNavController()
    val startDestination = remember {
        if (settingsState.hasApiKey) AppRoute.CHAT else AppRoute.SETTINGS
    }

    LaunchedEffect(settingsState.hasApiKey) {
        if (!settingsState.hasApiKey &&
            navController.currentDestination?.route != AppRoute.SETTINGS
        ) {
            navController.navigate(AppRoute.SETTINGS) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideInHorizontally(
                animationSpec = tween(durationMillis = 250),
                initialOffsetX = { it / 5 },
            ) + fadeIn(animationSpec = tween(durationMillis = 180))
        },
        exitTransition = {
            slideOutHorizontally(
                animationSpec = tween(durationMillis = 220),
                targetOffsetX = { -it / 12 },
            ) + fadeOut(animationSpec = tween(durationMillis = 150))
        },
        popEnterTransition = {
            slideInHorizontally(
                animationSpec = tween(durationMillis = 240),
                initialOffsetX = { -it / 8 },
            ) + fadeIn(animationSpec = tween(durationMillis = 180))
        },
        popExitTransition = {
            slideOutHorizontally(
                animationSpec = tween(durationMillis = 220),
                targetOffsetX = { it / 5 },
            ) + fadeOut(animationSpec = tween(durationMillis = 150))
        },
    ) {
        composable(AppRoute.CHAT) {
            ChatDestination(
                onOpenSettings = {
                    navController.navigate(AppRoute.SETTINGS) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(AppRoute.SETTINGS) {
            val state by settingsViewModel.uiState.collectAsStateWithLifecycle()
            SettingsScreen(
                state = state,
                onBack = {
                    if (state.hasApiKey) {
                        if (!navController.popBackStack()) {
                            navController.navigate(AppRoute.CHAT) {
                                popUpTo(AppRoute.SETTINGS) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                },
                onKeyChange = settingsViewModel::updateProviderKey,
                onModelChange = settingsViewModel::updateProviderModel,
                onTestAndSave = settingsViewModel::testAndSaveProvider,
                onStyleChange = settingsViewModel::saveConversationStyle,
                autoMemoryEnabled = state.autoMemoryEnabled,
                memoryCount = state.memoryCount,
                onAutoMemoryChange = settingsViewModel::setAutoMemoryEnabled,
                onOpenMemory = {
                    navController.navigate(AppRoute.MEMORY) { launchSingleTop = true }
                },
                onOpenPerception = {
                    navController.navigate(AppRoute.PERCEPTION) { launchSingleTop = true }
                },
                onOpenReminders = {
                    navController.navigate(AppRoute.REMINDERS) { launchSingleTop = true }
                },
                onClearApiKey = settingsViewModel::clearApiKey,
                onClearChatMessages = settingsViewModel::clearChatMessages,
                onCompanionNameChange = settingsViewModel::updateCompanionNameDraft,
                onSaveCompanionName = settingsViewModel::saveCompanionName,
                onPickCompanionAvatar = settingsViewModel::updateCompanionAvatar,
                onResetCompanionAvatar = settingsViewModel::resetCompanionAvatar,
            )
        }

        composable(AppRoute.MEMORY) {
            MemoryDestination(onBack = { navController.popBackStack() })
        }

        composable(AppRoute.PERCEPTION) {
            PerceptionDestination(onBack = { navController.popBackStack() })
        }

        composable(AppRoute.REMINDERS) {
            ReminderDestination(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun ChatDestination(
    onOpenSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val chatState by viewModel.uiState.collectAsStateWithLifecycle()
    ChatScreen(
        state = chatState,
        companionName = chatState.companionName,
        companionAvatarPath = chatState.companionAvatarPath,
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

@Composable
private fun MemoryDestination(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MemoryScreen(
        state = state,
        onBack = onBack,
        onAutoMemoryChange = viewModel::setAutoMemoryEnabled,
        onConsolidateNow = viewModel::consolidateNow,
        onAdd = viewModel::addManualMemory,
        onUpdate = viewModel::updateMemory,
        onConfirm = viewModel::confirmMemory,
        onReject = viewModel::rejectMemory,
        onDelete = viewModel::deleteMemory,
        onForgetRecent = viewModel::forgetRecentThreeDays,
        onDiscardPending = viewModel::discardPendingCandidates,
        onClearAll = viewModel::clearAllMemories,
    )
}

@Composable
private fun PerceptionDestination(
    onBack: () -> Unit,
    viewModel: PerceptionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PerceptionScreen(
        state = state,
        onBack = onBack,
        onRefreshAccess = viewModel::refreshAccess,
        onNotificationSourceChange = viewModel::setNotificationPackageAllowed,
        onForegroundAppChange = viewModel::setForegroundAppEnabled,
        onCoarseLocationChange = viewModel::setCoarseLocationEnabled,
        onWebSearchChange = viewModel::setWebSearchEnabled,
        onSaveSearchConfiguration = viewModel::saveSearxng,
        onTestSearch = viewModel::testSearch,
        onDeleteEvent = viewModel::deleteEvent,
        onClearEvents = viewModel::clearEvents,
        onClearSources = viewModel::clearSources,
    )
}

@Composable
private fun ReminderDestination(
    onBack: () -> Unit,
    viewModel: ReminderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReminderScreen(
        state = state,
        onBack = onBack,
        onAdd = viewModel::add,
        onCancel = viewModel::cancel,
        onRefresh = viewModel::refresh,
    )
}
