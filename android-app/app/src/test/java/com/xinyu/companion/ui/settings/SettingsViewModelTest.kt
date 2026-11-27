package com.xinyu.companion.ui.settings

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.data.settings.CompanionAvatarStorage
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
import com.xinyu.companion.domain.memory.ConversationSummary
import com.xinyu.companion.domain.memory.LongTermMemory
import com.xinyu.companion.domain.memory.ManualMemoryInput
import com.xinyu.companion.domain.memory.MemoryCandidate
import com.xinyu.companion.domain.memory.MemoryContextProvider
import com.xinyu.companion.domain.memory.MemoryJob
import com.xinyu.companion.domain.memory.MemoryJobInput
import com.xinyu.companion.domain.memory.MemoryPromptContext
import com.xinyu.companion.domain.memory.MemorySettingsStore
import com.xinyu.companion.domain.memory.MemoryStore
import com.xinyu.companion.domain.memory.MemoryWorkCoordinator
import com.xinyu.companion.domain.perception.PerceptionContextProvider
import com.xinyu.companion.domain.perception.PerceptionPromptContext
import com.xinyu.companion.domain.provider.ChatProvider
import com.xinyu.companion.domain.provider.ChatRequest
import com.xinyu.companion.domain.provider.ProviderCapabilities
import com.xinyu.companion.domain.provider.ProviderConfig
import com.xinyu.companion.domain.repository.ChatConversationStore
import com.xinyu.companion.domain.settings.ChatStyleStore
import com.xinyu.companion.domain.settings.CompanionProfileStore
import com.xinyu.companion.domain.settings.ProviderSettingsStore
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {
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
    fun `initial load reports configured key and companion profile`() = runTest(dispatcher) {
        val deps = dependencies(ProviderConfig(apiKey = "saved-key"))
        val viewModel = deps.viewModel()
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.loaded).isTrue()
        assertThat(viewModel.uiState.value.hasApiKey).isTrue()
        assertThat(viewModel.uiState.value.companionProfile.displayName).isEqualTo("林")
        collector.cancel()
    }

    @Test
    fun `successful connection test saves provider settings`() = runTest(dispatcher) {
        val deps = dependencies(ProviderConfig(apiKey = ""))
        val viewModel = deps.viewModel()
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateProviderKey("new-key")
        viewModel.updateProviderModel("deepseek-chat")
        viewModel.testAndSaveProvider()
        advanceUntilIdle()

        assertThat(deps.provider.lastTestedConfig?.apiKey).isEqualTo("new-key")
        assertThat(deps.settings.savedConfig?.apiKey).isEqualTo("new-key")
        assertThat(viewModel.uiState.value.hasApiKey).isTrue()
        assertThat(viewModel.uiState.value.keyDraft).isEmpty()
        collector.cancel()
    }

    @Test
    fun `failed connection test does not save provider settings`() = runTest(dispatcher) {
        val deps = dependencies(ProviderConfig(apiKey = ""))
        deps.provider.testResult = Result.failure(Exception("invalid key"))
        val viewModel = deps.viewModel()
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateProviderKey("bad-key")
        viewModel.testAndSaveProvider()
        advanceUntilIdle()

        assertThat(deps.settings.savedConfig).isNull()
        assertThat(viewModel.uiState.value.hasApiKey).isFalse()
        assertThat(viewModel.uiState.value.error).contains("invalid key")
        collector.cancel()
    }

    @Test
    fun `deleting key does not clear messages`() = runTest(dispatcher) {
        val deps = dependencies(ProviderConfig(apiKey = "saved-key"))
        val viewModel = deps.viewModel()
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.clearApiKey()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.hasApiKey).isFalse()
        assertThat(deps.settings.clearCalls).isEqualTo(1)
        assertThat(deps.conversationStore.clearCalls).isEqualTo(0)
        collector.cancel()
    }

    @Test
    fun `clearing messages reseeds conversation without deleting key`() = runTest(dispatcher) {
        val deps = dependencies(ProviderConfig(apiKey = "saved-key"))
        val viewModel = deps.viewModel()
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.clearChatMessages()
        advanceUntilIdle()

        assertThat(deps.conversationStore.clearCalls).isEqualTo(1)
        assertThat(deps.conversationStore.seedCalls).isEqualTo(1)
        assertThat(deps.settings.clearCalls).isEqualTo(0)
        assertThat(viewModel.uiState.value.hasApiKey).isTrue()
        collector.cancel()
    }

    @Test
    fun `conversation style is persisted`() = runTest(dispatcher) {
        val deps = dependencies(ProviderConfig(apiKey = "saved-key"))
        val viewModel = deps.viewModel()
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.saveConversationStyle(
            temperature = 1.25f,
            closeness = 78f,
            replyLength = ReplyLength.DETAILED,
        )
        advanceUntilIdle()

        assertThat(deps.style.savedStyle).isEqualTo(
            ConversationStyle(
                temperature = 1.25,
                closeness = 78,
                replyLength = ReplyLength.DETAILED,
            ),
        )
        collector.cancel()
    }

    @Test
    fun `updating companion nickname and avatar persists profile and resets cleanly`() =
        runTest(dispatcher) {
            val deps = dependencies(ProviderConfig(apiKey = "saved-key"))
            val viewModel = deps.viewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.updateCompanionNameDraft("清屿")
            viewModel.saveCompanionName()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.companionProfile.displayName).isEqualTo("清屿")

            viewModel.updateCompanionAvatar(Uri.parse("content://media/picker/1"))
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.companionProfile.avatarPath)
                .isEqualTo("/data/user/0/app/files/avatars/picked.jpg")

            viewModel.resetCompanionAvatar()
            advanceUntilIdle()

            assertThat(deps.avatarStorage.clearCalls).isEqualTo(1)
            assertThat(viewModel.uiState.value.companionProfile.avatarPath).isNull()
            collector.cancel()
        }

    private fun dependencies(config: ProviderConfig): SettingsTestDependencies {
        val settings = FakeSettingsProviderStore(config)
        val style = FakeSettingsStyleStore()
        val profile = FakeSettingsCompanionProfileStore()
        val avatarStorage = FakeAvatarStorage()
        val provider = FakeSettingsChatProvider()
        val conversationStore = FakeSettingsConversationStore()
        val memoryStore = FakeSettingsMemoryStore()
        val memorySettings = FakeSettingsMemorySettingsStore()
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
            store = conversationStore,
            provider = provider,
            settingsStore = settings,
            styleStore = style,
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
            diagnostics = diagnostics,
            ioDispatcher = dispatcher,
            scope = scope,
        )
        return SettingsTestDependencies(
            settings = settings,
            style = style,
            profile = profile,
            avatarStorage = avatarStorage,
            provider = provider,
            engine = engine,
            conversationStore = conversationStore,
            memoryStore = memoryStore,
            memorySettings = memorySettings,
        )
    }
}

private data class SettingsTestDependencies(
    val settings: FakeSettingsProviderStore,
    val style: FakeSettingsStyleStore,
    val profile: FakeSettingsCompanionProfileStore,
    val avatarStorage: FakeAvatarStorage,
    val provider: FakeSettingsChatProvider,
    val engine: ChatEngine,
    val conversationStore: FakeSettingsConversationStore,
    val memoryStore: FakeSettingsMemoryStore,
    val memorySettings: FakeSettingsMemorySettingsStore,
) {
    fun viewModel() = SettingsViewModel(
        apiSettingsStore = settings,
        styleStore = style,
        companionProfileStore = profile,
        avatarStorage = avatarStorage,
        chatProvider = provider,
        chatEngine = engine,
        conversationStore = conversationStore,
        memoryStore = memoryStore,
        memorySettingsStore = memorySettings,
        ioDispatcher = Dispatchers.Main,
    )
}

private class FakeAvatarStorage : CompanionAvatarStorage {
    var clearCalls = 0
    var importResult: Result<String> = Result.success("/data/user/0/app/files/avatars/picked.jpg")

    override fun importAvatar(sourceUri: Uri): Result<String> = importResult

    override fun clearAvatars() {
        clearCalls += 1
    }
}

private class FakeSettingsCompanionProfileStore : CompanionProfileStore {
    private val mutable = MutableStateFlow(CompanionProfile())
    override val profile: StateFlow<CompanionProfile> = mutable.asStateFlow()

    override fun load(): CompanionProfile = mutable.value

    override fun save(profile: CompanionProfile): CompanionProfile {
        mutable.value = profile
        return profile
    }
}

private class FakeSettingsProviderStore(
    private var config: ProviderConfig,
) : ProviderSettingsStore {
    private val mutableConfigured = MutableStateFlow(config.isConfigured)
    override val configured: StateFlow<Boolean> = mutableConfigured.asStateFlow()
    var savedConfig: ProviderConfig? = null
    var clearCalls = 0

    override fun load(): ProviderConfig = config

    override fun save(config: ProviderConfig) {
        this.config = config
        savedConfig = config
        mutableConfigured.value = config.isConfigured
    }

    override fun clearApiKey() {
        clearCalls += 1
        config = config.copy(apiKey = "")
        mutableConfigured.value = false
    }
}

private class FakeSettingsStyleStore : ChatStyleStore {
    private var style = ConversationStyle()
    var savedStyle: ConversationStyle? = null

    override fun load(): ConversationStyle = style

    override fun save(style: ConversationStyle) {
        this.style = style
        savedStyle = style
    }
}

private class FakeSettingsChatProvider : ChatProvider {
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

private class FakeSettingsConversationStore : ChatConversationStore {
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    var seedCalls = 0
    var clearCalls = 0

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = messages

    override suspend fun ensureSeeded(conversationId: String, companion: CompanionProfile) {
        seedCalls += 1
    }

    override suspend fun appendTurn(conversationId: String, text: String): ChatTurn =
        error("unused")

    override suspend fun prepareRetry(assistantMessageId: String): ChatTurn? = null

    override suspend fun updateMessage(id: String, content: String, status: MessageStatus) = Unit

    override suspend fun recoverIncomplete(conversationId: String) = Unit

    override suspend fun getMessages(conversationId: String): List<ChatMessage> = messages.value

    override suspend fun clearMessages(conversationId: String) {
        clearCalls += 1
        messages.value = emptyList()
    }
}

private class FakeSettingsMemorySettingsStore : MemorySettingsStore {
    private val mutable = MutableStateFlow(true)
    override val autoMemoryEnabled: Flow<Boolean> = mutable

    override suspend fun setAutoMemoryEnabled(enabled: Boolean) {
        mutable.value = enabled
    }
}

private class FakeSettingsMemoryStore : MemoryStore {
    override fun observeMemories(companionId: String): Flow<List<LongTermMemory>> =
        flowOf(emptyList())

    override fun observeLatestSummary(conversationId: String): Flow<ConversationSummary?> =
        flowOf(null)

    override fun observeLatestJob(conversationId: String): Flow<MemoryJob?> = flowOf(null)
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
    override suspend fun latestSummary(conversationId: String): ConversationSummary? = null
    override suspend fun activeProfileMemories(companionId: String): List<LongTermMemory> = emptyList()
    override suspend fun activeMemories(companionId: String): List<LongTermMemory> = emptyList()
    override suspend fun searchActiveMemories(companionId: String, query: String): List<LongTermMemory> = emptyList()
    override suspend fun markMemoryAccessed(ids: List<String>, at: Long) = Unit
    override suspend fun addManualMemory(input: ManualMemoryInput): LongTermMemory = error("unused")
    override suspend fun updateMemoryStatement(id: String, statement: String) = Unit
    override suspend fun confirmMemory(id: String) = Unit
    override suspend fun rejectMemory(id: String) = Unit
    override suspend fun deleteMemory(id: String) = Unit
    override suspend fun forgetObservedSince(companionId: String, since: Long) = Unit
    override suspend fun expireTemporalMemories(companionId: String, at: Long) = Unit
    override suspend fun discardPendingCandidates(companionId: String) = Unit
    override suspend fun clearAllMemories(conversationId: String) = Unit
}
