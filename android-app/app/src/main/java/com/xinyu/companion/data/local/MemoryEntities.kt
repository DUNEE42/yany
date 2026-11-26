package com.xinyu.companion.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_summaries",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            name = "index_conversation_summaries_conversationId_endSequence",
            value = ["conversationId", "endSequence"],
        ),
        Index(
            name = "index_conversation_summaries_conversationId_contentHash",
            value = ["conversationId", "contentHash"],
            unique = true,
        ),
    ],
)
data class ConversationSummaryEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val startSequence: Long,
    val endSequence: Long,
    val periodStart: Long,
    val periodEnd: Long,
    val content: String,
    val contentHash: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "memory_items",
    indices = [
        Index(
            name = "index_memory_items_companionId_status_kind",
            value = ["companionId", "status", "kind"],
        ),
        Index(
            name = "index_memory_items_identity",
            value = ["scope", "subject", "predicate", "status"],
        ),
        Index(
            name = "index_memory_items_validity",
            value = ["validFrom", "validTo"],
        ),
        Index(
            name = "index_memory_items_normalizedKey",
            value = ["normalizedKey"],
        ),
    ],
)
data class MemoryItemEntity(
    @PrimaryKey val id: String,
    val companionId: String,
    val kind: String,
    val scope: String,
    val subject: String,
    val predicate: String,
    val objectText: String,
    val statement: String,
    val normalizedKey: String,
    val sourceKind: String,
    val confirmationState: String,
    val status: String,
    val confidence: Double,
    val importance: Int,
    val sensitivity: String,
    val observedAt: Long,
    val validFrom: Long?,
    val validTo: Long?,
    val supersededById: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long?,
)

@Entity(
    tableName = "memory_sources",
    foreignKeys = [
        ForeignKey(
            entity = MemoryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ConversationSummaryEntity::class,
            parentColumns = ["id"],
            childColumns = ["summaryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(
            name = "index_memory_sources_memoryId",
            value = ["memoryId"],
        ),
        Index(
            name = "index_memory_sources_messageId",
            value = ["messageId"],
        ),
        Index(
            name = "index_memory_sources_summaryId",
            value = ["summaryId"],
        ),
        Index(
            name = "index_memory_sources_identity",
            value = ["memoryId", "messageId", "summaryId"],
            unique = true,
        ),
    ],
)
data class MemorySourceEntity(
    @PrimaryKey val id: String,
    val memoryId: String,
    val messageId: String?,
    val summaryId: String?,
    val evidenceHash: String,
)

@Entity(
    tableName = "memory_jobs",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            name = "index_memory_jobs_conversationId_status",
            value = ["conversationId", "status"],
        ),
        Index(
            name = "index_memory_jobs_status_scheduledAt",
            value = ["status", "scheduledAt"],
        ),
    ],
)
data class MemoryJobEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val companionId: String,
    val fromSequence: Long,
    val toSequence: Long,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val scheduledAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
)

