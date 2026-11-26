package com.xinyu.companion.data.repository

import androidx.room.withTransaction
import com.xinyu.companion.data.local.AppDatabase
import com.xinyu.companion.data.local.ConversationEntity
import com.xinyu.companion.data.local.MessageEntity
import com.xinyu.companion.domain.ChatMessage
import com.xinyu.companion.domain.ChatTurn
import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.MessageRole
import com.xinyu.companion.domain.MessageStatus
import com.xinyu.companion.domain.repository.ChatConversationStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ChatRepository @Inject constructor(
    private val database: Provider<AppDatabase>,
) : ChatConversationStore {
    private val dao by lazy { database.get().companionDao() }

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        dao.observeMessages(conversationId).map { entities ->
            entities.map(MessageEntity::toDomain)
        }

    override suspend fun ensureSeeded(
        conversationId: String,
        companion: CompanionProfile,
    ) {
        database.get().withTransaction {
            val now = System.currentTimeMillis()
            dao.insertConversation(
                ConversationEntity(
                    id = conversationId,
                    companionId = companion.id,
                    title = "和${companion.name}的日常",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            dao.interruptUnfinishedMessages(conversationId, now)
            if (dao.getMessages(conversationId).isEmpty()) {
                dao.insertMessage(
                    MessageEntity(
                        id = "welcome-$conversationId",
                        conversationId = conversationId,
                        role = MessageRole.ASSISTANT.name,
                        sequence = dao.nextSequence(conversationId),
                        content = "你来了。今天怎么样？",
                        status = MessageStatus.COMPLETED.name,
                        clientMessageId = null,
                        replyToMessageId = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    override suspend fun appendTurn(
        conversationId: String,
        text: String,
    ): ChatTurn = database.get().withTransaction {
        val now = System.currentTimeMillis()
        val clientMessageId = UUID.randomUUID().toString()
        val (userSequence, assistantSequence) = nextTurnSequences(
            dao.nextSequence(conversationId),
        )
        val user = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = MessageRole.USER.name,
            sequence = userSequence,
            content = text,
            status = MessageStatus.COMPLETED.name,
            clientMessageId = clientMessageId,
            replyToMessageId = null,
            createdAt = now,
            updatedAt = now,
        )
        val assistant = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = MessageRole.ASSISTANT.name,
            sequence = assistantSequence,
            content = "",
            status = MessageStatus.STREAMING.name,
            clientMessageId = null,
            replyToMessageId = user.id,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertMessage(user)
        dao.insertMessage(assistant)
        dao.touchConversation(conversationId, now)
        ChatTurn(user.toDomain(), assistant.toDomain())
    }

    override suspend fun prepareRetry(assistantMessageId: String): ChatTurn? =
        database.get().withTransaction {
            val assistant = dao.getMessage(assistantMessageId) ?: return@withTransaction null
            if (assistant.role != MessageRole.ASSISTANT.name) return@withTransaction null
            if (assistant.status !in setOf(
                    MessageStatus.FAILED.name,
                    MessageStatus.INTERRUPTED.name,
                )
            ) {
                return@withTransaction null
            }

            val user = assistant.replyToMessageId
                ?.let { dao.getMessage(it) }
                ?.takeIf {
                    it.role == MessageRole.USER.name &&
                        it.conversationId == assistant.conversationId
                }
                ?: dao.getPreviousUserMessage(
                    conversationId = assistant.conversationId,
                    beforeSequence = assistant.sequence,
                )
                ?: return@withTransaction null

            val now = System.currentTimeMillis()
            dao.updateMessage(
                id = assistant.id,
                content = "",
                status = MessageStatus.STREAMING.name,
                updatedAt = now,
            )
            dao.touchConversation(assistant.conversationId, now)
            ChatTurn(
                user = user.toDomain(),
                assistant = assistant.copy(
                    content = "",
                    status = MessageStatus.STREAMING.name,
                    updatedAt = now,
                ).toDomain(),
            )
        }

    override suspend fun updateMessage(
        id: String,
        content: String,
        status: MessageStatus,
    ) {
        dao.updateMessage(id, content, status.name, System.currentTimeMillis())
    }

    override suspend fun recoverIncomplete(conversationId: String) {
        dao.interruptUnfinishedMessages(conversationId, System.currentTimeMillis())
    }

    override suspend fun getMessages(conversationId: String): List<ChatMessage> =
        dao.getMessages(conversationId).map(MessageEntity::toDomain)

    override suspend fun clearMessages(conversationId: String) {
        database.get().withTransaction {
            val memoryDao = database.get().memoryDao()
            dao.deleteMessages(conversationId)
            memoryDao.deleteSummaries(conversationId)
            memoryDao.deleteJobs(conversationId)
            memoryDao.expireMemoriesWithoutEvidence(System.currentTimeMillis())
        }
    }
}

internal fun nextTurnSequences(nextAvailable: Long): Pair<Long, Long> =
    nextAvailable to nextAvailable + 1

private fun MessageEntity.toDomain() = ChatMessage(
    id = id,
    conversationId = conversationId,
    role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.ASSISTANT),
    sequence = sequence,
    content = content,
    status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.FAILED),
    createdAt = createdAt,
    clientMessageId = clientMessageId,
    replyToMessageId = replyToMessageId,
)

