package com.xinyu.companion.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.data.local.AppDatabase
import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.MessageStatus
import com.xinyu.companion.domain.memory.ManualMemoryInput
import com.xinyu.companion.domain.memory.MemoryCandidate
import com.xinyu.companion.domain.memory.MemoryConfirmationState
import com.xinyu.companion.domain.memory.MemoryKind
import com.xinyu.companion.domain.memory.MemoryScope
import com.xinyu.companion.domain.memory.MemorySensitivity
import com.xinyu.companion.domain.memory.MemorySourceKind
import com.xinyu.companion.domain.memory.MemoryStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var chatRepository: ChatRepository
    private lateinit var repository: MemoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatRepository = ChatRepository { database }
        repository = MemoryRepository { database }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `manual memory can be searched edited and deleted`() = runTest {
        val memory = repository.addManualMemory(
            ManualMemoryInput(
                companionId = CompanionProfile.MAIN_COMPANION_ID,
                kind = MemoryKind.PREFERENCE,
                statement = "用户喜欢在杭州的安静咖啡馆",
            ),
        )

        assertThat(memory.status).isEqualTo(MemoryStatus.ACTIVE)
        assertThat(repository.searchActiveMemories(memory.companionId, "杭州"))
            .hasSize(1)

        repository.updateMemoryStatement(memory.id, "用户喜欢杭州的独立咖啡馆")
        assertThat(repository.searchActiveMemories(memory.companionId, "独立咖啡馆"))
            .hasSize(1)

        repository.deleteMemory(memory.id)
        assertThat(repository.observeMemories(memory.companionId).first()).isEmpty()
    }

    @Test
    fun `unrelated active memories are not returned as fallback context`() = runTest {
        repository.addManualMemory(
            ManualMemoryInput(
                companionId = CompanionProfile.MAIN_COMPANION_ID,
                kind = MemoryKind.PREFERENCE,
                statement = "用户喜欢安静咖啡馆。",
            ),
        )

        assertThat(
            repository.searchActiveMemories(
                CompanionProfile.MAIN_COMPANION_ID,
                "杭州面试",
            ),
        ).isEmpty()
    }

    @Test
    fun `Chinese question retrieves a related memory by content words`() = runTest {
        repository.addManualMemory(
            ManualMemoryInput(
                companionId = CompanionProfile.MAIN_COMPANION_ID,
                kind = MemoryKind.PLAN,
                statement = "用户计划周五去杭州参加面试。",
            ),
        )

        assertThat(
            repository.searchActiveMemories(
                CompanionProfile.MAIN_COMPANION_ID,
                "你还记得杭州的面试吗",
            ),
        ).hasSize(1)
    }

    @Test
    fun `completed extraction stores summary memory and evidence transactionally`() = runTest {
        chatRepository.ensureSeeded("c1", CompanionProfile())
        val source = completeTurn("c1", "我喜欢先听结论")
        val job = repository.enqueue("c1", CompanionProfile.MAIN_COMPANION_ID)!!
        val input = repository.loadJobInput(job)!!

        repository.markJobRunning(job.id)
        repository.completeJob(
            jobId = job.id,
            conversationId = "c1",
            summary = "2026-11-25，用户明确表示喜欢先听结论。",
            candidates = listOf(
                candidate(
                    kind = MemoryKind.PREFERENCE,
                    objectText = "先听结论",
                    statement = "用户偏好先听结论再听解释。",
                    sourceIds = listOf(source.user.id, source.assistant.id),
                ),
            ),
        )

        val memories = repository.observeMemories(CompanionProfile.MAIN_COMPANION_ID).first()
        assertThat(memories).hasSize(1)
        assertThat(memories.single().confirmationState)
            .isEqualTo(MemoryConfirmationState.CONFIRMED)
        assertThat(repository.latestSummary("c1")!!.content).contains("喜欢先听结论")
        assertThat(repository.observeLatestJob("c1").first()!!.status.name)
            .isEqualTo("COMPLETED")

        val evidenceCount = queryCount(
            "SELECT COUNT(*) FROM memory_sources WHERE memoryId = '${memories.single().id}'",
        )
        assertThat(evidenceCount).isEqualTo(2)
    }

    @Test
    fun `duplicate fact reuses one memory and adds evidence`() = runTest {
        chatRepository.ensureSeeded("c1", CompanionProfile())
        val first = completeTurn("c1", "我喜欢先听结论")
        commitSingleMemory(
            "c1",
            first.user.id,
            first.assistant.id,
            "先听结论",
            kind = MemoryKind.PREFERENCE,
        )

        val second = completeTurn("c1", "还是先说结论吧")
        commitSingleMemory(
            "c1",
            second.user.id,
            second.assistant.id,
            "先听结论",
            kind = MemoryKind.PREFERENCE,
        )

        val memories = repository.activeMemories(CompanionProfile.MAIN_COMPANION_ID)
        assertThat(memories).hasSize(1)
        val evidenceCount = queryCount(
            "SELECT COUNT(*) FROM memory_sources WHERE memoryId = '${memories.single().id}'",
        )
        assertThat(evidenceCount).isEqualTo(4)
    }

    @Test
    fun `new fact supersedes old fact without deleting history`() = runTest {
        chatRepository.ensureSeeded("c1", CompanionProfile())
        val first = completeTurn("c1", "我喜欢咖啡")
        commitSingleMemory(
            conversationId = "c1",
            userId = first.user.id,
            assistantId = first.assistant.id,
            statement = "用户喜欢咖啡。",
            objectText = "咖啡",
            observedAt = 1_000L,
            kind = MemoryKind.PREFERENCE,
        )

        val second = completeTurn("c1", "我现在更喜欢茶")
        commitSingleMemory(
            conversationId = "c1",
            userId = second.user.id,
            assistantId = second.assistant.id,
            statement = "用户更喜欢茶。",
            objectText = "茶",
            observedAt = 2_000L,
            kind = MemoryKind.PREFERENCE,
        )

        val memories = repository.observeMemories(CompanionProfile.MAIN_COMPANION_ID).first()
        assertThat(memories).hasSize(2)
        val old = memories.first { it.objectText == "咖啡" }
        val current = memories.first { it.objectText == "茶" }
        assertThat(old.status).isEqualTo(MemoryStatus.SUPERSEDED)
        assertThat(old.supersededById).isEqualTo(current.id)
        assertThat(old.validTo).isEqualTo(current.validFrom)
    }

    @Test
    fun `ordinary event stays pending with a bounded lifetime`() = runTest {
        chatRepository.ensureSeeded("c1", CompanionProfile())
        val turn = completeTurn("c1", "今天面试不太顺")
        val job = repository.enqueue("c1", CompanionProfile.MAIN_COMPANION_ID)!!
        repository.markJobRunning(job.id)
        repository.completeJob(
            jobId = job.id,
            conversationId = "c1",
            summary = "【近期经历】\n- 用户当天面试不顺。",
            candidates = listOf(
                candidate(
                    kind = MemoryKind.EVENT,
                    objectText = "面试不顺",
                    statement = "用户当天面试受挫。",
                    sourceIds = listOf(turn.user.id),
                ),
            ),
        )

        val memory = repository.observeMemories(CompanionProfile.MAIN_COMPANION_ID)
            .first()
            .single()
        assertThat(memory.status).isEqualTo(MemoryStatus.CANDIDATE)
        assertThat(memory.confirmationState).isEqualTo(MemoryConfirmationState.UNCONFIRMED)
        assertThat(memory.validTo).isNotNull()
        assertThat(repository.activeMemories(CompanionProfile.MAIN_COMPANION_ID)).isEmpty()
    }

    @Test
    fun `location is sensitive even when the model calls it normal`() = runTest {
        chatRepository.ensureSeeded("c1", CompanionProfile())
        val turn = completeTurn("c1", "我现在在苏州")
        val job = repository.enqueue("c1", CompanionProfile.MAIN_COMPANION_ID)!!
        repository.markJobRunning(job.id)
        repository.completeJob(
            jobId = job.id,
            conversationId = "c1",
            summary = "",
            candidates = listOf(
                candidate(
                    kind = MemoryKind.FACT,
                    objectText = "苏州",
                    statement = "用户目前所在城市是苏州。",
                    sourceIds = listOf(turn.user.id),
                ),
            ),
        )

        val memory = repository.observeMemories(CompanionProfile.MAIN_COMPANION_ID)
            .first()
            .single()
        assertThat(memory.sensitivity).isEqualTo(MemorySensitivity.SENSITIVE)
        assertThat(memory.status).isEqualTo(MemoryStatus.CANDIDATE)
        assertThat(repository.activeMemories(CompanionProfile.MAIN_COMPANION_ID)).isEmpty()
    }

    @Test
    fun `auto promotion uses only the candidate's own evidence`() = runTest {
        chatRepository.ensureSeeded("c1", CompanionProfile())
        completeTurn("c1", "我喜欢安静")
        val unrelated = completeTurn("c1", "今天天气不错")
        val job = repository.enqueue("c1", CompanionProfile.MAIN_COMPANION_ID)!!
        repository.markJobRunning(job.id)
        repository.completeJob(
            jobId = job.id,
            conversationId = "c1",
            summary = "",
            candidates = listOf(
                candidate(
                    kind = MemoryKind.FACT,
                    objectText = "医生",
                    statement = "用户是医生。",
                    sourceIds = listOf(unrelated.user.id, unrelated.assistant.id),
                ),
            ),
        )

        val memory = repository.observeMemories(CompanionProfile.MAIN_COMPANION_ID)
            .first()
            .single()
        assertThat(memory.status).isEqualTo(MemoryStatus.CANDIDATE)
        assertThat(memory.confirmationState).isEqualTo(MemoryConfirmationState.UNCONFIRMED)
    }

    @Test
    fun `punctuation-only query does not fall back to arbitrary memories`() = runTest {
        repository.addManualMemory(
            ManualMemoryInput(
                companionId = CompanionProfile.MAIN_COMPANION_ID,
                kind = MemoryKind.PREFERENCE,
                statement = "用户喜欢安静咖啡馆。",
            ),
        )

        assertThat(
            repository.searchActiveMemories(
                CompanionProfile.MAIN_COMPANION_ID,
                "……？",
            ),
        ).isEmpty()
    }

    @Test
    fun `inferred memory stays candidate until the user confirms it`() = runTest {
        chatRepository.ensureSeeded("c1", CompanionProfile())
        val turn = completeTurn("c1", "今天不太想说话")
        val job = repository.enqueue("c1", CompanionProfile.MAIN_COMPANION_ID)!!
        repository.markJobRunning(job.id)
        repository.completeJob(
            jobId = job.id,
            conversationId = "c1",
            summary = "用户今天不想说话，但原因未知。",
            candidates = listOf(
                candidate(
                    statement = "用户可能不喜欢社交。",
                    objectText = "不喜欢社交",
                    sourceIds = listOf(turn.user.id, turn.assistant.id),
                ).copy(
                    sourceKind = MemorySourceKind.INFERRED,
                    confidence = 0.55,
                ),
            ),
        )

        val candidateMemory = repository.observeMemories(
            CompanionProfile.MAIN_COMPANION_ID,
        ).first().single()
        assertThat(candidateMemory.status).isEqualTo(MemoryStatus.CANDIDATE)
        assertThat(repository.activeMemories(CompanionProfile.MAIN_COMPANION_ID)).isEmpty()

        repository.confirmMemory(candidateMemory.id)

        assertThat(repository.activeMemories(CompanionProfile.MAIN_COMPANION_ID))
            .hasSize(1)
    }

    @Test
    fun `discard pending keeps active memories`() = runTest {
        repository.addManualMemory(
            ManualMemoryInput(
                companionId = CompanionProfile.MAIN_COMPANION_ID,
                kind = MemoryKind.BOUNDARY,
                statement = "用户不喜欢连续追问。",
            ),
        )
        chatRepository.ensureSeeded("c1", CompanionProfile())
        val turn = completeTurn("c1", "今天不太想说话")
        val job = repository.enqueue("c1", CompanionProfile.MAIN_COMPANION_ID)!!
        repository.markJobRunning(job.id)
        repository.completeJob(
            jobId = job.id,
            conversationId = "c1",
            summary = "",
            candidates = listOf(
                candidate(
                    statement = "用户可能需要更多独处时间。",
                    objectText = "独处",
                    sourceIds = listOf(turn.user.id),
                ).copy(sourceKind = MemorySourceKind.INFERRED, confidence = 0.6),
            ),
        )

        repository.discardPendingCandidates(CompanionProfile.MAIN_COMPANION_ID)

        val remaining = repository.observeMemories(CompanionProfile.MAIN_COMPANION_ID)
            .first()
        assertThat(remaining).hasSize(1)
        assertThat(remaining.single().kind).isEqualTo(MemoryKind.BOUNDARY)
        assertThat(remaining.single().status).isEqualTo(MemoryStatus.ACTIVE)
    }

    @Test
    fun `forget observed since removes only recent memories`() = runTest {
        chatRepository.ensureSeeded("c1", CompanionProfile())
        val oldTurn = completeTurn("c1", "旧记忆")
        setMessageTime(oldTurn.user.id, 1_000)
        setMessageTime(oldTurn.assistant.id, 1_000)
        commitSingleMemory(
            conversationId = "c1",
            userId = oldTurn.user.id,
            assistantId = oldTurn.assistant.id,
            statement = "用户喜欢旧书。",
            objectText = "旧书",
            observedAt = 1_000,
        )
        val recentTurn = completeTurn("c1", "新记忆")
        setMessageTime(recentTurn.user.id, 200_000)
        setMessageTime(recentTurn.assistant.id, 200_000)
        commitSingleMemory(
            conversationId = "c1",
            userId = recentTurn.user.id,
            assistantId = recentTurn.assistant.id,
            statement = "用户喜欢新唱片。",
            objectText = "新唱片",
            observedAt = 200_000,
        )

        repository.forgetObservedSince(CompanionProfile.MAIN_COMPANION_ID, since = 100_000)

        val remaining = repository.observeMemories(
            CompanionProfile.MAIN_COMPANION_ID,
        ).first()
        assertThat(remaining).hasSize(1)
        assertThat(remaining.single().objectText).isEqualTo("旧书")
    }

    @Test
    fun `clear all removes memories summaries and jobs`() = runTest {
        chatRepository.ensureSeeded("c1", CompanionProfile())
        val turn = completeTurn("c1", "记住我喜欢安静")
        commitSingleMemory("c1", turn.user.id, turn.assistant.id, "安静")

        repository.clearAllMemories("c1")

        assertThat(repository.observeMemories(CompanionProfile.MAIN_COMPANION_ID).first())
            .isEmpty()
        assertThat(repository.latestSummary("c1")).isNull()
        assertThat(queryCount("SELECT COUNT(*) FROM memory_jobs")).isEqualTo(0)
        assertThat(repository.enqueue("c1", CompanionProfile.MAIN_COMPANION_ID)).isNull()
    }

    private suspend fun commitSingleMemory(
        conversationId: String,
        userId: String,
        assistantId: String,
        statement: String,
        objectText: String = statement,
        observedAt: Long = System.currentTimeMillis(),
        kind: MemoryKind = MemoryKind.FACT,
    ) {
        val job = repository.enqueue(conversationId, CompanionProfile.MAIN_COMPANION_ID)!!
        repository.markJobRunning(job.id)
        repository.completeJob(
            jobId = job.id,
            conversationId = conversationId,
            summary = "用户表达：$statement",
            candidates = listOf(
                candidate(
                    kind = kind,
                    statement = statement,
                    objectText = objectText,
                    sourceIds = listOf(userId, assistantId),
                    observedAt = observedAt,
                ),
            ),
        )
    }

    private fun candidate(
        kind: MemoryKind = MemoryKind.FACT,
        objectText: String,
        statement: String,
        sourceIds: List<String>,
        observedAt: Long = System.currentTimeMillis(),
    ) = MemoryCandidate(
        kind = kind,
        scope = MemoryScope.USER,
        subject = "user",
        predicate = "states",
        objectText = objectText,
        statement = statement,
        sourceMessageIds = sourceIds,
        observedAt = observedAt,
        validFrom = observedAt,
        validTo = null,
        confidence = 0.95,
        sourceKind = MemorySourceKind.USER_EXPLICIT,
        sensitivity = MemorySensitivity.NORMAL,
        importance = 4,
    )

    private suspend fun completeTurn(conversationId: String, userText: String) =
        chatRepository.appendTurn(conversationId, userText).also { turn ->
            chatRepository.updateMessage(
                turn.assistant.id,
                "我记住了。",
                MessageStatus.COMPLETED,
            )
        }

    private fun queryCount(sql: String): Int =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun setMessageTime(messageId: String, createdAt: Long) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE messages SET createdAt = ? WHERE id = ?",
            arrayOf(createdAt, messageId),
        )
    }
}

