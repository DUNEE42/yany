package com.xinyu.companion.domain.memory

import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `disabled automatic memory does not enqueue work`() = runTest(dispatcher) {
        val store = FakeStore()
        val coordinator = coordinator(
            store = store,
            settings = FakeSettings(enabled = false),
        )

        coordinator.onTurnCompleted("c1", CompanionProfile())
        advanceUntilIdle()

        assertThat(store.enqueueCalls).isEqualTo(0)
        assertThat(store.completedJobs).isEmpty()
    }

    @Test
    fun `twelve messages trigger extraction and transactional completion`() = runTest(dispatcher) {
        val store = FakeStore(messageCount = 12)
        val coordinator = coordinator(store, FakeSettings(enabled = true))
        val extractor = store.extractor
        extractor.result = Result.success(
            MemoryExtractionResult(
                summary = "用户最近在准备杭州面试。",
                candidates = listOf(candidate()),
            ),
        )

        coordinator.onTurnCompleted("c1", CompanionProfile())
        advanceUntilIdle()

        assertThat(store.enqueueCalls).isAtLeast(1)
        assertThat(store.runningJobs).hasSize(1)
        assertThat(store.completedJobs).hasSize(1)
        assertThat(store.completedJobs.single().second).hasSize(1)
        assertThat(store.failedJobs).isEmpty()
    }

    @Test
    fun `extractor failure is recorded without committing memory`() = runTest(dispatcher) {
        val store = FakeStore(messageCount = 12)
        store.extractor.result = Result.failure(IllegalStateException("模型不可用"))
        val coordinator = coordinator(store, FakeSettings(enabled = true))

        coordinator.onTurnCompleted("c1", CompanionProfile())
        advanceUntilIdle()

        assertThat(store.completedJobs).isEmpty()
        assertThat(store.failedJobs.single()).contains("模型不可用")
    }

    private fun coordinator(
        store: FakeStore,
        settings: FakeSettings,
    ) = MemoryCoordinator(
        store = store,
        extractor = store.extractor,
        settings = settings,
        scope = scope,
        ioDispatcher = dispatcher,
    )
}

private class FakeSettings(
    enabled: Boolean,
) : MemorySettingsStore {
    private val state = MutableStateFlow(enabled)
    override val autoMemoryEnabled: Flow<Boolean> = state

    override suspend fun setAutoMemoryEnabled(enabled: Boolean) {
        state.value = enabled
    }
}

private class FakeStore(
    private val messageCount: Long = 12,
) : MemoryStore {
    val extractor = FakeExtractor()
    var enqueueCalls = 0
    val runningJobs = mutableListOf<String>()
    val completedJobs = mutableListOf<Pair<String, List<MemoryCandidate>>>()
    val failedJobs = mutableListOf<String>()
    private var available = true

    override fun observeMemories(companionId: String): Flow<List<LongTermMemory>> =
        MutableStateFlow(emptyList())

    override fun observeLatestSummary(conversationId: String): Flow<ConversationSummary?> =
        MutableStateFlow(null)

    override fun observeLatestJob(conversationId: String): Flow<MemoryJob?> =
        MutableStateFlow(null)

    override suspend fun enqueue(conversationId: String, companionId: String): MemoryJob? {
        enqueueCalls += 1
        if (!available) return null
        available = false
        return MemoryJob(
            id = "job",
            conversationId = conversationId,
            companionId = companionId,
            fromSequence = 1,
            toSequence = messageCount,
            status = MemoryJobStatus.PENDING,
            attempts = 0,
            lastError = null,
            scheduledAt = 1,
            startedAt = null,
            completedAt = null,
        )
    }

    override suspend fun recoverRunningJobs() = Unit

    override suspend fun loadJobInput(job: MemoryJob): MemoryJobInput = MemoryJobInput(
        job = job,
        messages = (1..messageCount).map { index ->
            MemorySourceMessage(
                id = "m$index",
                role = if (index % 2 == 0L) MessageRole.ASSISTANT else MessageRole.USER,
                sequence = index,
                content = "message-$index",
                createdAt = index,
            )
        },
        existingMemories = emptyList(),
    )

    override suspend fun markJobRunning(jobId: String) {
        runningJobs += jobId
    }

    override suspend fun completeJob(
        jobId: String,
        conversationId: String,
        summary: String,
        candidates: List<MemoryCandidate>,
    ) {
        completedJobs += jobId to candidates
    }

    override suspend fun failJob(jobId: String, message: String) {
        failedJobs += message
    }

    override suspend fun latestSummary(conversationId: String): ConversationSummary? = null

    override suspend fun activeProfileMemories(companionId: String): List<LongTermMemory> =
        emptyList()

    override suspend fun activeMemories(companionId: String): List<LongTermMemory> =
        emptyList()

    override suspend fun searchActiveMemories(
        companionId: String,
        query: String,
    ): List<LongTermMemory> = emptyList()

    override suspend fun markMemoryAccessed(ids: List<String>, at: Long) = Unit

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

private class FakeExtractor : MemoryExtractor {
    var result: Result<MemoryExtractionResult> = Result.failure(IllegalStateException("not set"))

    override suspend fun extract(input: MemoryJobInput): Result<MemoryExtractionResult> = result
}

private fun candidate() = MemoryCandidate(
    kind = MemoryKind.FACT,
    scope = MemoryScope.USER,
    subject = "user",
    predicate = "plans",
    objectText = "杭州面试",
    statement = "用户计划去杭州参加面试。",
    sourceMessageIds = listOf("m1", "m2"),
    observedAt = 1,
    validFrom = 1,
    validTo = null,
    confidence = 0.9,
    sourceKind = MemorySourceKind.USER_EXPLICIT,
    sensitivity = MemorySensitivity.NORMAL,
)

