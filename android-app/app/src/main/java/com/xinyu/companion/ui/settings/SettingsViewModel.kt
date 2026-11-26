package com.xinyu.companion.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xinyu.companion.data.settings.CompanionAvatarStorage
import com.xinyu.companion.domain.ChatEngine
import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.ConversationIds
import com.xinyu.companion.domain.ConversationStyle
import com.xinyu.companion.domain.ReplyLength
import com.xinyu.companion.domain.coroutines.IoDispatcher
import com.xinyu.companion.domain.memory.MemorySettingsStore
import com.xinyu.companion.domain.memory.MemoryStore
import com.xinyu.companion.domain.provider.ChatProvider
import com.xinyu.companion.domain.provider.ProviderConfig
import com.xinyu.companion.domain.repository.ChatConversationStore
import com.xinyu.companion.domain.settings.ChatStyleStore
import com.xinyu.companion.domain.settings.CompanionProfileStore
import com.xinyu.companion.domain.settings.ProviderSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SettingsDraftState(
    val loaded: Boolean = false,
    val hasApiKey: Boolean = false,
    val keyDraft: String = "",
    val modelDraft: String = ProviderConfig.DEFAULT_MODEL,
    val isTesting: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val conversationStyle: ConversationStyle = ConversationStyle(),
    val companionNameDraft: String = CompanionProfile.DEFAULT_NAME,
    val isUpdatingAvatar: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val apiSettingsStore: ProviderSettingsStore,
    private val styleStore: ChatStyleStore,
    private val companionProfileStore: CompanionProfileStore,
    private val avatarStorage: CompanionAvatarStorage,
    private val chatProvider: ChatProvider,
    private val chatEngine: ChatEngine,
    private val conversationStore: ChatConversationStore,
    private val memoryStore: MemoryStore,
    private val memorySettingsStore: MemorySettingsStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val draft = MutableStateFlow(SettingsDraftState())

    val uiState: StateFlow<SettingsUiState> = combine(
        draft,
        companionProfileStore.profile,
        memorySettingsStore.autoMemoryEnabled,
        memoryStore.observeMemories(CompanionProfile.MAIN_COMPANION_ID).map { it.size },
    ) { currentDraft, profile, autoMemoryEnabled, memoryCount ->
        SettingsUiState(
            loaded = currentDraft.loaded,
            hasApiKey = currentDraft.hasApiKey,
            keyDraft = currentDraft.keyDraft,
            modelDraft = currentDraft.modelDraft,
            isTesting = currentDraft.isTesting,
            message = currentDraft.message,
            error = currentDraft.error,
            conversationStyle = currentDraft.conversationStyle,
            companionProfile = profile,
            companionNameDraft = currentDraft.companionNameDraft,
            isUpdatingAvatar = currentDraft.isUpdatingAvatar,
            autoMemoryEnabled = autoMemoryEnabled,
            memoryCount = memoryCount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    init {
        viewModelScope.launch {
            val (config, style, profile) = withContext(ioDispatcher) {
                Triple(
                    apiSettingsStore.load(),
                    styleStore.load(),
                    companionProfileStore.load(),
                )
            }
            draft.update {
                it.copy(
                    loaded = true,
                    hasApiKey = config.isConfigured,
                    modelDraft = config.model,
                    conversationStyle = style,
                    companionNameDraft = profile.displayName,
                )
            }
        }
    }

    fun updateProviderKey(value: String) {
        draft.update {
            it.copy(
                keyDraft = value,
                message = null,
                error = null,
            )
        }
    }

    fun updateProviderModel(value: String) {
        draft.update {
            it.copy(
                modelDraft = value,
                message = null,
                error = null,
            )
        }
    }

    fun updateCompanionNameDraft(value: String) {
        draft.update {
            it.copy(
                companionNameDraft = value.take(CompanionProfile.MAX_NAME_LENGTH),
                message = null,
                error = null,
            )
        }
    }

    fun saveCompanionName() {
        val cleanName = draft.value.companionNameDraft.trim()
        if (cleanName.isBlank()) {
            draft.update { it.copy(error = "请填写伴侣昵称") }
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val current = companionProfileStore.load()
                    companionProfileStore.save(current.copy(name = cleanName))
                }
            }.onSuccess { saved ->
                draft.update {
                    it.copy(
                        companionNameDraft = saved.displayName,
                        message = "已将伴侣昵称更新为“${saved.displayName}”",
                        error = null,
                    )
                }
            }.onFailure { error ->
                draft.update {
                    it.copy(error = error.message ?: "无法保存伴侣昵称")
                }
            }
        }
    }

    fun updateCompanionAvatar(sourceUri: Uri) {
        if (draft.value.isUpdatingAvatar) return
        viewModelScope.launch {
            draft.update {
                it.copy(isUpdatingAvatar = true, message = null, error = null)
            }
            val result = withContext(ioDispatcher) {
                avatarStorage.importAvatar(sourceUri).mapCatching { path ->
                    val current = companionProfileStore.load()
                    companionProfileStore.save(current.copy(avatarPath = path))
                }
            }
            result.fold(
                onSuccess = {
                    draft.update {
                        it.copy(
                            isUpdatingAvatar = false,
                            message = "头像已更新并保存在本机私有目录",
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    draft.update {
                        it.copy(
                            isUpdatingAvatar = false,
                            error = error.message ?: "无法更新头像，请换一张图片重试",
                        )
                    }
                },
            )
        }
    }

    fun resetCompanionAvatar() {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    avatarStorage.clearAvatars()
                    val current = companionProfileStore.load()
                    companionProfileStore.save(current.copy(avatarPath = null))
                }
            }.onSuccess {
                draft.update {
                    it.copy(
                        message = "已恢复默认文字头像",
                        error = null,
                    )
                }
            }.onFailure { error ->
                draft.update {
                    it.copy(error = error.message ?: "无法重置头像")
                }
            }
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
        draft.update { it.copy(conversationStyle = style) }
        viewModelScope.launch {
            withContext(ioDispatcher) { styleStore.save(style) }
        }
    }

    fun setAutoMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    memorySettingsStore.setAutoMemoryEnabled(enabled)
                }
            }.onSuccess {
                draft.update {
                    it.copy(
                        message = if (enabled) "自动整理记忆已开启" else "自动整理记忆已关闭",
                        error = null,
                    )
                }
            }.onFailure { error ->
                draft.update {
                    it.copy(error = error.message ?: "无法保存记忆设置")
                }
            }
        }
    }

    fun clearApiKey() {
        chatEngine.resetSession()
        viewModelScope.launch {
            withContext(ioDispatcher) { apiSettingsStore.clearApiKey() }
            draft.update {
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
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val companion = companionProfileStore.load()
                    conversationStore.clearMessages(ConversationIds.DEFAULT)
                    conversationStore.ensureSeeded(ConversationIds.DEFAULT, companion)
                }
            }.onSuccess {
                draft.update {
                    it.copy(
                        message = "聊天记录已清空",
                        error = null,
                    )
                }
            }.onFailure { error ->
                draft.update {
                    it.copy(
                        error = error.message ?: "无法重建本地聊天",
                    )
                }
            }
        }
    }

    fun testAndSaveProvider() {
        if (draft.value.isTesting) return
        viewModelScope.launch {
            draft.update {
                it.copy(
                    isTesting = true,
                    message = null,
                    error = null,
                )
            }
            val existing = withContext(ioDispatcher) { apiSettingsStore.load() }
            val key = draft.value.keyDraft.trim().ifBlank { existing.apiKey }
            val model = draft.value.modelDraft.trim().ifBlank { ProviderConfig.DEFAULT_MODEL }

            if (key.isBlank()) {
                draft.update {
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
                        draft.update {
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
                        draft.update {
                            it.copy(
                                isTesting = false,
                                error = saveError.message ?: "Key 已连上，但无法安全保存到本机",
                            )
                        }
                    }
                },
                onFailure = { error ->
                    draft.update {
                        it.copy(
                            isTesting = false,
                            error = error.message ?: "连接失败，请检查 Key 和网络",
                        )
                    }
                },
            )
        }
    }
}
