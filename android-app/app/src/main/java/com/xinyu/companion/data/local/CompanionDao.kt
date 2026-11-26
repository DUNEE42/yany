package com.xinyu.companion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CompanionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sequence ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sequence ASC")
    suspend fun getMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessage(id: String): MessageEntity?

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
          AND sequence < :beforeSequence
          AND role = 'USER'
        ORDER BY sequence DESC
        LIMIT 1
        """,
    )
    suspend fun getPreviousUserMessage(
        conversationId: String,
        beforeSequence: Long,
    ): MessageEntity?

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM messages WHERE conversationId = :conversationId")
    suspend fun nextSequence(conversationId: String): Long

    @Query("UPDATE messages SET content = :content, status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMessage(
        id: String,
        content: String,
        status: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE messages
        SET status = 'INTERRUPTED', updatedAt = :updatedAt
        WHERE conversationId = :conversationId
          AND status IN ('PENDING', 'STREAMING')
        """,
    )
    suspend fun interruptUnfinishedMessages(
        conversationId: String,
        updatedAt: Long,
    )

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :conversationId")
    suspend fun touchConversation(conversationId: String, updatedAt: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessages(conversationId: String)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Query(
        """
        SELECT * FROM memories
        WHERE companionId = :companionId AND userConfirmed = 1
        ORDER BY importance DESC, createdAt DESC
        LIMIT 30
        """,
    )
    fun observeMemories(companionId: String): Flow<List<MemoryEntity>>

    @Query(
        """
        SELECT * FROM memories
        WHERE companionId = :companionId AND userConfirmed = 1
        ORDER BY importance DESC, createdAt DESC
        LIMIT 30
        """,
    )
    suspend fun getMemories(companionId: String): List<MemoryEntity>

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemory(id: String)
}

