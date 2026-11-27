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
import com.xinyu.companion.domain.SafetyPolicy
import com.xinyu.companion.domain.diagnostics.DiagnosticSink
import com.xinyu.companion.domain.diagnostics.GenerationOutcome
import com.xinyu.companion.domain.memory.MemoryContextProvider
import com.xinyu.companion.domain.memory.MemoryPromptContext
import com.xinyu.companion.domain.memory.MemoryWorkCoordinator
import com.xinyu.companion.domain.perception.PerceptionContextProvider
import com.xinyu.companion.domain.perception.PerceptionPromptContext
import com.xinyu.companion.domain.perception.PerceptionSettings
import com.xinyu.companion.domain.perception.PerceptionSettingsStore
import com.xinyu.companion.domain.provider.ChatProvider
import com.xinyu.companion.domain.provider.ChatRequest
import com.xinyu.companion.domain.provider.ProviderCapabilities
import com.xinyu.companion.domain.provider.ProviderConfig
import com.xinyu.companion.domain.repository.ChatConversationStore
import com.xinyu.companion.domain.settings.ChatStyleStore
import com.xinyu.companion.domain.settings.CompanionProfileStore
import com.xinyu.companion.domain.settings.ProviderSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
    fun `initial load seeds conversation and starts memory coordinator`() = runTest(dispatcher) {
        val dependencies = dependencies(ProviderConfig(apiKey = "saved-key"))

        val viewModel = dependencies.viewModel()
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertThat(dependencies.store.seedCalls).isEqualTo(1)
        assertThat(dependencies.memoryCoordinator.startCalls).isEqualTo(1)
        assertThat(viewModel.uiState.value.hasApiKey).isTrue()
        assertThat(viewModel.uiState.value.isConversationReady).isTrue()
        collector.cancel()
    }

    @Test
    fun `reacts to updated companion profile name and avatar path`() = runTest(dispatcher) {
        val dependencies = dependencies(ProviderConfig(apiKey = "saved-key"))
        val viewModel = dependencies.viewModel()
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        dependencies.companionProfile.save(
            CompanionProfile(name = "清屿", avatarPath = "/tmp/avatar.jpg"),
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.companionName).isEqualTo("清屿")
        assertThat(viewModel.uiState.value.companionAvatarPath).isEqualTo("/tmp/avatar.jpg")
        collector.cancel()
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

    private fun dependencies(config: ProviderConfig): ChatTestDependencies {
        val store = FakeChatConversationStore()
        val settings = FakeChatProviderSettingsStore(config)
        val companionProfile = FakeChatCompanionProfileStore()
        val style = FakeChatStyleStore()
        val provider = FakeChatProvider()
        val memoryCoordinator = FakeChatMemoryWorkCoordinator()
        val perceptionSettings = FakeChatPerceptionSettingsStore()
        val perception = FakeChatPerceptionContextProvider()
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
            memoryContextProvider = FakeChatMemoryContextProvider(),
            memoryCoordinator = memoryCoordinator,
            perceptionContextProvider = perception,
            safetyPolicy = SafetyPolicy(),
            diagnostics = diagnostics,
            ioDispatcher = dispatcher,
            scope = scope,
        )
        return ChatTestDependencies(
            store = store,
            settings = settings,
            companionProfile = companionProfile,
            engine = engine,
            memoryCoordinator = memoryCoordinator,
            perceptionSettings = perceptionSettings,
            perception = perception,
        )
    }
}

private data class ChatTestDependencies(
    val store: FakeChatConversationStore,
    val settings: FakeChatProviderSettingsStore,
    val companionProfile: FakeChatCompanionProfileStore,
    val engine: ChatEngine,
    val memoryCoordinator: FakeChatMemoryWorkCoordinator,
    val perceptionSettings: FakeChatPerceptionSettingsStore,
    val perception: FakeChatPerceptionContextProvider,
) {
    fun viewModel() = ChatViewModel(
        store = store,
        apiSettingsStore = settings,
        companionProfileStore = companionProfile,
        chatEngine = engine,
        memoryCoordinator = memoryCoordinator,
        perceptionSettingsStore = perceptionSettings,
        ioDispatcher = Dispatchers.Main,
    )
}

private class FakeChatCompanionProfileStore : CompanionProfileStore {
    private val mutable = MutableStateFlow(CompanionProfile())
    override val profile: StateFlow<CompanionProfile> = mutable.asStateFlow()

    override fun load(): CompanionProfile = mutable.value

    override fun save(profile: CompanionProfile): CompanionProfile {
        mutable.value = profile
        return profile
    }
}

private class FakeChatMemoryContextProvider : MemoryContextProvider {
    override suspend fun build(
        companionId: String,
        conversationId: String,
        query: String,
    ): MemoryPromptContext = MemoryPromptContext()
}

private class FakeChatPerceptionSettingsStore(
    var current: PerceptionSettings = PerceptionSettings(),
) : PerceptionSettingsStore {
    override val settings = MutableStateFlow(current)

    override fun load(): PerceptionSettings = current

    override suspend fun save(settings: PerceptionSettings) {
        current = settings
        this.settings.value = settings
    }
}

private class FakeChatPerceptionContextProvider : PerceptionContextProvider {
    var lastApprovedQuery: String? = null

    override suspend fun build(
        query: String,
        approvedSearchQuery: String?,
    ): PerceptionPromptContext {
        lastApprovedQuery = approvedSearchQuery
        return PerceptionPromptContext(currentTimeLabel = "2026-11-25T12:00:00+08:00")
    }
}

private class FakeChatMemoryWorkCoordinator : MemoryWorkCoordinator {
    var startCalls = 0

    override val isProcessing: StateFlow<Boolean> = MutableStateFlow(false)

    override fun start(conversationId: String, companion: CompanionProfile) {
        startCalls += 1
    }

    override fun onTurnCompleted(conversationId: String, companion: CompanionProfile) = Unit

    override fun consolidateNow(conversationId: String, companion: CompanionProfile) = Unit
}

private class FakeChatProviderSettingsStore(
    private var config: ProviderConfig,
) : ProviderSettingsStore {
    private val mutableConfigured = MutableStateFlow(config.isConfigured)
    override val configured: StateFlow<Boolean> = mutableConfigured.asStateFlow()

    override fun load(): ProviderConfig {
        mutableConfigured.value = config.isConfigured
        return config
    }

    override fun save(config: ProviderConfig) {
        this.config = config
        mutableConfigured.value = config.isConfigured
    }

    override fun clearApiKey() {
        config = config.copy(apiKey = "")
        mutableConfigured.value = false
    }
}

private class FakeChatStyleStore : ChatStyleStore {
    private var style = ConversationStyle()

    override fun load(): ConversationStyle = style

    override fun save(style: ConversationStyle) {
        this.style = style
    }
}

private class FakeChatProvider : ChatProvider {
    override val id: String = "fake"
    override val capabilities = ProviderCapabilities(
        streaming = true,
        images = false,
        tools = false,
        usageReporting = false,
    )

    override fun stream(request: ChatRequest): Flow<GenerationEvent> =
        flowOf(GenerationEvent.Completed)

    override suspend fun testConnection(config: ProviderConfig): Result<Unit> =
        Result.success(Unit)
}

private class FakeChatConversationStore : ChatConversationStore {
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    var seedCalls = 0
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
        messages.value = emptyList()
    }
}
