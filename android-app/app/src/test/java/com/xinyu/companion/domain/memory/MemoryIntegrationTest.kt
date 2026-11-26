package com.xinyu.companion.domain.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.data.local.AppDatabase
import com.xinyu.companion.data.repository.ChatRepository
import com.xinyu.companion.data.repository.MemoryRepository
import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryIntegrationTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var database: AppDatabase
    private lateinit var chatRepository: ChatRepository
    private lateinit var memoryRepository: MemoryRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        chatRepository = ChatRepository { database }
        memoryRepository = MemoryRepository { database }
    }

    @After
    fun tearDown() {
        database.close()
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `completed conversation becomes retrievable memory evidence`() = runTest(dispatcher) {
        chatRepository.ensureSeeded("c1", CompanionProfile())
        repeat(6) { index ->
            val userText = if (index == 0) {
                "我喜欢先听结论再听解释，记住这个偏好"
            } else {
                "第${index + 1}句话"
            }
            val turn = chatRepository.appendTurn("c1", userText)
            chatRepository.updateMessage(
                turn.assistant.id,
                "回复${index + 1}",
                MessageStatus.COMPLETED,
            )
        }
        val extractor = SourceAwareExtractor()
        val coordinator = MemoryCoordinator(
            store = memoryRepository,
            extractor = extractor,
            settings = AlwaysEnabledMemorySettings,
            scope = scope,
            ioDispatcher = dispatcher,
        )

        coordinator.onTurnCompleted("c1", CompanionProfile())
        advanceUntilIdle()

        val job = memoryRepository.observeLatestJob("c1").first()
        assertThat(job!!.status).isEqualTo(MemoryJobStatus.COMPLETED)
        val memories = memoryRepository.activeMemories(CompanionProfile.MAIN_COMPANION_ID)
        assertThat(memories).hasSize(1)
        assertThat(extractor.lastInput!!.messages).hasSize(12)

        val promptContext = MemoryContextBuilder(memoryRepository).build(
            companionId = CompanionProfile.MAIN_COMPANION_ID,
            conversationId = "c1",
            query = "我是不是说过喜欢先听结论",
        )
        assertThat(promptContext.profileItems)
            .contains("用户偏好先听结论再听解释。")
        assertThat(memoryRepository.latestSummary("c1")!!.content)
            .contains("用户在这段对话中明确表达偏好")
    }

    private class SourceAwareExtractor : MemoryExtractor {
        var lastInput: MemoryJobInput? = null

        override suspend fun extract(input: MemoryJobInput): Result<MemoryExtractionResult> {
            lastInput = input
            val userMessage = input.messages.first { it.role.name == "USER" }
            return Result.success(
                MemoryExtractionResult(
                    summary = "用户在这段对话中明确表达偏好。",
                    candidates = listOf(
                        MemoryCandidate(
                            kind = MemoryKind.PREFERENCE,
                            scope = MemoryScope.USER,
                            subject = "user",
                            predicate = "prefers_response_order",
                            objectText = "先听结论",
                            statement = "用户偏好先听结论再听解释。",
                            sourceMessageIds = listOf(userMessage.id),
                            observedAt = userMessage.createdAt,
                            validFrom = userMessage.createdAt,
                            validTo = null,
                            confidence = 0.95,
                            sourceKind = MemorySourceKind.USER_EXPLICIT,
                            sensitivity = MemorySensitivity.NORMAL,
                            importance = 4,
                        ),
                    ),
                ),
            )
        }
    }

    private object AlwaysEnabledMemorySettings : MemorySettingsStore {
        override val autoMemoryEnabled: Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setAutoMemoryEnabled(enabled: Boolean) = Unit
    }
}

