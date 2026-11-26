package com.xinyu.companion.ui.chat

import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.domain.ChatEngine
import com.xinyu.companion.domain.ChatMessage
import com.xinyu.companion.domain.ChatTurn
import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.ContextAssembler
import com.xinyu.companion.domain.ConversationStyle
import com.xinyu.companion.domain.GenerationEvent
import com.xinyu.companion.domain.MessageStatus
import com.xinyu.companion.domain.ReplyLength
import com.xinyu.companion.domain.SafetyPolicy
import com.xinyu.companion.domain.diagnostics.DiagnosticSink
import com.xinyu.companion.domain.diagnostics.GenerationOutcome
import com.xinyu.companion.domain.provider.ChatProvider
import com.xinyu.companion.domain.provider.ChatRequest
import com.xinyu.companion.domain.provider.ProviderCapabilities
import com.xinyu.companion.domain.provider.ProviderConfig
import com.xinyu.companion.domain.repository.ChatConversationStore
import com.xinyu.companion.domain.memory.MemoryContextProvider
import com.xinyu.companion.domain.memory.MemoryPromptContext
import com.xinyu.companion.domain.memory.MemoryWorkCoordinator
import com.xinyu.companion.domain.perception.PerceptionContextProvider
import com.xinyu.companion.domain.perception.PerceptionPromptContext
import com.xinyu.companion.domain.perception.PerceptionSettings
import com.xinyu.companion.domain.perception.PerceptionSettingsStore
import com.xinyu.companion.domain.settings.ChatStyleStore
import com.xinyu.companion.domain.settings.ProviderSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
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
    fun `initial load seeds conversation and reports configured key`() = runTest(dispatcher) {
        val dependencies = dependencies(ProviderConfig(apiKey = "saved-key"))

        val viewModel = dependencies.viewModel()
        advanceUntilIdle()

        assertThat(dependencies.store.seedCalls).isEqualTo(1)
        assertThat(dependencies.memoryCoordinator.startCalls).isEqualTo(1)
        assertThat(viewModel.settingsState.value.loaded).isTrue()
        assertThat(viewModel.settingsState.value.hasApiKey).isTrue()
    }

    @Test
    fun `successful connection test saves provider settings`() = runTest(dispatcher) {
        val dependencies = dependencies(ProviderConfig(apiKey = ""))
        val viewModel = dependencies.viewModel()
        advanceUntilIdle()

        viewModel.updateProviderKey("new-key")
        viewModel.updateProviderModel("deepseek-chat")
        viewModel.testAndSaveProvider()
        advanceUntilIdle()

        assertThat(dependencies.provider.lastTestedConfig?.apiKey).isEqualTo("new-key")
        assertThat(dependencies.settings.savedConfig?.apiKey).isEqualTo("new-key")
        assertThat(viewModel.settingsState.value.hasApiKey).isTrue()
        assertThat(viewModel.settingsState.value.keyDraft).isEmpty()
    }

    @Test
    fun `deleting key does not clear messages`() = runTest(dispatcher) {
        val dependencies = dependencies(ProviderConfig(apiKey = "saved-key"))
        val viewModel = dependencies.viewModel()
        advanceUntilIdle()

        viewModel.clearApiKey()
        advanceUntilIdle()

        assertThat(viewModel.settingsState.value.hasApiKey).isFalse()
        assertThat(dependencies.settings.clearCalls).isEqualTo(1)
        assertThat(dependencies.store.clearCalls).isEqualTo(0)
    }

    @Test
    fun `clearing messages reseeds the conversation without deleting key`() = runTest(dispatcher) {
        val dependencies = dependencies(ProviderConfig(apiKey = "saved-key"))
        val viewModel = dependencies.viewModel()
        advanceUntilIdle()

        viewModel.clearChatMessages()
        advanceUntilIdle()

        assertThat(dependencies.store.clearCalls).isEqualTo(1)
        assertThat(dependencies.store.seedCalls).isEqualTo(2)
        assertThat(dependencies.settings.clearCalls).isEqualTo(0)
        assertThat(viewModel.settingsState.value.hasApiKey).isTrue()
    }

    @Test
    fun `failed connection test does not save provider settings`() = runTest(dispatcher) {
        val dependencies = dependencies(ProviderConfig(apiKey = ""))
        dependencies.provider.testResult = Result.failure(Exception("invalid key"))
        val viewModel = dependencies.viewModel()
        advanceUntilIdle()

        viewModel.updateProviderKey("bad-key")
        viewModel.testAndSaveProvider()
        advanceUntilIdle()

        assertThat(dependencies.settings.savedConfig).isNull()
        assertThat(viewModel.settingsState.value.hasApiKey).isFalse()
        assertThat(viewModel.settingsState.value.error).contains("invalid key")
    }

    @Test
    fun `conversation style is persisted`() = runTest(dispatcher) {
        val dependencies = dependencies(ProviderConfig(apiKey = "saved-key"))
        val viewModel = dependencies.viewModel()
        advanceUntilIdle()

        viewModel.saveConversationStyle(
            temperature = 1.25f,
            closeness = 78f,
            replyLength = ReplyLength.DETAILED,
        )
        advanceUntilIdle()

        assertThat(dependencies.style.savedStyle).isEqualTo(
            ConversationStyle(
                temperature = 1.25,
                closeness = 78,
                replyLength = ReplyLength.DETAILED,
            ),
        )
    }

    @Test
    fun `send waits for conversation seeding to finish`() = runTest(dispatcher) {
        val dependencies = dependencies(ProviderConfig(apiKey = "saved-key"))
        val seedGate = CompletableDeferred<Unit>()
        dependencies.store.seedGate = seedGate
        val viewModel = dependencies.viewModel()
        advanceUntilIdle()

        viewModel.updateInput("你好")
        viewModel.send()
        assertThat(dependencies.store.appendCalls).isEqualTo(0)

        seedGate.complete(Unit)
        advanceUntilIdle()
        viewModel.send()
        advanceUntilIdle()

        assertThat(dependencies.store.appendCalls).isEqualTo(1)
    }

    @Test
    fun `searchable message pauses until user confirms an edited query`() = runTest(dispatcher) {
        val dependencies = dependencies(ProviderConfig(apiKey = "saved-key"))
        dependencies.perceptionSettings.current = PerceptionSettings(
            webSearchEnabled = true,
            searxngUrl = "https://search.example",
        )
        val viewModel = dependencies.viewModel()
        advanceUntilIdle()

        viewModel.updateInput("查一下张伟的失信记录")
        viewModel.send()
        advanceUntilIdle()

        assertThat(dependencies.store.appendCalls).isEqualTo(0)

        viewModel.updateSearchQuery("失信被执行人 公开信息")
        viewModel.confirmSearchAndSend()
        advanceUntilIdle()

        assertThat(dependencies.store.appendCalls).isEqualTo(1)
        assertThat(dependencies.perception.lastApprovedQuery)
            .isEqualTo("失信被执行人 公开信息")
    }

    @Test
    fun `skipping search sends chat without reusing a search approval`() = runTest(dispatcher) {
        val dependencies = dependencies(ProviderConfig(apiKey = "saved-key"))
        dependencies.perceptionSettings.current = PerceptionSettings(
            webSearchEnabled = true,
            searxngUrl = "https://search.example",
        )
        val viewModel = dependencies.viewModel()
        advanceUntilIdle()

        viewModel.updateInput("查一下今天的新闻")
        viewModel.send()
        viewModel.sendWithoutSearch()
        advanceUntilIdle()

        assertThat(dependencies.store.appendCalls).isEqualTo(1)
        assertThat(dependencies.perception.lastApprovedQuery).isNull()
    }

    @Test
    fun `search confirmation cannot be submitted twice`() = runTest(dispatcher) {
        val dependencies = dependencies(ProviderConfig(apiKey = "saved-key"))
        dependencies.perceptionSettings.current = PerceptionSettings(
            webSearchEnabled = true,
            searxngUrl = "https://search.example",
        )
        val viewModel = dependencies.viewModel()
        advanceUntilIdle()

        viewModel.updateInput("查一下今天的新闻")
        viewModel.send()
        viewModel.confirmSearchAndSend()
        viewModel.confirmSearchAndSend()
        advanceUntilIdle()

        assertThat(dependencies.store.appendCalls).isEqualTo(1)
    }

    private fun dependencies(config: ProviderConfig): TestDependencies {
        val store = FakeConversationStore()
        val settings = FakeProviderSettingsStore(config)
        val style = FakeChatStyleStore()
        val provider = FakeProvider()
        val memoryCoordinator = FakeMemoryWorkCoordinator()
        val perceptionSettings = FakePerceptionSettingsStore()
        val perception = FakePerceptionContextProvider()
        val diagnostics = object : DiagnosticSink {
            override fun recordGeneration(
                assistantMessageId: String,
                providerId: String,
                model: String,
                outcome: GenerationOutcome,
                durationMs: Long,
                errorCode: String?,
            ) = Unit
        }
        val engine = ChatEngine(
            store = store,
            provider = provider,
            settingsStore = settings,
            styleStore = style,
            contextAssembler = ContextAssembler(),
            memoryContextProvider = FakeMemoryContextProvider(),
            memoryCoordinator = memoryCoordinator,
            perceptionContextProvider = perception,
            safetyPolicy = SafetyPolicy(),
            diagnostics = diagnostics,
            ioDispatcher = dispatcher,
            scope = scope,
        )
        return TestDependencies(
            store,
            settings,
            style,
            provider,
            engine,
            memoryCoordinator,
            perceptionSettings,
            perception,
        )
    }
}

private data class TestDependencies(
    val store: FakeConversationStore,
    val settings: FakeProviderSettingsStore,
    val style: FakeChatStyleStore,
    val provider: FakeProvider,
    val engine: ChatEngine,
    val memoryCoordinator: FakeMemoryWorkCoordinator,
    val perceptionSettings: FakePerceptionSettingsStore,
    val perception: FakePerceptionContextProvider,
) {
    fun viewModel() = ChatViewModel(
        store = store,
        apiSettingsStore = settings,
        styleStore = style,
        chatProvider = provider,
        chatEngine = engine,
        memoryCoordinator = memoryCoordinator,
        perceptionSettingsStore = perceptionSettings,
        ioDispatcher = Dispatchers.Main,
    )
}

private class FakeMemoryContextProvider : MemoryContextProvider {
    override suspend fun build(
        companionId: String,
        conversationId: String,
        query: String,
    ): MemoryPromptContext = MemoryPromptContext()
}

private class FakePerceptionSettingsStore(
    var current: PerceptionSettings = PerceptionSettings(),
) : PerceptionSettingsStore {
    override val settings = MutableStateFlow(current)

    override fun load(): PerceptionSettings = current

    override suspend fun save(settings: PerceptionSettings) {
        current = settings
        this.settings.value = settings
    }
}

private class FakePerceptionContextProvider : PerceptionContextProvider {
    var lastApprovedQuery: String? = null

    override suspend fun build(
        query: String,
        approvedSearchQuery: String?,
    ): PerceptionPromptContext {
        lastApprovedQuery = approvedSearchQuery
        return PerceptionPromptContext(currentTimeLabel = "2026-11-25T12:00:00+08:00")
    }
}

private class FakeMemoryWorkCoordinator : MemoryWorkCoordinator {
    var startCalls = 0

    override val isProcessing: StateFlow<Boolean> = MutableStateFlow(false)

    override fun start(conversationId: String, companion: CompanionProfile) {
        startCalls += 1
    }

    override fun onTurnCompleted(conversationId: String, companion: CompanionProfile) = Unit

    override fun consolidateNow(conversationId: String, companion: CompanionProfile) = Unit
}

private class FakeProviderSettingsStore(
    private var config: ProviderConfig,
) : ProviderSettingsStore {
    var savedConfig: ProviderConfig? = null
    var clearCalls = 0

    override fun load(): ProviderConfig = config

    override fun save(config: ProviderConfig) {
        this.config = config
        savedConfig = config
    }

    override fun clearApiKey() {
        clearCalls += 1
        config = config.copy(apiKey = "")
    }
}

private class FakeChatStyleStore : ChatStyleStore {
    private var style = ConversationStyle()
    var savedStyle: ConversationStyle? = null

    override fun load(): ConversationStyle = style

    override fun save(style: ConversationStyle) {
        this.style = style
        savedStyle = style
    }
}

private class FakeProvider : ChatProvider {
    var lastTestedConfig: ProviderConfig? = null
    var testResult: Result<Unit> = Result.success(Unit)

    override val id: String = "fake"
    override val capabilities = ProviderCapabilities(
        streaming = true,
        images = false,
        tools = false,
        usageReporting = false,
    )

    override fun stream(request: ChatRequest): Flow<GenerationEvent> =
        flowOf(GenerationEvent.Completed)

    override suspend fun testConnection(config: ProviderConfig): Result<Unit> {
        lastTestedConfig = config
        return testResult
    }
}

private class FakeConversationStore : ChatConversationStore {
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    var seedCalls = 0
    var clearCalls = 0
    var appendCalls = 0
    var seedGate: CompletableDeferred<Unit>? = null

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = messages

    override suspend fun ensureSeeded(
        conversationId: String,
        companion: CompanionProfile,
    ) {
        seedCalls += 1
        seedGate?.await()
    }

    override suspend fun appendTurn(
        conversationId: String,
        text: String,
    ): ChatTurn {
        appendCalls += 1
        val user = ChatMessage(
            id = "u$appendCalls",
            conversationId = conversationId,
            role = com.xinyu.companion.domain.MessageRole.USER,
            sequence = appendCalls.toLong() * 2 - 1,
            content = text,
            status = MessageStatus.COMPLETED,
            createdAt = 1,
        )
        val assistant = ChatMessage(
            id = "a$appendCalls",
            conversationId = conversationId,
            role = com.xinyu.companion.domain.MessageRole.ASSISTANT,
            sequence = appendCalls.toLong() * 2,
            content = "",
            status = MessageStatus.STREAMING,
            createdAt = 1,
            replyToMessageId = user.id,
        )
        messages.value += listOf(user, assistant)
        return ChatTurn(user, assistant)
    }

    override suspend fun prepareRetry(assistantMessageId: String): ChatTurn? = null

    override suspend fun updateMessage(
        id: String,
        content: String,
        status: MessageStatus,
    ) = Unit

    override suspend fun recoverIncomplete(conversationId: String) = Unit

    override suspend fun getMessages(conversationId: String): List<ChatMessage> =
        messages.value

    override suspend fun clearMessages(conversationId: String) {
        clearCalls += 1
        messages.value = emptyList()
    }
}
