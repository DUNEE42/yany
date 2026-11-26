package com.xinyu.companion.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["companionId", "updatedAt"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val companionId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversationId", "sequence"], unique = true),
        Index(value = ["clientMessageId"], unique = true),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val sequence: Long,
    val content: String,
    val status: String,
    val clientMessageId: String?,
    val replyToMessageId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "memories",
    indices = [
        Index(
            value = ["companionId", "userConfirmed", "importance", "createdAt"],
        ),
    ],
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val companionId: String,
    val type: String,
    val content: String,
    val importance: Int,
    val userConfirmed: Boolean,
    val createdAt: Long,
)

