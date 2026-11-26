package com.xinyu.companion.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xinyu.companion.domain.ChatEngine
import com.xinyu.companion.domain.ChatMessage
import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.ConversationIds
import com.xinyu.companion.domain.coroutines.IoDispatcher
import com.xinyu.companion.domain.memory.MemoryWorkCoordinator
import com.xinyu.companion.domain.perception.PerceptionQueryPolicy
import com.xinyu.companion.domain.perception.PerceptionSettingsStore
import com.xinyu.companion.domain.repository.ChatConversationStore
import com.xinyu.companion.domain.settings.CompanionProfileStore
import com.xinyu.companion.domain.settings.ProviderSettingsStore
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

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isStreaming: Boolean = false,
    val error: String? = null,
    val safetyLevel: String = "normal",
    val canRetry: Boolean = false,
    val hasApiKey: Boolean = false,
    val isConversationReady: Boolean = false,
    val liveContextLabel: String? = null,
    val searchConfirmation: SearchConfirmationUiState? = null,
    val companionName: String = CompanionProfile.DEFAULT_NAME,
    val companionAvatarPath: String? = null,
)

data class SearchConfirmationUiState(
    val originalMessage: String,
    val query: String,
    val error: String? = null,
)

private data class InteractionState(
    val input: String = "",
    val conversationReady: Boolean = false,
    val conversationError: String? = null,
    val searchConfirmation: SearchConfirmationUiState? = null,
    val toolSearchDraft: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val store: ChatConversationStore,
    private val apiSettingsStore: ProviderSettingsStore,
    private val companionProfileStore: CompanionProfileStore,
    private val chatEngine: ChatEngine,
    private val memoryCoordinator: MemoryWorkCoordinator,
    private val perceptionSettingsStore: PerceptionSettingsStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val interaction = MutableStateFlow(InteractionState())

    val uiState: StateFlow<ChatUiState> = combine(
        store.observeMessages(ConversationIds.DEFAULT),
        interaction,
        chatEngine.state,
        apiSettingsStore.configured,
        companionProfileStore.profile,
    ) { messages, local, engine, hasApiKey, companion ->
        val effectiveSearchConfirmation = engine.pendingToolSearch?.let { pending ->
            SearchConfirmationUiState(
                originalMessage = "",
                query = local.toolSearchDraft ?: pending.suggestedQuery,
                error = pending.error,
            )
        } ?: local.searchConfirmation
        ChatUiState(
            messages = messages,
            input = local.input,
            isStreaming = engine.isStreaming,
            error = engine.error ?: local.conversationError,
            safetyLevel = engine.safetyLevel,
            canRetry = engine.retryableAssistantMessageId != null,
            hasApiKey = hasApiKey,
            isConversationReady = local.conversationReady,
            liveContextLabel = engine.liveContextLabel,
            searchConfirmation = effectiveSearchConfirmation,
            companionName = companion.displayName,
            companionAvatarPath = companion.avatarPath,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState(
            hasApiKey = apiSettingsStore.configured.value,
            companionName = companionProfileStore.profile.value.displayName,
            companionAvatarPath = companionProfileStore.profile.value.avatarPath,
        ),
    )

    init {
        viewModelScope.launch {
            withContext(ioDispatcher) { apiSettingsStore.load() }
        }
        viewModelScope.launch {
            runCatching {
                val companion = withContext(ioDispatcher) {
                    val profile = companionProfileStore.load()
                    store.ensureSeeded(ConversationIds.DEFAULT, profile)
                    profile
                }
                companion
            }.onSuccess { companion ->
                memoryCoordinator.start(ConversationIds.DEFAULT, companion)
                interaction.update {
                    it.copy(conversationReady = true, conversationError = null)
                }
            }.onFailure { error ->
                interaction.update {
                    it.copy(
                        conversationReady = false,
                        conversationError = error.message ?: "本地聊天记录无法打开",
                    )
                }
            }
        }
    }

    fun updateInput(value: String) {
        interaction.update {
            it.copy(
                input = value,
                searchConfirmation = null,
            )
        }
    }

    fun send() {
        if (!apiSettingsStore.configured.value || !interaction.value.conversationReady) return
        val text = interaction.value.input
        if (text.isBlank() || chatEngine.state.value.isStreaming) return
        chatEngine.clearError()
        if (needsSearchConfirmation(text)) {
            val query = PerceptionQueryPolicy.suggestedSearchQuery(text) ?: return
            interaction.update {
                it.copy(
                    searchConfirmation = SearchConfirmationUiState(
                        originalMessage = text,
                        query = query,
                    ),
                )
            }
            return
        }
        sendApproved(text, approvedSearchQuery = null)
    }

    fun updateSearchQuery(value: String) {
        val clipped = value.take(MAX_SEARCH_QUERY_LENGTH)
        if (chatEngine.state.value.pendingToolSearch != null) {
            interaction.update { it.copy(toolSearchDraft = clipped) }
            return
        }
        interaction.update { current ->
            val pending = current.searchConfirmation ?: return@update current
            current.copy(
                searchConfirmation = pending.copy(
                    query = clipped,
                    error = null,
                ),
            )
        }
    }

    fun confirmSearchAndSend() {
        val midTurnPending = chatEngine.state.value.pendingToolSearch
        if (midTurnPending != null) {
            val candidate = interaction.value.toolSearchDraft ?: midTurnPending.suggestedQuery
            if (chatEngine.approveToolSearch(candidate)) {
                interaction.update { it.copy(toolSearchDraft = null) }
            }
            return
        }
        val pending = interaction.value.searchConfirmation ?: return
        val approvedQuery = PerceptionQueryPolicy.normalizeConfirmedSearchQuery(pending.query)
        if (approvedQuery == null) {
            interaction.update { current ->
                current.copy(
                    searchConfirmation = pending.copy(
                        error = "搜索词不能为空，也不能包含验证码、证件号或银行卡等敏感字段",
                    ),
                )
            }
            return
        }
        sendApproved(pending.originalMessage, approvedQuery)
    }

    fun sendWithoutSearch() {
        if (chatEngine.state.value.pendingToolSearch != null) {
            interaction.update { it.copy(toolSearchDraft = null) }
            chatEngine.rejectToolSearch()
            return
        }
        val pending = interaction.value.searchConfirmation ?: return
        sendApproved(pending.originalMessage, approvedSearchQuery = null)
    }

    fun dismissSearchConfirmation() {
        if (chatEngine.state.value.pendingToolSearch != null) {
            interaction.update { it.copy(toolSearchDraft = null) }
            chatEngine.rejectToolSearch()
            return
        }
        interaction.update { it.copy(searchConfirmation = null) }
    }

    fun retry() {
        if (apiSettingsStore.configured.value) {
            chatEngine.retry(
                conversationId = ConversationIds.DEFAULT,
                companion = companionProfileStore.profile.value,
                approvedSearchQuery = null,
            )
        }
    }

    fun stop() {
        chatEngine.stop()
    }

    private fun needsSearchConfirmation(text: String): Boolean =
        perceptionSettingsStore.load().searchConfigured &&
            PerceptionQueryPolicy.hasConfirmableSearchIntent(text)

    private fun sendApproved(
        text: String,
        approvedSearchQuery: String?,
    ) {
        if (chatEngine.send(
                conversationId = ConversationIds.DEFAULT,
                companion = companionProfileStore.profile.value,
                text = text,
                approvedSearchQuery = approvedSearchQuery,
            )
        ) {
            interaction.update {
                it.copy(
                    input = "",
                    searchConfirmation = null,
                )
            }
        }
    }

    override fun onCleared() {
        chatEngine.stop()
        super.onCleared()
    }

    private companion object {
        const val MAX_SEARCH_QUERY_LENGTH = 120
    }
}
