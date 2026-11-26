package com.xinyu.companion.domain.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import com.xinyu.companion.domain.CompanionProfile

interface MemorySettingsStore {
    val autoMemoryEnabled: Flow<Boolean>

    suspend fun setAutoMemoryEnabled(enabled: Boolean)
}

suspend fun MemorySettingsStore.isAutoMemoryEnabled(): Boolean =
    autoMemoryEnabled.first()

interface MemoryExtractor {
    suspend fun extract(input: MemoryJobInput): Result<MemoryExtractionResult>
}

interface MemoryWorkCoordinator {
    val isProcessing: StateFlow<Boolean>

    fun start(
        conversationId: String,
        companion: CompanionProfile,
    )

    fun onTurnCompleted(
        conversationId: String,
        companion: CompanionProfile,
    )

    fun consolidateNow(
        conversationId: String,
        companion: CompanionProfile,
    )
}

interface MemoryStore {
    fun observeMemories(companionId: String): Flow<List<LongTermMemory>>

    fun observeLatestSummary(conversationId: String): Flow<ConversationSummary?>

    fun observeLatestJob(conversationId: String): Flow<MemoryJob?>

    suspend fun enqueue(conversationId: String, companionId: String): MemoryJob?

    suspend fun recoverRunningJobs()

    suspend fun loadJobInput(job: MemoryJob): MemoryJobInput?

    suspend fun markJobRunning(jobId: String)

    suspend fun completeJob(
        jobId: String,
        conversationId: String,
        summary: String,
        candidates: List<MemoryCandidate>,
    )

    suspend fun failJob(jobId: String, message: String)

    suspend fun latestSummary(conversationId: String): ConversationSummary?

    suspend fun activeProfileMemories(companionId: String): List<LongTermMemory>

    suspend fun activeMemories(companionId: String): List<LongTermMemory>

    suspend fun searchActiveMemories(
        companionId: String,
        query: String,
    ): List<LongTermMemory>

    suspend fun markMemoryAccessed(ids: List<String>, at: Long)

    suspend fun addManualMemory(input: ManualMemoryInput): LongTermMemory

    suspend fun updateMemoryStatement(id: String, statement: String)

    suspend fun confirmMemory(id: String)

    suspend fun rejectMemory(id: String)

    suspend fun deleteMemory(id: String)

    suspend fun forgetObservedSince(companionId: String, since: Long)

    suspend fun expireTemporalMemories(companionId: String, at: Long)

    suspend fun discardPendingCandidates(companionId: String)

    suspend fun clearAllMemories(conversationId: String)
}

