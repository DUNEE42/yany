package com.xinyu.companion.ui.perception

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xinyu.companion.domain.coroutines.IoDispatcher
import com.xinyu.companion.domain.perception.NotificationEvent
import com.xinyu.companion.domain.perception.NotificationObservationStore
import com.xinyu.companion.domain.perception.NotificationSource
import com.xinyu.companion.domain.perception.NotificationSourceSettings
import com.xinyu.companion.domain.perception.PerceptionAccessChecker
import com.xinyu.companion.domain.perception.PerceptionAccessStatus
import com.xinyu.companion.domain.perception.PerceptionSettings
import com.xinyu.companion.domain.perception.PerceptionSettingsStore
import com.xinyu.companion.domain.perception.WebSearchProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PerceptionUiState(
    val settings: PerceptionSettings = PerceptionSettings(),
    val access: PerceptionAccessStatus = PerceptionAccessStatus(false, false),
    val sources: List<NotificationSource> = emptyList(),
    val allowedPackages: Set<String> = emptySet(),
    val events: List<NotificationEvent> = emptyList(),
    val isTestingSearch: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

private data class PerceptionFeedback(
    val isTestingSearch: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

private data class PerceptionContent(
    val settings: PerceptionSettings,
    val allowedPackages: Set<String>,
    val sources: List<NotificationSource>,
    val events: List<NotificationEvent>,
    val access: PerceptionAccessStatus,
)

@HiltViewModel
class PerceptionViewModel @Inject constructor(
    private val settingsStore: PerceptionSettingsStore,
    private val sourceSettings: NotificationSourceSettings,
    private val eventStore: NotificationObservationStore,
    private val accessChecker: PerceptionAccessChecker,
    private val searchProvider: WebSearchProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val access = MutableStateFlow(accessChecker.status())
    private val feedback = MutableStateFlow(PerceptionFeedback())

    init {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                eventStore.pruneExpired(System.currentTimeMillis())
            }
        }
    }

    private val content = combine(
        settingsStore.settings,
        sourceSettings.allowedPackages,
        eventStore.observeSources(),
        eventStore.observeActiveEvents(),
        access,
    ) { settings, allowedPackages, sources, events, accessStatus ->
        PerceptionContent(
            settings = settings,
            allowedPackages = allowedPackages,
            sources = sources,
            events = events.filter {
                accessStatus.notificationAccess &&
                    allowedPackages.contains(it.sourcePackage)
            },
            access = accessStatus,
        )
    }

    val uiState: StateFlow<PerceptionUiState> = combine(content, feedback) {
            current, currentFeedback ->
        PerceptionUiState(
            settings = current.settings,
            allowedPackages = current.allowedPackages,
            sources = current.sources,
            events = current.events,
            access = current.access,
            isTestingSearch = currentFeedback.isTestingSearch,
            message = currentFeedback.message,
            error = currentFeedback.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PerceptionUiState(
            settings = settingsStore.load(),
            access = access.value,
            allowedPackages = sourceSettings.allowedPackages.value,
        ),
    )

    fun refreshAccess() {
        access.value = accessChecker.status()
    }

    fun setNotificationPackageAllowed(packageName: String, allowed: Boolean) {
        sourceSettings.setAllowed(packageName, allowed)
    }

    fun setForegroundAppEnabled(enabled: Boolean) {
        save(settingsStore.load().copy(foregroundAppEnabled = enabled))
    }

    fun setCoarseLocationEnabled(enabled: Boolean) {
        save(settingsStore.load().copy(coarseLocationEnabled = enabled))
    }

    fun setWebSearchEnabled(enabled: Boolean) {
        save(settingsStore.load().copy(webSearchEnabled = enabled))
    }

    fun saveSearxng(url: String, preferredCity: String) {
        val current = settingsStore.load()
        save(
            current.copy(
                searxngUrl = url,
                preferredCity = preferredCity,
            ),
        )
    }

    fun testSearch(url: String) {
        if (feedback.value.isTestingSearch) return
        val clean = url.trim().trimEnd('/')
        if (clean.isBlank()) {
            feedback.update { it.copy(error = "请先填写 SearXNG 地址") }
            return
        }
        viewModelScope.launch {
            feedback.update {
                it.copy(isTestingSearch = true, message = null, error = null)
            }
            val result = withContext(ioDispatcher) {
                searchProvider.search(clean, "OpenAI", maxResults = 1)
            }
            result.fold(
                onSuccess = {
                    feedback.update {
                        PerceptionFeedback(message = "搜索服务连接成功")
                    }
                },
                onFailure = {
                    feedback.update {
                        PerceptionFeedback(error = it.message ?: "搜索服务连接失败")
                    }
                },
            )
        }
    }

    fun clearEvents() {
        viewModelScope.launch {
            withContext(ioDispatcher) { eventStore.clearEvents() }
            feedback.update { it.copy(message = "近期观察记录已清空", error = null) }
        }
    }

    fun clearSources() {
        viewModelScope.launch {
            withContext(ioDispatcher) { eventStore.clearSources() }
            feedback.update { it.copy(message = "来源记录已清空", error = null) }
        }
    }

    fun deleteEvent(id: String) {
        viewModelScope.launch {
            withContext(ioDispatcher) { eventStore.deleteEvent(id) }
        }
    }

    private fun save(settings: PerceptionSettings) {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) { settingsStore.save(settings) }
            }.onFailure {
                feedback.update { feedback ->
                    feedback.copy(error = it.message ?: "无法保存观察设置")
                }
            }
        }
    }
}
