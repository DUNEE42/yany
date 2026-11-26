package com.xinyu.companion.domain.repository

import com.xinyu.companion.domain.ChatMessage
import com.xinyu.companion.domain.ChatTurn
import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.MessageStatus
import kotlinx.coroutines.flow.Flow

interface ChatConversationStore {
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>

    suspend fun ensureSeeded(
        conversationId: String,
        companion: CompanionProfile,
    )

    suspend fun appendTurn(
        conversationId: String,
        text: String,
    ): ChatTurn

    suspend fun prepareRetry(assistantMessageId: String): ChatTurn?

    suspend fun updateMessage(
        id: String,
        content: String,
        status: MessageStatus,
    )

    suspend fun recoverIncomplete(conversationId: String)

    suspend fun getMessages(conversationId: String): List<ChatMessage>

    suspend fun clearMessages(conversationId: String)
}
