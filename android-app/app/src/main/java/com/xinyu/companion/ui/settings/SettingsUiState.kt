package com.xinyu.companion.ui.settings

import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.ConversationStyle
import com.xinyu.companion.domain.provider.ProviderConfig

data class SettingsUiState(
    val loaded: Boolean = false,
    val hasApiKey: Boolean = false,
    val keyDraft: String = "",
    val modelDraft: String = ProviderConfig.DEFAULT_MODEL,
    val isTesting: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val conversationStyle: ConversationStyle = ConversationStyle(),
    val companionProfile: CompanionProfile = CompanionProfile(),
    val companionNameDraft: String = CompanionProfile.DEFAULT_NAME,
    val isUpdatingAvatar: Boolean = false,
    val autoMemoryEnabled: Boolean = true,
    val memoryCount: Int = 0,
)
