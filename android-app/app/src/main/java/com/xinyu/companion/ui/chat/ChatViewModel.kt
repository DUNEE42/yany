package com.xinyu.companion.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xinyu.companion.domain.ChatEngine
import com.xinyu.companion.domain.ChatMessage
import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.ConversationIds
import com.xinyu.companion.domain.ConversationStyle
import com.xinyu.companion.domain.ReplyLength
import com.xinyu.companion.domain.coroutines.IoDispatcher
import com.xinyu.companion.domain.provider.ChatProvider
import com.xinyu.companion.domain.provider.ProviderConfig
import com.xinyu.companion.domain.memory.MemoryWorkCoordinator
import com.xinyu.companion.domain.perception.PerceptionQueryPolicy
import com.xinyu.companion.domain.perception.PerceptionSettingsStore
import com.xinyu.companion.domain.repository.ChatConversationStore
import com.xinyu.companion.domain.settings.ChatStyleStore
import com.xinyu.companion.domain.settings.ProviderSettingsStore
import com.xinyu.companion.ui.settings.SettingsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val store: ChatConversationStore,
    private val apiSettingsStore: ProviderSettingsStore,
    private val styleStore: ChatStyleStore,
    private val chatProvider: ChatProvider,
    private val chatEngine: ChatEngine,
    private val memoryCoordinator: MemoryWorkCoordinator,
    private val perceptionSettingsStore: PerceptionSettingsStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val companion = CompanionProfile()
    private val interaction = MutableStateFlow(InteractionState())
    private val settings = MutableStateFlow(SettingsUiState())

    val settingsState: StateFlow<SettingsUiState> = settings.asStateFlow()

    val uiState: StateFlow<ChatUiState> by lazy {
        combine(
            store.observeMessages(ConversationIds.DEFAULT),
            interaction,
            chatEngine.state,
            settings,
        ) { messages, local, engine, settingsState ->
            ChatUiState(
                messages = messages,
                input = local.input,
                isStreaming = engine.isStreaming,
                error = engine.error ?: local.conversationError,
                safetyLevel = engine.safetyLevel,
                canRetry = engine.retryableAssistantMessageId != null,
                hasApiKey = settingsState.hasApiKey,
                isConversationReady = local.conversationReady,
                liveContextLabel = engine.liveContextLabel,
                searchConfirmation = local.searchConfirmation,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatUiState(),
        )
    }

    init {
        viewModelScope.launch {
            val (config, style) = withContext(ioDispatcher) {
                apiSettingsStore.load() to styleStore.load()
            }
            settings.update {
                it.copy(
                    loaded = true,
                    hasApiKey = config.isConfigured,
                    modelDraft = config.model,
                    conversationStyle = style,
                )
            }
        }
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    store.ensureSeeded(ConversationIds.DEFAULT, companion)
                }
            }.onSuccess {
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

    val companionName: String = companion.name

    fun updateInput(value: String) {
        interaction.update {
            it.copy(
                input = value,
                searchConfirmation = null,
            )
        }
    }

    fun send() {
        if (!settings.value.hasApiKey || !interaction.value.conversationReady) return
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
        interaction.update { current ->
            val pending = current.searchConfirmation ?: return@update current
            current.copy(
                searchConfirmation = pending.copy(
                    query = value.take(MAX_SEARCH_QUERY_LENGTH),
                    error = null,
                ),
            )
        }
    }

    fun confirmSearchAndSend() {
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
        val pending = interaction.value.searchConfirmation ?: return
        sendApproved(pending.originalMessage, approvedSearchQuery = null)
    }

    fun dismissSearchConfirmation() {
        interaction.update { it.copy(searchConfirmation = null) }
    }

    fun retry() {
        if (settings.value.hasApiKey) {
            chatEngine.retry(
                conversationId = ConversationIds.DEFAULT,
                companion = companion,
                approvedSearchQuery = null,
            )
        }
    }

    fun stop() {
        chatEngine.stop()
    }

    fun updateProviderKey(value: String) {
        settings.update {
            it.copy(
                keyDraft = value,
                message = null,
                error = null,
            )
        }
    }

    fun updateProviderModel(value: String) {
        settings.update {
            it.copy(
                modelDraft = value,
                message = null,
                error = null,
            )
        }
    }

    fun saveConversationStyle(
        temperature: Float,
        closeness: Float,
        replyLength: ReplyLength,
    ) {
        val style = ConversationStyle(
            temperature = temperature.toDouble().coerceIn(
                ConversationStyle.MIN_TEMPERATURE,
                ConversationStyle.MAX_TEMPERATURE,
            ),
            closeness = closeness.toInt().coerceIn(0, 100),
            replyLength = replyLength,
        )
        settings.update { it.copy(conversationStyle = style) }
        viewModelScope.launch {
            withContext(ioDispatcher) { styleStore.save(style) }
        }
    }

    fun clearApiKey() {
        chatEngine.resetSession()
        viewModelScope.launch {
            withContext(ioDispatcher) { apiSettingsStore.clearApiKey() }
            settings.update {
                it.copy(
                    hasApiKey = false,
                    keyDraft = "",
                    message = "API Key 已删除",
                    error = null,
                )
            }
        }
    }

    fun clearChatMessages() {
        chatEngine.resetSession()
        interaction.update {
            it.copy(conversationReady = false, conversationError = null)
        }
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    store.clearMessages(ConversationIds.DEFAULT)
                    store.ensureSeeded(ConversationIds.DEFAULT, companion)
                }
            }.onSuccess {
                interaction.update {
                    it.copy(conversationReady = true, conversationError = null)
                }
                settings.update { it.copy(message = "聊天记录已清空") }
            }.onFailure { error ->
                interaction.update {
                    it.copy(
                        conversationReady = false,
                        conversationError = error.message ?: "无法重建本地聊天",
                    )
                }
            }
        }
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
                companion = companion,
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

    fun testAndSaveProvider() {
        if (settings.value.isTesting) return
        viewModelScope.launch {
            settings.update {
                it.copy(
                    isTesting = true,
                    message = null,
                    error = null,
                )
            }
            val existing = withContext(ioDispatcher) { apiSettingsStore.load() }
            val key = settings.value.keyDraft.trim().ifBlank { existing.apiKey }
            val model = settings.value.modelDraft.trim().ifBlank { ProviderConfig.DEFAULT_MODEL }

            if (key.isBlank()) {
                settings.update {
                    it.copy(
                        isTesting = false,
                        error = "请填写 DeepSeek API Key",
                    )
                }
                return@launch
            }

            val config = ProviderConfig(
                apiKey = key,
                baseUrl = ProviderConfig.DEFAULT_BASE_URL,
                model = model,
            )
            chatProvider.testConnection(config).fold(
                onSuccess = {
                    runCatching {
                        withContext(ioDispatcher) { apiSettingsStore.save(config) }
                    }.onSuccess {
                        settings.update {
                            it.copy(
                                hasApiKey = true,
                                keyDraft = "",
                                modelDraft = model,
                                isTesting = false,
                                message = "连接成功，设置已保存",
                                error = null,
                            )
                        }
                    }.onFailure { saveError ->
                        settings.update {
                            it.copy(
                                isTesting = false,
                                error = saveError.message ?: "Key 已连上，但无法安全保存到本机",
                            )
                        }
                    }
                },
                onFailure = { error ->
                    settings.update {
                        it.copy(
                            isTesting = false,
                            error = error.message ?: "连接失败，请检查 Key 和网络",
                        )
                    }
                },
            )
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
