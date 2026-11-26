package com.xinyu.companion.domain

import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.domain.diagnostics.DiagnosticSink
import com.xinyu.companion.domain.diagnostics.GenerationOutcome
import com.xinyu.companion.domain.memory.MemoryContextProvider
import com.xinyu.companion.domain.memory.MemoryPromptContext
import com.xinyu.companion.domain.memory.MemoryWorkCoordinator
import com.xinyu.companion.domain.perception.PerceptionContextProvider
import com.xinyu.companion.domain.perception.PerceptionPromptContext
import com.xinyu.companion.domain.perception.PerceptionSettings
import com.xinyu.companion.domain.perception.PerceptionSettingsStore
import com.xinyu.companion.domain.perception.WebSearchProvider
import com.xinyu.companion.domain.perception.WebSearchResponse
import com.xinyu.companion.domain.perception.WebSearchResult
import com.xinyu.companion.domain.provider.ChatProvider
import com.xinyu.companion.domain.provider.ChatRequest
import com.xinyu.companion.domain.provider.ProviderCapabilities
import com.xinyu.companion.domain.provider.ProviderConfig
import com.xinyu.companion.domain.repository.ChatConversationStore
import com.xinyu.companion.domain.settings.ChatStyleStore
import com.xinyu.companion.domain.settings.ProviderSettingsStore
import com.xinyu.companion.domain.tools.CompanionTool
import com.xinyu.companion.domain.tools.ToolExecutionContext
import com.xinyu.companion.domain.tools.ToolExecutionResult
import com.xinyu.companion.domain.tools.ToolRegistry
import com.xinyu.companion.domain.tools.WebSearchTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
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

@OptIn(ExperimentalCoroutinesApi::class)
class ChatEngineToolLoopTest {
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
    fun `executes tool call and streams grounded final answer with weather attribution`() =
        runTest(dispatcher) {
            val store = LoopFakeStore()
            val provider = LoopFakeProvider(
                flowOf(
                    GenerationEvent.ToolCallDelta(0, "call_w", "get_weather", """{"city":"苏州"}"""),
                    GenerationEvent.Completed,
                ),
                flowOf(
                    GenerationEvent.Delta("苏州现在 19 度，有点微凉。"),
                    GenerationEvent.Completed,
                ),
            )
            val weatherTool = StaticTool(
                name = "get_weather",
                status = "正在查询实时天气…",
                result = ToolExecutionResult(content = "苏州：晴，19.0°C", usedWeather = true),
            )
            val engine = engine(store, provider, ToolRegistry(listOf(weatherTool)))

            assertThat(engine.send("c1", CompanionProfile(), "苏州冷吗")).isTrue()
            advanceUntilIdle()

            assertThat(provider.requests).hasSize(2)
            assertThat(provider.requests[1].context.any { it.role == "tool" && it.toolCallId == "call_w" })
                .isTrue()
            assertThat(store.messages.value.last().content).contains("苏州现在 19 度")
            assertThat(store.messages.value.last().content).contains("天气数据：Open-Meteo")
            assertThat(store.messages.value.last().status).isEqualTo(MessageStatus.COMPLETED)
        }

    @Test
    fun `enforces maxToolSteps by disabling tools on final step`() = runTest(dispatcher) {
        val store = LoopFakeStore()
        val provider = LoopFakeProvider(
            flowOf(
                GenerationEvent.ToolCallDelta(0, "call_1", "echo_tool", "{}"),
                GenerationEvent.Completed,
            ),
            flowOf(
                GenerationEvent.ToolCallDelta(0, "call_2", "echo_tool", "{}"),
                GenerationEvent.Completed,
            ),
            flowOf(
                GenerationEvent.Delta("已根据前两步结果总结。"),
                GenerationEvent.Completed,
            ),
        )
        val echoTool = StaticTool(
            name = "echo_tool",
            status = "正在调用工具…",
            result = ToolExecutionResult(content = "step-ok"),
        )
        val engine = engine(store, provider, ToolRegistry(listOf(echoTool)), maxToolSteps = 2)

        engine.send("c1", CompanionProfile(), "多步任务")
        advanceUntilIdle()

        assertThat(provider.requests).hasSize(3)
        assertThat(provider.requests[0].tools).isNotEmpty()
        assertThat(provider.requests[1].tools).isNotEmpty()
        assertThat(provider.requests[2].tools).isEmpty()
        assertThat(store.messages.value.last().content).isEqualTo("已根据前两步结果总结。")
        assertThat(store.messages.value.last().status).isEqualTo(MessageStatus.COMPLETED)
    }

    @Test
    fun `stop immediately interrupts a suspended tool call`() = runTest(dispatcher) {
        val store = LoopFakeStore()
        val gate = CompletableDeferred<Unit>()
        val provider = LoopFakeProvider(
            flowOf(
                GenerationEvent.ToolCallDelta(0, "call_slow", "slow_tool", "{}"),
                GenerationEvent.Completed,
            ),
        )
        val slowTool = object : CompanionTool {
            override val statusLabel = "慢工具…"
            override val definition = ToolDefinition("slow_tool", "slow", """{"type":"object"}""")
            override suspend fun execute(
                argumentsJson: String,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                gate.await()
                return ToolExecutionResult("never")
            }
        }
        val engine = engine(store, provider, ToolRegistry(listOf(slowTool)))

        engine.send("c1", CompanionProfile(), "执行慢工具")
        runCurrent()
        assertThat(engine.state.value.isStreaming).isTrue()

        engine.stop()
        advanceUntilIdle()

        assertThat(engine.state.value.isStreaming).isFalse()
        assertThat(store.messages.value.last().status).isEqualTo(MessageStatus.INTERRUPTED)
    }

    @Test
    fun `mid turn web_search tool pauses for user approval and cites verified source`() =
        runTest(dispatcher) {
            val store = LoopFakeStore()
            var executedQuery: String? = null
            val webSearchTool = WebSearchTool(
                settingsStore = object : PerceptionSettingsStore {
                    val value = PerceptionSettings(
                        webSearchEnabled = true,
                        searxngUrl = "https://search.example",
                    )
                    override val settings: Flow<PerceptionSettings> = MutableStateFlow(value)
                    override fun load(): PerceptionSettings = value
                    override suspend fun save(settings: PerceptionSettings) = Unit
                },
                searchProvider = object : WebSearchProvider {
                    override suspend fun search(
                        baseUrl: String,
                        query: String,
                        maxResults: Int,
                    ): Result<WebSearchResponse> {
                        executedQuery = query
                        return Result.success(
                            WebSearchResponse(
                                query = query,
                                results = listOf(
                                    WebSearchResult(
                                        title = "官方公告",
                                        url = "https://example.org/announce",
                                        snippet = "已正式发布",
                                    ),
                                ),
                                searchedAt = 1,
                            ),
                        )
                    }
                },
            )
            val provider = LoopFakeProvider(
                flowOf(
                    GenerationEvent.ToolCallDelta(
                        0,
                        "call_s",
                        WebSearchTool.NAME,
                        """{"query":"今年图灵奖得主"}""",
                    ),
                    GenerationEvent.Completed,
                ),
                flowOf(
                    GenerationEvent.Delta("已正式发布。[S1]"),
                    GenerationEvent.Completed,
                ),
            )
            val engine = engine(store, provider, ToolRegistry(listOf(webSearchTool)))

            engine.send("c1", CompanionProfile(), "今年图灵奖得主是谁？")
            runCurrent()

            assertThat(engine.state.value.pendingToolSearch?.suggestedQuery)
                .isEqualTo("今年图灵奖得主")
            assertThat(executedQuery).isNull()

            assertThat(engine.approveToolSearch("2026 图灵奖 公告")).isTrue()
            advanceUntilIdle()

            assertThat(executedQuery).isEqualTo("2026 图灵奖 公告")
            assertThat(engine.state.value.pendingToolSearch).isNull()
            assertThat(store.messages.value.last().content).contains("参考来源")
            assertThat(store.messages.value.last().content).contains("https://example.org/announce")
        }

    @Test
    fun `crisis turn disables all tools`() = runTest(dispatcher) {
        val store = LoopFakeStore()
        val provider = LoopFakeProvider(
            flowOf(GenerationEvent.Delta("我们先保证安全。"), GenerationEvent.Completed),
        )
        val tool = StaticTool("get_weather", "天气", ToolExecutionResult("ok"))
        val engine = engine(store, provider, ToolRegistry(listOf(tool)))

        engine.send("c1", CompanionProfile(), "我不想活了")
        advanceUntilIdle()

        assertThat(provider.requests.single().tools).isEmpty()
        assertThat(engine.state.value.safetyLevel).isEqualTo("crisis")
    }

    private fun engine(
        store: LoopFakeStore,
        provider: LoopFakeProvider,
        registry: ToolRegistry,
        maxToolSteps: Int = 4,
    ) = ChatEngine(
        store = store,
        provider = provider,
        settingsStore = object : ProviderSettingsStore {
            override fun load(): ProviderConfig = ProviderConfig(apiKey = "test-key")
            override fun save(config: ProviderConfig) = Unit
            override fun clearApiKey() = Unit
        },
        styleStore = object : ChatStyleStore {
            override fun load(): ConversationStyle = ConversationStyle()
            override fun save(style: ConversationStyle) = Unit
        },
        contextAssembler = ContextAssembler(),
        memoryContextProvider = object : MemoryContextProvider {
            override suspend fun build(
                companionId: String,
                conversationId: String,
                query: String,
            ): MemoryPromptContext = MemoryPromptContext()
        },
        memoryCoordinator = object : MemoryWorkCoordinator {
            override val isProcessing: StateFlow<Boolean> = MutableStateFlow(false)
            override fun start(conversationId: String, companion: CompanionProfile) = Unit
            override fun onTurnCompleted(conversationId: String, companion: CompanionProfile) = Unit
            override fun consolidateNow(conversationId: String, companion: CompanionProfile) = Unit
        },
        perceptionContextProvider = object : PerceptionContextProvider {
            override suspend fun build(
                query: String,
                approvedSearchQuery: String?,
            ): PerceptionPromptContext = PerceptionPromptContext("2026-11-26T12:00:00+08:00")
        },
        safetyPolicy = SafetyPolicy(),
        diagnostics = object : DiagnosticSink {
            override fun recordGeneration(
                assistantMessageId: String,
                providerId: String,
                model: String,
                outcome: GenerationOutcome,
                durationMs: Long,
                errorCode: String?,
            ) = Unit
        },
        toolRegistry = registry,
        maxToolSteps = maxToolSteps,
        toolTimeoutMs = 5_000L,
        ioDispatcher = dispatcher,
        scope = engineScope,
    )
}

private class StaticTool(
    name: String,
    status: String,
    private val result: ToolExecutionResult,
) : CompanionTool {
    override val statusLabel: String = status
    override val definition = ToolDefinition(name, name, """{"type":"object"}""")
    override suspend fun execute(
        argumentsJson: String,
        context: ToolExecutionContext,
    ): ToolExecutionResult = result
}

private class LoopFakeProvider(
    vararg responses: Flow<GenerationEvent>,
) : ChatProvider {
    private val queue = ArrayDeque<Flow<GenerationEvent>>().apply { addAll(responses) }
    val requests = mutableListOf<ChatRequest>()
    override val id: String = "fake-tools"
    override val capabilities = ProviderCapabilities(
        streaming = true,
        images = false,
        tools = true,
        usageReporting = false,
    )

    override fun stream(request: ChatRequest): Flow<GenerationEvent> {
        requests += request
        return queue.removeFirstOrNull()
            ?: flowOf(GenerationEvent.Failed("missing", "无更多测试响应", false))
    }

    override suspend fun testConnection(config: ProviderConfig): Result<Unit> = Result.success(Unit)
}

private class LoopFakeStore : ChatConversationStore {
    private val state = MutableStateFlow<List<ChatMessage>>(emptyList())
    private var nextSequence = 1L
    val messages = state

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = state
    override suspend fun ensureSeeded(conversationId: String, companion: CompanionProfile) = Unit
    override suspend fun appendTurn(conversationId: String, text: String): ChatTurn {
        val user = ChatMessage(
            id = "u${nextSequence}",
            conversationId = conversationId,
            role = MessageRole.USER,
            sequence = nextSequence++,
            content = text,
            status = MessageStatus.COMPLETED,
            createdAt = 1,
        )
        val assistant = ChatMessage(
            id = "a${nextSequence}",
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            sequence = nextSequence++,
            content = "",
            status = MessageStatus.STREAMING,
            createdAt = 1,
            replyToMessageId = user.id,
        )
        state.value = state.value + user + assistant
        return ChatTurn(user, assistant)
    }

    override suspend fun prepareRetry(assistantMessageId: String): ChatTurn? = null
    override suspend fun updateMessage(id: String, content: String, status: MessageStatus) {
        state.value = state.value.map {
            if (it.id == id) it.copy(content = content, status = status) else it
        }
    }

    override suspend fun recoverIncomplete(conversationId: String) = Unit
    override suspend fun getMessages(conversationId: String): List<ChatMessage> = state.value
    override suspend fun clearMessages(conversationId: String) {
        state.value = emptyList()
    }
}
