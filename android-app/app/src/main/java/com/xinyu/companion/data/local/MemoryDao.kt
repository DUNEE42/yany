package com.xinyu.companion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSummary(summary: ConversationSummaryEntity)

    @Query(
        """
        SELECT * FROM conversation_summaries
        WHERE conversationId = :conversationId AND status = 'ACTIVE'
        ORDER BY endSequence DESC
        LIMIT 1
        """,
    )
    fun observeLatestSummary(conversationId: String): Flow<ConversationSummaryEntity?>

    @Query(
        """
        SELECT * FROM conversation_summaries
        WHERE conversationId = :conversationId AND status = 'ACTIVE'
        ORDER BY endSequence DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestSummary(conversationId: String): ConversationSummaryEntity?

    @Query(
        """
        SELECT COALESCE(MAX(endSequence), 0)
        FROM conversation_summaries
        WHERE conversationId = :conversationId
          AND status IN ('ACTIVE', 'CHECKPOINT')
        """,
    )
    suspend fun latestSummarizedSequence(conversationId: String): Long

    @Query(
        """
        SELECT MAX(sequence)
        FROM messages
        WHERE conversationId = :conversationId
          AND status = 'COMPLETED'
          AND id NOT LIKE 'welcome-%'
        """,
    )
    suspend fun latestCompletedSequence(conversationId: String): Long?

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
          AND sequence >= :fromSequence
          AND status = 'COMPLETED'
          AND id NOT LIKE 'welcome-%'
        ORDER BY sequence ASC
        LIMIT :limit
        """,
    )
    suspend fun getCompletedMessagesFrom(
        conversationId: String,
        fromSequence: Long,
        limit: Int,
    ): List<MessageEntity>

    @Query("DELETE FROM conversation_summaries WHERE conversationId = :conversationId")
    suspend fun deleteSummaries(conversationId: String)

    @Query("DELETE FROM memory_jobs WHERE conversationId = :conversationId")
    suspend fun deleteJobs(conversationId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMemory(memory: MemoryItemEntity)

    @Query(
        """
        UPDATE memory_items
        SET statement = :statement,
            objectText = :objectText,
            normalizedKey = :normalizedKey,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateMemoryStatement(
        id: String,
        statement: String,
        objectText: String,
        normalizedKey: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE memory_items
        SET confirmationState = :confirmationState, status = :status, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateMemoryConfirmation(
        id: String,
        confirmationState: String,
        status: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE memory_items
        SET status = :status, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateMemoryStatus(id: String, status: String, updatedAt: Long)

    @Query(
        """
        UPDATE memory_items
        SET status = 'SUPERSEDED',
            validTo = :validTo,
            supersededById = :supersededById,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun supersedeMemory(
        id: String,
        validTo: Long,
        supersededById: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE memory_items
        SET lastAccessedAt = :accessedAt
        WHERE id IN (:ids)
        """,
    )
    suspend fun markAccessed(ids: List<String>, accessedAt: Long)

    @Query(
        """
        SELECT * FROM memory_items
        WHERE companionId = :companionId AND status != 'DELETED'
        ORDER BY
          CASE status WHEN 'ACTIVE' THEN 0 WHEN 'CANDIDATE' THEN 1 ELSE 2 END,
          importance DESC,
          updatedAt DESC
        """,
    )
    fun observeMemories(companionId: String): Flow<List<MemoryItemEntity>>

    @Query(
        """
        SELECT * FROM memory_items
        WHERE companionId = :companionId
          AND status = 'ACTIVE'
          AND (validTo IS NULL OR validTo > :now)
          AND (
            sourceKind IN ('USER_EXPLICIT', 'USER_FEEDBACK', 'MANUAL')
            OR confirmationState = 'CONFIRMED'
          )
        ORDER BY importance DESC, updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getActiveMemories(
        companionId: String,
        now: Long,
        limit: Int = 2_000,
    ): List<MemoryItemEntity>

    @Query(
        """
        SELECT * FROM memory_items
        WHERE companionId = :companionId
          AND status = 'ACTIVE'
          AND kind IN ('BOUNDARY', 'PREFERENCE', 'RELATIONSHIP_PREFERENCE', 'FACT')
        ORDER BY
          CASE kind
            WHEN 'BOUNDARY' THEN 0
            WHEN 'RELATIONSHIP_PREFERENCE' THEN 1
            WHEN 'PREFERENCE' THEN 2
            ELSE 3
          END,
          importance DESC,
          updatedAt DESC
        LIMIT 40
        """,
    )
    suspend fun getProfileCandidates(companionId: String): List<MemoryItemEntity>

    @Query(
        """
        UPDATE memory_items
        SET status = 'EXPIRED', updatedAt = :now
        WHERE companionId = :companionId
          AND status IN ('ACTIVE', 'CANDIDATE')
          AND validTo IS NOT NULL
          AND validTo <= :now
        """,
    )
    suspend fun expireTemporalMemories(companionId: String, now: Long)

    @Query(
        """
        SELECT * FROM memory_items
        WHERE companionId = :companionId
          AND status = 'ACTIVE'
          AND normalizedKey = :normalizedKey
        LIMIT 1
        """,
    )
    suspend fun findActiveByNormalizedKey(
        companionId: String,
        normalizedKey: String,
    ): MemoryItemEntity?

    @Query(
        """
        SELECT * FROM memory_items
        WHERE companionId = :companionId
          AND status = 'ACTIVE'
          AND scope = :scope
          AND subject = :subject
          AND predicate = :predicate
          AND normalizedKey != :normalizedKey
        ORDER BY observedAt DESC
        """,
    )
    suspend fun findPotentialConflicts(
        companionId: String,
        scope: String,
        subject: String,
        predicate: String,
        normalizedKey: String,
    ): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE id = :id LIMIT 1")
    suspend fun getMemory(id: String): MemoryItemEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(source: MemorySourceEntity)

    @Query(
        """
        UPDATE memory_items
        SET status = 'EXPIRED', updatedAt = :updatedAt
        WHERE status IN ('ACTIVE', 'CANDIDATE')
          AND sourceKind != 'MANUAL'
          AND NOT EXISTS (
            SELECT 1 FROM memory_sources
            WHERE memory_sources.memoryId = memory_items.id
              AND memory_sources.messageId IS NOT NULL
          )
          AND NOT EXISTS (
            SELECT 1 FROM memory_sources
            WHERE memory_sources.memoryId = memory_items.id
              AND memory_sources.summaryId IS NOT NULL
          )
        """,
    )
    suspend fun expireMemoriesWithoutEvidence(updatedAt: Long)

    @Query("DELETE FROM memory_items WHERE id = :id")
    suspend fun deleteMemory(id: String)

    @Query(
        """
        SELECT id FROM memory_items
        WHERE companionId = :companionId
          AND observedAt >= :since
          AND status != 'DELETED'
        """,
    )
    suspend fun getMemoryIdsObservedSince(companionId: String, since: Long): List<String>

    @Query(
        """
        SELECT id FROM memory_items
        WHERE companionId = :companionId
          AND status = 'CANDIDATE'
          AND confirmationState != 'REJECTED'
        """,
    )
    suspend fun getPendingCandidateIds(companionId: String): List<String>

    @Query("DELETE FROM memory_items")
    suspend fun deleteAllMemories()

    @Query("DELETE FROM conversation_summaries")
    suspend fun deleteAllSummaries()

    @Query("DELETE FROM memory_jobs")
    suspend fun deleteAllJobs()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJob(job: MemoryJobEntity)

    @Query(
        """
        UPDATE memory_jobs
        SET toSequence = :toSequence,
            status = 'PENDING',
            lastError = NULL
        WHERE id = :id
        """,
    )
    suspend fun extendPendingJob(id: String, toSequence: Long)

    @Query(
        """
        SELECT * FROM memory_jobs
        WHERE conversationId = :conversationId AND status = 'PENDING'
        ORDER BY scheduledAt ASC
        LIMIT 1
        """,
    )
    suspend fun getPendingJob(conversationId: String): MemoryJobEntity?

    @Query(
        """
        SELECT * FROM memory_jobs
        WHERE conversationId = :conversationId AND status = 'RUNNING'
        ORDER BY startedAt DESC
        LIMIT 1
        """,
    )
    suspend fun getRunningJob(conversationId: String): MemoryJobEntity?

    @Query("SELECT * FROM memory_jobs WHERE id = :id LIMIT 1")
    suspend fun getJob(id: String): MemoryJobEntity?

    @Query(
        """
        SELECT * FROM memory_jobs
        WHERE conversationId = :conversationId
        ORDER BY scheduledAt DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestJob(conversationId: String): MemoryJobEntity?

    @Query(
        """
        SELECT * FROM memory_jobs
        WHERE conversationId = :conversationId
        ORDER BY scheduledAt DESC
        LIMIT 1
        """,
    )
    fun observeLatestJob(conversationId: String): Flow<MemoryJobEntity?>

    @Query(
        """
        UPDATE memory_jobs
        SET status = 'RUNNING',
            attempts = attempts + 1,
            startedAt = :startedAt,
            lastError = NULL
        WHERE id = :id
        """,
    )
    suspend fun markJobRunning(id: String, startedAt: Long)

    @Query(
        """
        UPDATE memory_jobs
        SET status = 'COMPLETED', completedAt = :completedAt, lastError = NULL
        WHERE id = :id
        """,
    )
    suspend fun completeJob(id: String, completedAt: Long)

    @Query(
        """
        UPDATE memory_jobs
        SET status = 'FAILED', lastError = :error, completedAt = :completedAt
        WHERE id = :id
        """,
    )
    suspend fun failJob(id: String, error: String, completedAt: Long)

    @Query(
        """
        UPDATE memory_jobs
        SET status = 'PENDING', startedAt = NULL
        WHERE status = 'RUNNING'
        """,
    )
    suspend fun recoverRunningJobs()

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
          AND sequence BETWEEN :fromSequence AND :toSequence
          AND status = 'COMPLETED'
          AND id NOT LIKE 'welcome-%'
        ORDER BY sequence ASC
        """,
    )
    suspend fun getJobMessages(
        conversationId: String,
        fromSequence: Long,
        toSequence: Long,
    ): List<MessageEntity>

}

