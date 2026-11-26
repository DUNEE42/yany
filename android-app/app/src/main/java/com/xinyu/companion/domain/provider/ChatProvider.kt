package com.xinyu.companion.domain.provider

import com.xinyu.companion.domain.ContextTurn
import com.xinyu.companion.domain.ConversationStyle
import com.xinyu.companion.domain.GenerationEvent
import kotlinx.coroutines.flow.Flow

data class ProviderCapabilities(
    val streaming: Boolean,
    val images: Boolean,
    val tools: Boolean,
    val usageReporting: Boolean,
)

data class ChatRequest(
    val context: List<ContextTurn>,
    val config: ProviderConfig,
    val conversationStyle: ConversationStyle = ConversationStyle(),
    val maxOutputTokens: Int? = null,
)

interface ChatProvider {
    val id: String
    val capabilities: ProviderCapabilities

    fun stream(request: ChatRequest): Flow<GenerationEvent>

    suspend fun testConnection(config: ProviderConfig): Result<Unit>
}
