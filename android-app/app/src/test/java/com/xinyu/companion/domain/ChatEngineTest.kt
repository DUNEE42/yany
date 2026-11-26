package com.xinyu.companion.domain

import com.xinyu.companion.domain.provider.ChatProvider
import com.xinyu.companion.domain.provider.ChatRequest
import com.xinyu.companion.domain.provider.ProviderCapabilities
import com.xinyu.companion.domain.provider.ProviderConfig
import com.xinyu.companion.domain.diagnostics.DiagnosticSink
import com.xinyu.companion.domain.diagnostics.GenerationOutcome
import com.xinyu.companion.domain.repository.ChatConversationStore
import com.xinyu.companion.domain.memory.MemoryContextProvider
import com.xinyu.companion.domain.memory.MemoryPromptContext
import com.xinyu.companion.domain.memory.MemoryWorkCoordinator
import com.xinyu.companion.domain.perception.PerceptionContextProvider
import com.xinyu.companion.domain.perception.PerceptionPromptContext
import com.xinyu.companion.domain.perception.WebSearchResponse
import com.xinyu.companion.domain.perception.WebSearchResult
import com.xinyu.companion.domain.settings.ChatStyleStore
import com.xinyu.companion.domain.settings.ProviderSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

@OptIn(ExperimentalCoroutinesApi::class)
class ChatEngineTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engineScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        engineScope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @After
    fun tearDown() {
        engineScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `send creates one user turn and completes assistant response`() = runTest(dispatcher) {
        val store = FakeStore()
        val provider = FakeProvider(
            flowOf(
                GenerationEvent.Delta("你"),
                GenerationEvent.Delta("好。"),
                GenerationEvent.Completed,
            ),
        )
        val engine = engine(store, provider)

        assertThat(engine.send("c1", CompanionProfile(), "你好")).isTrue()
        advanceUntilIdle()

        assertThat(store.messages.value.count { it.role == MessageRole.USER }).isEqualTo(1)
        assertThat(store.messages.value.last().content).isEqualTo("你好。")
        assertThat(store.messages.value.last().status).isEqualTo(MessageStatus.COMPLETED)
        assertThat(engine.state.value.isStreaming).isFalse()
        assertThat(engine.state.value.error).isNull()
    }

    @Test
    fun `retry reuses the same user message`() = runTest(dispatcher) {
        val store = FakeStore()
        val provider = FakeProvider(
            flowOf(
                GenerationEvent.Delta("半句"),
                GenerationEvent.Failed("network", "断网", true),
            ),
            flowOf(
                GenerationEvent.Delta("重试成功"),
                GenerationEvent.Completed,
            ),
        )
        val engine = engine(store, provider)

        engine.send("c1", CompanionProfile(), "你还记得吗")
        advanceUntilIdle()
        assertThat(engine.state.value.retryableAssistantMessageId).isNotNull()

        assertThat(engine.retry("c1", CompanionProfile())).isTrue()
        advanceUntilIdle()

        assertThat(store.messages.value.count { it.role == MessageRole.USER }).isEqualTo(1)
        assertThat(store.messages.value.size).isEqualTo(2)
        assertThat(store.messages.value.last().content).isEqualTo("重试成功")
        assertThat(store.messages.value.last().status).isEqualTo(MessageStatus.COMPLETED)
    }

    @Test
    fun `crisis input emits safety state and injects safety prompt`() = runTest(dispatcher) {
        val store = FakeStore()
        val provider = FakeProvider(
            flowOf(GenerationEvent.Delta("我们先保证安全。"), GenerationEvent.Completed),
        )
        val engine = engine(store, provider)

        engine.send("c1", CompanionProfile(), "我不想活了")
        advanceUntilIdle()

        assertThat(engine.state.value.safetyLevel).isEqualTo("crisis")
        assertThat(provider.lastRequest?.context?.first()?.content).contains("安全")
    }

    @Test
    fun `successful search appends verifiable source urls`() = runTest(dispatcher) {
        val store = FakeStore()
        val provider = FakeProvider(
            flowOf(GenerationEvent.Delta("今天适合带伞。[S1]"), GenerationEvent.Completed),
        )
        val engine = engine(
            store,
            provider,
            perception = StaticPerceptionContextProvider(
                PerceptionPromptContext(
                    currentTimeLabel = "2026-11-25T12:00:00+08:00",
                    search = WebSearchResponse(
                        query = "苏州天气",
                        results = listOf(
                            WebSearchResult(
                                title = "苏州天气",
                                url = "https://example.com/weather",
                                snippet = "有雨",
                            ),
                        ),
                        searchedAt = 1,
                    ),
                ),
            ),
        )

        engine.send("c1", CompanionProfile(), "查一下苏州天气")
        advanceUntilIdle()

        assertThat(store.messages.value.last().content).contains("参考来源")
        assertThat(store.messages.value.last().content).contains("[1]")
        assertThat(store.messages.value.last().content)
            .contains("https://example.com/weather")
    }

    @Test
    fun `uncited search results are not appended as answer sources`() = runTest(dispatcher) {
        val store = FakeStore()
        val provider = FakeProvider(
            flowOf(GenerationEvent.Delta("今天适合带伞。"), GenerationEvent.Completed),
        )
        val engine = engine(
            store,
            provider,
            perception = StaticPerceptionContextProvider(
                PerceptionPromptContext(
                    currentTimeLabel = "2026-11-25T12:00:00+08:00",
                    search = WebSearchResponse(
                        query = "苏州天气",
                        results = listOf(
                            WebSearchResult(
                                title = "苏州天气",
                                url = "https://example.com/weather",
                                snippet = "有雨",
                            ),
                        ),
                        searchedAt = 1,
                    ),
                ),
            ),
        )

        engine.send("c1", CompanionProfile(), "查一下苏州天气")
        advanceUntilIdle()

        assertThat(store.messages.value.last().content).doesNotContain("参考来源")
        assertThat(store.messages.value.last().content)
            .doesNotContain("https://example.com/weather")
    }

    @Test
    fun `stop interrupts response and blocks a concurrent second send`() = runTest(dispatcher) {
        val store = FakeStore()
        val waitingEvents = MutableSharedFlow<GenerationEvent>()
        val provider = FakeProvider(waitingEvents)
        val engine = engine(store, provider)

        assertThat(engine.send("c1", CompanionProfile(), "慢慢说")).isTrue()
        runCurrent()
        assertThat(engine.state.value.isStreaming).isTrue()
        assertThat(engine.send("c1", CompanionProfile(), "重复发送")).isFalse()

        engine.stop()
        advanceUntilIdle()

        assertThat(store.messages.value.count { it.role == MessageRole.USER }).isEqualTo(1)
        assertThat(store.messages.value.last().status).isEqualTo(MessageStatus.INTERRUPTED)
        assertThat(engine.state.value.isStreaming).isFalse()
        assertThat(engine.state.value.error).isNull()
    }

    @Test
    fun `reset does not let an old job overwrite the next run`() = runTest(dispatcher) {
        val store = FakeStore()
        val waitingEvents = MutableSharedFlow<GenerationEvent>()
        val provider = FakeProvider(
            waitingEvents,
            flowOf(GenerationEvent.Delta("第二次完成"), GenerationEvent.Completed),
        )
        val engine = engine(store, provider)

        assertThat(engine.send("c1", CompanionProfile(), "第一次")).isTrue()
        runCurrent()
        engine.resetSession()
        assertThat(engine.send("c1", CompanionProfile(), "第二次")).isTrue()
        advanceUntilIdle()

        assertThat(engine.state.value.isStreaming).isFalse()
        assertThat(engine.state.value.error).isNull()
        assertThat(store.messages.value.last().content).isEqualTo("第二次完成")
        assertThat(store.messages.value.last().status).isEqualTo(MessageStatus.COMPLETED)
    }

    @Test
    fun `conversation style reaches provider request`() = runTest(dispatcher) {
        val store = FakeStore()
        val provider = FakeProvider(
            flowOf(GenerationEvent.Delta("好"), GenerationEvent.Completed),
        )
        val style = ConversationStyle(
            temperature = 1.1,
            closeness = 80,
            replyLength = ReplyLength.DETAILED,
        )
        val engine = engine(store, provider, FakeStyleStore(style))

        engine.send("c1", CompanionProfile(), "今天怎么样")
        advanceUntilIdle()

        assertThat(provider.lastRequest?.conversationStyle).isEqualTo(style)
    }

    @Test
    fun `empty response becomes a retryable failure`() = runTest(dispatcher) {
        val store = FakeStore()
        val provider = FakeProvider(flowOf(GenerationEvent.Completed))
        val engine = engine(store, provider)

        engine.send("c1", CompanionProfile(), "在吗")
        advanceUntilIdle()

        assertThat(store.messages.value.last().status).isEqualTo(MessageStatus.FAILED)
        assertThat(engine.state.value.error).contains("没有返回内容")
        assertThat(engine.state.value.retryableAssistantMessageId).isNotNull()
    }

    @Test
    fun `non retryable provider failure does not offer retry`() = runTest(dispatcher) {
        val store = FakeStore()
        val provider = FakeProvider(
            flowOf(
                GenerationEvent.Failed(
                    code = "invalid_api_key",
                    message = "Key 无效",
                    retryable = false,
                ),
            ),
        )
        val engine = engine(store, provider)

        engine.send("c1", CompanionProfile(), "你好")
        advanceUntilIdle()

        assertThat(store.messages.value.last().status).isEqualTo(MessageStatus.FAILED)
        assertThat(engine.state.value.error).isEqualTo("Key 无效")
        assertThat(engine.state.value.retryableAssistantMessageId).isNull()
    }

    @Test
    fun `diagnostics record outcome without conversation text`() = runTest(dispatcher) {
        val store = FakeStore()
        val provider = FakeProvider(
            flowOf(GenerationEvent.Delta("回复"), GenerationEvent.Completed),
        )
        val diagnostics = FakeDiagnosticSink()
        val engine = engine(store, provider, diagnostics = diagnostics)

        engine.send("c1", CompanionProfile(), "不要写进日志的内容")
        advanceUntilIdle()

        assertThat(diagnostics.events).hasSize(1)
        assertThat(diagnostics.events.single().outcome)
            .isEqualTo(GenerationOutcome.COMPLETED)
        assertThat(diagnostics.events.single().providerId).isEqualTo("fake")
        assertThat(diagnostics.events.single().model).isEqualTo(ProviderConfig.DEFAULT_MODEL)
        assertThat(diagnostics.events.single().errorCode).isNull()
    }

    @Test
    fun `memory context reaches prompt and completed turn schedules background work`() =
        runTest(dispatcher) {
            val store = FakeStore()
            val provider = FakeProvider(
                flowOf(GenerationEvent.Delta("记得。"), GenerationEvent.Completed),
            )
            val memoryCoordinator = FakeMemoryWorkCoordinator()
            val engine = engine(store, provider, memoryCoordinator = memoryCoordinator)

            engine.send("c1", CompanionProfile(), "我说过什么")
            advanceUntilIdle()

            assertThat(provider.lastRequest!!.context.first().content)
                .contains("用户偏好先听结论")
            assertThat(memoryCoordinator.completedTurns).isEqualTo(1)
        }

    private fun engine(
        store: FakeStore,
        provider: FakeProvider,
        styleStore: FakeStyleStore = FakeStyleStore(),
        diagnostics: FakeDiagnosticSink = FakeDiagnosticSink(),
        memoryCoordinator: FakeMemoryWorkCoordinator = FakeMemoryWorkCoordinator(),
        perception: PerceptionContextProvider = FakePerceptionContextProvider(),
    ) = ChatEngine(
        store = store,
        provider = provider,
        settingsStore = FakeSettingsStore(),
        styleStore = styleStore,
        contextAssembler = ContextAssembler(),
        memoryContextProvider = FakeMemoryContextProvider(),
        memoryCoordinator = memoryCoordinator,
        perceptionContextProvider = perception,
        safetyPolicy = SafetyPolicy(),
        diagnostics = diagnostics,
        ioDispatcher = dispatcher,
        scope = engineScope,
    )
}

private class FakeMemoryContextProvider : MemoryContextProvider {
    override suspend fun build(
        companionId: String,
        conversationId: String,
        query: String,
    ): MemoryPromptContext = MemoryPromptContext(
        profileItems = listOf("用户偏好先听结论"),
    )
}

private class FakePerceptionContextProvider : PerceptionContextProvider {
    override suspend fun build(
        query: String,
        approvedSearchQuery: String?,
    ): PerceptionPromptContext =
        PerceptionPromptContext(currentTimeLabel = "2026-11-25T12:00:00+08:00")
}

private class StaticPerceptionContextProvider(
    private val context: PerceptionPromptContext,
) : PerceptionContextProvider {
    override suspend fun build(
        query: String,
        approvedSearchQuery: String?,
    ): PerceptionPromptContext = context
}

private class FakeMemoryWorkCoordinator : MemoryWorkCoordinator {
    var completedTurns = 0

    override val isProcessing: StateFlow<Boolean> = MutableStateFlow(false)

    override fun start(conversationId: String, companion: CompanionProfile) = Unit

    override fun onTurnCompleted(conversationId: String, companion: CompanionProfile) {
        completedTurns += 1
    }

    override fun consolidateNow(conversationId: String, companion: CompanionProfile) = Unit
}

private class FakeDiagnosticSink : DiagnosticSink {
    data class Event(
        val assistantMessageId: String,
        val providerId: String,
        val model: String,
        val outcome: GenerationOutcome,
        val durationMs: Long,
        val errorCode: String?,
    )

    val events = mutableListOf<Event>()

    override fun recordGeneration(
        assistantMessageId: String,
        providerId: String,
        model: String,
        outcome: GenerationOutcome,
        durationMs: Long,
        errorCode: String?,
    ) {
        events += Event(assistantMessageId, providerId, model, outcome, durationMs, errorCode)
    }
}

private class FakeStyleStore(
    private var style: ConversationStyle = ConversationStyle(),
) : ChatStyleStore {
    override fun load(): ConversationStyle = style

    override fun save(style: ConversationStyle) {
        this.style = style
    }
}

private class FakeProvider(
    vararg responses: Flow<GenerationEvent>,
) : ChatProvider {
    private val queue = ArrayDeque<Flow<GenerationEvent>>().apply {
        addAll(responses)
    }
    var lastRequest: ChatRequest? = null

    override val id: String = "fake"
    override val capabilities = ProviderCapabilities(
        streaming = true,
        images = false,
        tools = false,
        usageReporting = false,
    )

    override fun stream(request: ChatRequest): Flow<GenerationEvent> {
        lastRequest = request
        return queue.removeFirstOrNull()
            ?: flowOf(GenerationEvent.Failed("missing", "没有测试响应", false))
    }

    override suspend fun testConnection(config: ProviderConfig): Result<Unit> =
        Result.success(Unit)
}

private class FakeSettingsStore : ProviderSettingsStore {
    private var config = ProviderConfig(apiKey = "test-key")

    override fun load(): ProviderConfig = config

    override fun save(config: ProviderConfig) {
        this.config = config
    }

    override fun clearApiKey() {
        config = config.copy(apiKey = "")
    }
}

private class FakeStore : ChatConversationStore {
    private val state = MutableStateFlow<List<ChatMessage>>(emptyList())
    private var nextSequence = 1L

    val messages = state

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = state

    override suspend fun ensureSeeded(
        conversationId: String,
        companion: CompanionProfile,
    ) = Unit

    override suspend fun appendTurn(
        conversationId: String,
        text: String,
    ): ChatTurn {
        val now = System.currentTimeMillis()
        val user = ChatMessage(
            id = "u${nextSequence}",
            conversationId = conversationId,
            role = MessageRole.USER,
            sequence = nextSequence++,
            content = text,
            status = MessageStatus.COMPLETED,
            createdAt = now,
        )
        val assistant = ChatMessage(
            id = "a${nextSequence}",
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            sequence = nextSequence++,
            content = "",
            status = MessageStatus.STREAMING,
            createdAt = now,
            replyToMessageId = user.id,
        )
        state.value = state.value + user + assistant
        return ChatTurn(user, assistant)
    }

    override suspend fun prepareRetry(assistantMessageId: String): ChatTurn? {
        val assistant = state.value.firstOrNull { it.id == assistantMessageId } ?: return null
        val user = state.value.firstOrNull { it.id == assistant.replyToMessageId } ?: return null
        val reset = assistant.copy(content = "", status = MessageStatus.STREAMING)
        replace(reset)
        return ChatTurn(user, reset)
    }

    override suspend fun updateMessage(
        id: String,
        content: String,
        status: MessageStatus,
    ) {
        val message = state.value.firstOrNull { it.id == id } ?: return
        replace(message.copy(content = content, status = status))
    }

    override suspend fun recoverIncomplete(conversationId: String) = Unit

    override suspend fun getMessages(conversationId: String): List<ChatMessage> = state.value

    override suspend fun clearMessages(conversationId: String) {
        state.value = emptyList()
    }

    private fun replace(message: ChatMessage) {
        state.value = state.value.map { if (it.id == message.id) message else it }
    }
}
