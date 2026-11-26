package com.xinyu.companion.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.ConversationIds
import com.xinyu.companion.domain.coroutines.IoDispatcher
import com.xinyu.companion.domain.memory.ConversationSummary
import com.xinyu.companion.domain.memory.LongTermMemory
import com.xinyu.companion.domain.memory.ManualMemoryInput
import com.xinyu.companion.domain.memory.MemoryJob
import com.xinyu.companion.domain.memory.MemoryKind
import com.xinyu.companion.domain.memory.MemoryPolicy
import com.xinyu.companion.domain.memory.MemorySettingsStore
import com.xinyu.companion.domain.memory.MemoryStore
import com.xinyu.companion.domain.memory.MemoryWorkCoordinator
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

data class MemoryUiState(
    val autoMemoryEnabled: Boolean = true,
    val isProcessing: Boolean = false,
    val memories: List<LongTermMemory> = emptyList(),
    val latestSummary: ConversationSummary? = null,
    val latestJob: MemoryJob? = null,
    val message: String? = null,
    val error: String? = null,
)

private data class MemoryFeedback(
    val message: String? = null,
    val error: String? = null,
)

private data class MemoryContent(
    val autoMemoryEnabled: Boolean,
    val isProcessing: Boolean,
    val memories: List<LongTermMemory>,
    val latestSummary: ConversationSummary?,
    val latestJob: MemoryJob?,
)

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val store: MemoryStore,
    private val settings: MemorySettingsStore,
    private val coordinator: MemoryWorkCoordinator,
    private val companionProfileStore: com.xinyu.companion.domain.settings.CompanionProfileStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val companion: CompanionProfile
        get() = companionProfileStore.profile.value
    private val feedback = MutableStateFlow(MemoryFeedback())

    private val content = combine(
        store.observeMemories(CompanionProfile.MAIN_COMPANION_ID),
        store.observeLatestSummary(ConversationIds.DEFAULT),
        store.observeLatestJob(ConversationIds.DEFAULT),
        settings.autoMemoryEnabled,
        coordinator.isProcessing,
    ) { memories, summary, job, autoEnabled, processing ->
        MemoryContent(
            autoMemoryEnabled = autoEnabled,
            isProcessing = processing,
            memories = memories,
            latestSummary = summary,
            latestJob = job,
        )
    }

    val uiState: StateFlow<MemoryUiState> = combine(content, feedback) {
            currentContent, currentFeedback ->
        MemoryUiState(
            autoMemoryEnabled = currentContent.autoMemoryEnabled,
            isProcessing = currentContent.isProcessing,
            memories = currentContent.memories,
            latestSummary = currentContent.latestSummary?.let { summary ->
                val sanitized = MemoryPolicy.sanitizeSummary(summary.content)
                if (sanitized.isBlank()) null else summary.copy(content = sanitized)
            },
            latestJob = currentContent.latestJob,
            message = currentFeedback.message,
            error = currentFeedback.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MemoryUiState(),
    )

    init {
        coordinator.start(ConversationIds.DEFAULT, companion)
    }

    fun setAutoMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    settings.setAutoMemoryEnabled(enabled)
                }
            }.onSuccess {
                feedback.update {
                    it.copy(
                        message = if (enabled) "自动记忆已开启" else "自动记忆已关闭",
                        error = null,
                    )
                }
            }.onFailure { error ->
                feedback.update {
                    it.copy(error = error.message ?: "无法保存记忆设置")
                }
            }
        }
    }

    fun consolidateNow() {
        coordinator.consolidateNow(ConversationIds.DEFAULT, companion)
        feedback.update {
            it.copy(message = "已开始整理最近的对话", error = null)
        }
    }

    fun addManualMemory(kind: MemoryKind, statement: String) {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    store.addManualMemory(
                        ManualMemoryInput(
                            companionId = companion.id,
                            kind = kind,
                            statement = statement,
                            importance = 4,
                        ),
                    )
                }
            }.onSuccess {
                feedback.update {
                    it.copy(message = "记忆已保存", error = null)
                }
            }.onFailure { error ->
                feedback.update {
                    it.copy(error = error.message ?: "无法保存记忆")
                }
            }
        }
    }

    fun updateMemory(id: String, statement: String) {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) { store.updateMemoryStatement(id, statement) }
            }.onSuccess {
                feedback.update { it.copy(message = "记忆已修改", error = null) }
            }.onFailure { error ->
                feedback.update { it.copy(error = error.message ?: "无法修改记忆") }
            }
        }
    }

    fun confirmMemory(id: String) {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) { store.confirmMemory(id) }
            }.onSuccess {
                feedback.update { it.copy(message = "已确认这条记忆", error = null) }
            }.onFailure { error ->
                feedback.update { it.copy(error = error.message ?: "无法确认记忆") }
            }
        }
    }

    fun rejectMemory(id: String) {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) { store.rejectMemory(id) }
            }.onSuccess {
                feedback.update { it.copy(message = "已拒绝这条记忆", error = null) }
            }.onFailure { error ->
                feedback.update { it.copy(error = error.message ?: "无法拒绝记忆") }
            }
        }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) { store.deleteMemory(id) }
            }.onSuccess {
                feedback.update { it.copy(message = "记忆已删除", error = null) }
            }.onFailure { error ->
                feedback.update { it.copy(error = error.message ?: "无法删除记忆") }
            }
        }
    }

    fun forgetRecentThreeDays() {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    store.forgetObservedSince(
                        companionId = companion.id,
                        since = System.currentTimeMillis() - THREE_DAYS_MS,
                    )
                }
            }.onSuccess {
                feedback.update { it.copy(message = "最近三天的记忆已删除", error = null) }
            }.onFailure { error ->
                feedback.update { it.copy(error = error.message ?: "无法删除近期记忆") }
            }
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    store.clearAllMemories(ConversationIds.DEFAULT)
                    settings.setAutoMemoryEnabled(false)
                }
            }.onSuccess {
                feedback.update {
                    it.copy(
                        message = "记忆已清空，自动记忆已暂停；需要时会从新的对话继续整理",
                        error = null,
                    )
                }
            }.onFailure { error ->
                feedback.update { it.copy(error = error.message ?: "无法清空记忆") }
            }
        }
    }

    fun discardPendingCandidates() {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    store.discardPendingCandidates(companion.id)
                }
            }.onSuccess {
                feedback.update {
                    it.copy(message = "待确认的记忆已清理", error = null)
                }
            }.onFailure { error ->
                feedback.update {
                    it.copy(error = error.message ?: "无法清理待确认记忆")
                }
            }
        }
    }

    private companion object {
        const val THREE_DAYS_MS = 3L * 24L * 60L * 60L * 1_000L
    }
}

