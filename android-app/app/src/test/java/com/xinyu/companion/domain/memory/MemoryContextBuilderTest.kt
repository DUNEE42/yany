package com.xinyu.companion.domain.memory

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MemoryContextBuilderTest {
    @Test
    fun `profile summary and relevant memory are separated and prioritized`() = runTest {
        val store = FakeMemoryStore(
            profile = listOf(
                memory(
                    id = "fact",
                    kind = MemoryKind.FACT,
                    statement = "用户住在杭州",
                ),
                memory(
                    id = "boundary",
                    kind = MemoryKind.BOUNDARY,
                    statement = "用户不喜欢连续追问",
                ),
                memory(
                    id = "preference",
                    kind = MemoryKind.PREFERENCE,
                    statement = "用户喜欢先听结论",
                ),
            ),
            active = listOf(
                memory(
                    id = "plan",
                    kind = MemoryKind.PLAN,
                    statement = "用户计划周五去杭州面试",
                ),
                memory(
                    id = "unconfirmed",
                    kind = MemoryKind.FACT,
                    statement = "用户可能讨厌这份工作",
                    sourceKind = MemorySourceKind.INFERRED,
                    confirmation = MemoryConfirmationState.UNCONFIRMED,
                    sensitivity = MemorySensitivity.SENSITIVE,
                ),
            ),
            summary = "用户最近在准备杭州面试。",
        )

        val context = MemoryContextBuilder(store).build(
            companionId = "companion",
            conversationId = "conversation",
            query = "你还记得杭州的面试吗",
        )

        assertThat(context.profileItems.first()).isEqualTo("用户不喜欢连续追问")
        assertThat(context.profileItems).contains("用户喜欢先听结论")
        assertThat(context.profileItems).doesNotContain("用户可能讨厌这份工作")
        assertThat(context.recentSummary).contains("杭州面试")
        assertThat(context.relevantMemories.map { it.statement })
            .contains("用户计划周五去杭州面试")
        assertThat(context.relevantMemories.map { it.statement })
            .doesNotContain("用户不喜欢连续追问")
        assertThat(store.accessedIds).contains("plan")
    }

    @Test
    fun `profile is capped to eight concise items`() = runTest {
        val store = FakeMemoryStore(
            profile = (1..12).map { index ->
                memory(
                    id = "p$index",
                    kind = MemoryKind.PREFERENCE,
                    statement = "偏好$index：" + "很具体的说明".repeat(12),
                )
            },
        )

        val context = MemoryContextBuilder(store).build(
            companionId = "companion",
            conversationId = "conversation",
            query = "你好",
        )

        assertThat(context.profileItems.size).isAtMost(8)
        assertThat(context.profileItems.sumOf(String::length)).isAtMost(600)
    }

    private fun memory(
        id: String,
        kind: MemoryKind,
        statement: String,
        sourceKind: MemorySourceKind = MemorySourceKind.USER_EXPLICIT,
        confirmation: MemoryConfirmationState = MemoryConfirmationState.CONFIRMED,
        sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    ) = LongTermMemory(
        id = id,
        companionId = "companion",
        kind = kind,
        scope = MemoryScope.USER,
        subject = "user",
        predicate = "states",
        objectText = statement,
        statement = statement,
        normalizedKey = id,
        sourceKind = sourceKind,
        confirmationState = confirmation,
        status = MemoryStatus.ACTIVE,
        confidence = 0.9,
        importance = 4,
        sensitivity = sensitivity,
        observedAt = System.currentTimeMillis(),
        validFrom = System.currentTimeMillis(),
        validTo = null,
        supersededById = null,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        lastAccessedAt = null,
    )
}

private class FakeMemoryStore(
    private val profile: List<LongTermMemory> = emptyList(),
    private val active: List<LongTermMemory> = emptyList(),
    private val summary: String? = null,
) : MemoryStore {
    val accessedIds = mutableListOf<String>()
    private val memories = MutableStateFlow<List<LongTermMemory>>(emptyList())

    override fun observeMemories(companionId: String): Flow<List<LongTermMemory>> = memories

    override fun observeLatestSummary(conversationId: String): Flow<ConversationSummary?> =
        MutableStateFlow(null)

    override fun observeLatestJob(conversationId: String): Flow<MemoryJob?> =
        MutableStateFlow(null)

    override suspend fun enqueue(conversationId: String, companionId: String): MemoryJob? = null

    override suspend fun recoverRunningJobs() = Unit

    override suspend fun loadJobInput(job: MemoryJob): MemoryJobInput? = null

    override suspend fun markJobRunning(jobId: String) = Unit

    override suspend fun completeJob(
        jobId: String,
        conversationId: String,
        summary: String,
        candidates: List<MemoryCandidate>,
    ) = Unit

    override suspend fun failJob(jobId: String, message: String) = Unit

    override suspend fun latestSummary(conversationId: String): ConversationSummary? =
        summary?.let {
            ConversationSummary(
                id = "summary",
                conversationId = conversationId,
                startSequence = 1,
                endSequence = 4,
                periodStart = 1,
                periodEnd = 2,
                content = it,
                createdAt = 2,
            )
        }

    override suspend fun activeProfileMemories(companionId: String): List<LongTermMemory> =
        profile

    override suspend fun activeMemories(companionId: String): List<LongTermMemory> = active

    override suspend fun searchActiveMemories(
        companionId: String,
        query: String,
    ): List<LongTermMemory> = active

    override suspend fun markMemoryAccessed(ids: List<String>, at: Long) {
        accessedIds += ids
    }

    override suspend fun addManualMemory(input: ManualMemoryInput): LongTermMemory =
        error("not used")

    override suspend fun updateMemoryStatement(id: String, statement: String) = Unit

    override suspend fun confirmMemory(id: String) = Unit

    override suspend fun rejectMemory(id: String) = Unit

    override suspend fun deleteMemory(id: String) = Unit

    override suspend fun forgetObservedSince(companionId: String, since: Long) = Unit

    override suspend fun expireTemporalMemories(companionId: String, at: Long) = Unit

    override suspend fun discardPendingCandidates(companionId: String) = Unit

    override suspend fun clearAllMemories(conversationId: String) = Unit
}

