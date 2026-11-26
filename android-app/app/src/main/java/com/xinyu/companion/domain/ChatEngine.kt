package com.xinyu.companion.domain

import com.xinyu.companion.domain.coroutines.ApplicationScope
import com.xinyu.companion.domain.coroutines.IoDispatcher
import com.xinyu.companion.domain.diagnostics.DiagnosticSink
import com.xinyu.companion.domain.diagnostics.GenerationOutcome
import com.xinyu.companion.domain.memory.MemoryContextProvider
import com.xinyu.companion.domain.memory.MemoryWorkCoordinator
import com.xinyu.companion.domain.perception.PerceptionContextProvider
import com.xinyu.companion.domain.perception.PerceptionPromptContext
import com.xinyu.companion.domain.perception.PerceptionQueryPolicy
import com.xinyu.companion.domain.perception.WebSearchResponse
import com.xinyu.companion.domain.provider.ChatProvider
import com.xinyu.companion.domain.provider.ChatRequest
import com.xinyu.companion.domain.repository.ChatConversationStore
import com.xinyu.companion.domain.settings.ChatStyleStore
import com.xinyu.companion.domain.settings.ProviderSettingsStore
import com.xinyu.companion.domain.tools.ToolExecutionContext
import com.xinyu.companion.domain.tools.ToolExecutionResult
import com.xinyu.companion.domain.tools.ToolRegistry
import com.xinyu.companion.domain.tools.ToolResultSanitizer
import com.xinyu.companion.domain.tools.WebSearchTool
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class PendingToolSearch(
    val suggestedQuery: String,
    val error: String? = null,
)

data class ChatEngineState(
    val isStreaming: Boolean = false,
    val error: String? = null,
    val safetyLevel: String = "normal",
    val retryableAssistantMessageId: String? = null,
    val liveContextLabel: String? = null,
    val pendingToolSearch: PendingToolSearch? = null,
)

@Singleton
class ChatEngine internal constructor(
    private val store: ChatConversationStore,
    private val provider: ChatProvider,
    private val settingsStore: ProviderSettingsStore,
    private val styleStore: ChatStyleStore,
    private val contextAssembler: ContextAssembler,
    private val memoryContextProvider: MemoryContextProvider,
    private val memoryCoordinator: MemoryWorkCoordinator,
    private val perceptionContextProvider: PerceptionContextProvider,
    private val safetyPolicy: SafetyPolicy,
    private val diagnostics: DiagnosticSink,
    private val toolRegistry: ToolRegistry,
    private val maxToolSteps: Int,
    private val toolTimeoutMs: Long,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        store: ChatConversationStore,
        provider: ChatProvider,
        settingsStore: ProviderSettingsStore,
        styleStore: ChatStyleStore,
        contextAssembler: ContextAssembler,
        memoryContextProvider: MemoryContextProvider,
        memoryCoordinator: MemoryWorkCoordinator,
        perceptionContextProvider: PerceptionContextProvider,
        safetyPolicy: SafetyPolicy,
        diagnostics: DiagnosticSink,
        toolRegistry: ToolRegistry,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        store = store,
        provider = provider,
        settingsStore = settingsStore,
        styleStore = styleStore,
        contextAssembler = contextAssembler,
        memoryContextProvider = memoryContextProvider,
        memoryCoordinator = memoryCoordinator,
        perceptionContextProvider = perceptionContextProvider,
        safetyPolicy = safetyPolicy,
        diagnostics = diagnostics,
        toolRegistry = toolRegistry,
        maxToolSteps = DEFAULT_MAX_TOOL_STEPS,
        toolTimeoutMs = DEFAULT_TOOL_TIMEOUT_MS,
        ioDispatcher = ioDispatcher,
        scope = scope,
    )

    constructor(
        store: ChatConversationStore,
        provider: ChatProvider,
        settingsStore: ProviderSettingsStore,
        styleStore: ChatStyleStore,
        contextAssembler: ContextAssembler,
        memoryContextProvider: MemoryContextProvider,
        memoryCoordinator: MemoryWorkCoordinator,
        perceptionContextProvider: PerceptionContextProvider,
        safetyPolicy: SafetyPolicy,
        diagnostics: DiagnosticSink,
        ioDispatcher: CoroutineDispatcher,
        scope: CoroutineScope,
    ) : this(
        store = store,
        provider = provider,
        settingsStore = settingsStore,
        styleStore = styleStore,
        contextAssembler = contextAssembler,
        memoryContextProvider = memoryContextProvider,
        memoryCoordinator = memoryCoordinator,
        perceptionContextProvider = perceptionContextProvider,
        safetyPolicy = safetyPolicy,
        diagnostics = diagnostics,
        toolRegistry = ToolRegistry.EMPTY,
        maxToolSteps = DEFAULT_MAX_TOOL_STEPS,
        toolTimeoutMs = DEFAULT_TOOL_TIMEOUT_MS,
        ioDispatcher = ioDispatcher,
        scope = scope,
    )

    private val running = AtomicBoolean(false)
    private val runSequence = AtomicLong(0)
    private val activeRun = AtomicLong(0)
    private var activeJob: Job? = null

    @Volatile
    private var pendingSearchDeferred: CompletableDeferred<String?>? = null

    private val mutableState = MutableStateFlow(ChatEngineState())
    val state: StateFlow<ChatEngineState> = mutableState

    fun send(
        conversationId: String,
        companion: CompanionProfile,
        text: String,
        approvedSearchQuery: String? = null,
    ): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return false
        val runId = beginRun() ?: return false
        activeJob = scope.launch {
            execute(
                conversationId = conversationId,
                companion = companion,
                prepare = { store.appendTurn(conversationId, clean) },
                approvedSearchQuery = approvedSearchQuery,
                runId = runId,
            )
        }
        return true
    }

    fun retry(
        conversationId: String,
        companion: CompanionProfile,
        approvedSearchQuery: String? = null,
    ): Boolean {
        val assistantMessageId = mutableState.value.retryableAssistantMessageId
            ?: return false
        val runId = beginRun() ?: return false
        activeJob = scope.launch {
            execute(
                conversationId = conversationId,
                companion = companion,
                prepare = { store.prepareRetry(assistantMessageId) },
                approvedSearchQuery = approvedSearchQuery,
                runId = runId,
            )
        }
        return true
    }

    fun approveToolSearch(query: String): Boolean {
        val normalized = PerceptionQueryPolicy.normalizeConfirmedSearchQuery(query)
        if (normalized == null) {
            mutableState.update { current ->
                val pending = current.pendingToolSearch ?: return@update current
                current.copy(
                    pendingToolSearch = pending.copy(
                        error = "搜索词不能为空，也不能包含验证码、证件号或银行卡等敏感字段",
                    ),
                )
            }
            return false
        }
        val deferred = pendingSearchDeferred ?: return false
        pendingSearchDeferred = null
        mutableState.update { it.copy(pendingToolSearch = null) }
        deferred.complete(normalized)
        return true
    }

    fun rejectToolSearch() {
        val deferred = pendingSearchDeferred
        pendingSearchDeferred = null
        mutableState.update { it.copy(pendingToolSearch = null) }
        deferred?.complete(null)
    }

    fun stop() {
        pendingSearchDeferred?.cancel()
        pendingSearchDeferred = null
        activeJob?.cancel()
    }

    fun clearError() {
        mutableState.update {
            it.copy(
                error = null,
                retryableAssistantMessageId = null,
            )
        }
    }

    fun resetSession() {
        stop()
        activeJob = null
        activeRun.set(0)
        running.set(false)
        mutableState.value = ChatEngineState()
    }

    private fun beginRun(): Long? {
        if (!running.compareAndSet(false, true)) return null
        val runId = runSequence.incrementAndGet()
        activeRun.set(runId)
        return runId
    }

    private suspend fun execute(
        conversationId: String,
        companion: CompanionProfile,
        prepare: suspend () -> ChatTurn?,
        approvedSearchQuery: String?,
        runId: Long,
    ) {
        val startedAt = System.currentTimeMillis()
        var turn: ChatTurn? = null
        var snapshot = StreamSnapshot()
        var finalStatus = MessageStatus.FAILED
        var diagnosticModel = "unknown"

        try {
            val (config, conversationStyle) = withContext(ioDispatcher) {
                settingsStore.load() to styleStore.load()
            }
            diagnosticModel = config.model
            if (!config.isConfigured) {
                throw ChatEngineException("请先配置 DeepSeek API Key")
            }

            turn = prepare() ?: throw ChatEngineException("找不到可重试的对话")
            val safety = safetyPolicy.assess(turn.user.content)
            mutableState.value = ChatEngineState(
                isStreaming = true,
                safetyLevel = safety.level,
                liveContextLabel = "正在检查本轮可用信息",
            )

            val history = store.getMessages(conversationId)
            val perceptionContext = try {
                withTimeout(PERCEPTION_TIMEOUT_MS) {
                    perceptionContextProvider.build(
                        query = turn.user.content,
                        approvedSearchQuery = approvedSearchQuery,
                    )
                }
            } catch (_: TimeoutCancellationException) {
                PerceptionPromptContext(
                    currentTimeLabel = java.time.ZonedDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    notes = listOf("本轮环境信息读取失败，不要声称查看过实时信息"),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                PerceptionPromptContext(
                    currentTimeLabel = java.time.ZonedDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    notes = listOf("本轮环境信息读取失败，不要声称查看过实时信息"),
                )
            }
            mutableState.update {
                it.copy(liveContextLabel = perceptionStatus(perceptionContext))
            }
            val memoryContext = memoryContextProvider.build(
                companionId = companion.id,
                conversationId = conversationId,
                query = turn.user.content,
            )
            val baseContext = contextAssembler.assemble(
                companion = companion,
                memoryContext = memoryContext,
                messages = history,
                currentUserMessageId = turn.user.id,
                safetyInstruction = safety.instruction,
                conversationStyle = conversationStyle,
                perceptionContext = perceptionContext,
            )

            val workingContext = baseContext.toMutableList()
            val toolsEnabled = provider.capabilities.tools &&
                !toolRegistry.isEmpty() &&
                safety.level != "crisis"
            var toolStepsUsed = 0
            var latestSearchResponse: WebSearchResponse? = perceptionContext.search
            var usedWeather = perceptionContext.weather != null
            var remainingPreApprovedSearchQuery = approvedSearchQuery
            var syntheticSeq = (workingContext.maxOfOrNull { it.sequence } ?: 0L) + 1L

            while (true) {
                currentCoroutineContext().ensureActive()
                val allowToolsThisStep = toolsEnabled && toolStepsUsed < maxToolSteps
                val stepTools = if (allowToolsThisStep) toolRegistry.definitions else emptyList()
                val stepSnapshot = streamStep(
                    assistantMessageId = turn.assistant.id,
                    request = ChatRequest(
                        context = workingContext.toList(),
                        config = config,
                        conversationStyle = conversationStyle,
                        tools = stepTools,
                        toolChoice = if (stepTools.isNotEmpty()) "auto" else null,
                    ),
                )

                if (stepSnapshot.error != null || !stepSnapshot.completed) {
                    snapshot = stepSnapshot
                    break
                }

                val requestedTools = stepSnapshot.toolCalls
                if (requestedTools.isEmpty() || !allowToolsThisStep) {
                    snapshot = stepSnapshot
                    break
                }

                toolStepsUsed += 1
                workingContext += ContextTurn(
                    id = "tool-assistant-$toolStepsUsed",
                    role = "assistant",
                    content = stepSnapshot.text,
                    sequence = syntheticSeq++,
                    toolCalls = requestedTools.take(MAX_TOOLS_PER_STEP),
                )

                for (call in requestedTools.take(MAX_TOOLS_PER_STEP)) {
                    currentCoroutineContext().ensureActive()
                    val tool = toolRegistry.find(call.name)
                    val result = if (tool == null) {
                        ToolExecutionResult(
                            content = ToolResultSanitizer.wrapUntrusted(
                                call.name,
                                "工具 ${call.name} 不可用，请不要重试该工具。",
                            ),
                        )
                    } else {
                        mutableState.update { it.copy(liveContextLabel = tool.statusLabel) }
                        val toolContext = ToolExecutionContext(
                            companionId = companion.id,
                            conversationId = conversationId,
                            userMessage = turn.user.content,
                            approvedSearchQuery = remainingPreApprovedSearchQuery,
                            preFetchedSearch = latestSearchResponse,
                            requestSearchApproval = { suggested ->
                                awaitToolSearchApproval(suggested)
                            },
                        )
                        executeToolSafely(tool, call.argumentsJson, toolContext).also {
                            if (tool.definition.name == WebSearchTool.NAME) {
                                remainingPreApprovedSearchQuery = null
                            }
                        }
                    }

                    result.searchResponse?.let { latestSearchResponse = it }
                    if (result.usedWeather) usedWeather = true

                    workingContext += ContextTurn(
                        id = "tool-result-${call.id}",
                        role = "tool",
                        content = result.content,
                        sequence = syntheticSeq++,
                        toolCallId = call.id,
                        toolName = call.name,
                    )
                }
                mutableState.update {
                    it.copy(
                        liveContextLabel = toolResultStatus(
                            search = latestSearchResponse,
                            usedWeather = usedWeather,
                            step = toolStepsUsed,
                        ),
                    )
                }
            }

            if (snapshot.error == null && !snapshot.completed) {
                snapshot = snapshot.reduce(
                    GenerationEvent.Failed(
                        code = "stream_incomplete",
                        message = "回复没有正常结束，请重试",
                        retryable = true,
                    ),
                )
            }
            if (snapshot.error == null && snapshot.text.isNotBlank()) {
                snapshot = snapshot.copy(
                    text = appendLiveContextSources(
                        answer = snapshot.text,
                        search = latestSearchResponse,
                        usedWeather = usedWeather,
                    ),
                )
            }
            if (snapshot.error == null && snapshot.text.isBlank()) {
                snapshot = snapshot.reduce(
                    GenerationEvent.Failed(
                        code = "empty_response",
                        message = "模型没有返回内容，请重试",
                        retryable = true,
                    ),
                )
            }

            finalStatus = if (snapshot.error == null) {
                MessageStatus.COMPLETED
            } else {
                MessageStatus.FAILED
            }
        } catch (cancelled: CancellationException) {
            finalStatus = MessageStatus.INTERRUPTED
            throw cancelled
        } catch (error: Throwable) {
            snapshot = snapshot.reduce(
                GenerationEvent.Failed(
                    code = "engine_error",
                    message = error.message ?: "生成失败，请稍后重试",
                    retryable = turn != null,
                ),
            )
            finalStatus = MessageStatus.FAILED
        } finally {
            pendingSearchDeferred?.cancel()
            pendingSearchDeferred = null
            val completedTurn = turn
            var persistenceError: String? = null
            if (completedTurn != null) {
                persistenceError = runCatching {
                    withContext(NonCancellable) {
                        store.updateMessage(
                            id = completedTurn.assistant.id,
                            content = snapshot.text,
                            status = finalStatus,
                        )
                    }
                }.exceptionOrNull()?.message
            }
            completedTurn?.let {
                runCatching {
                    diagnostics.recordGeneration(
                        assistantMessageId = it.assistant.id,
                        providerId = provider.id,
                        model = diagnosticModel,
                        outcome = when (finalStatus) {
                            MessageStatus.COMPLETED -> GenerationOutcome.COMPLETED
                            MessageStatus.INTERRUPTED -> GenerationOutcome.INTERRUPTED
                            else -> GenerationOutcome.FAILED
                        },
                        durationMs = System.currentTimeMillis() - startedAt,
                        errorCode = persistenceError?.let { "persistence_error" }
                            ?: snapshot.errorCode,
                    )
                }
            }
            if (activeRun.compareAndSet(runId, 0)) {
                mutableState.update {
                    it.copy(
                        isStreaming = false,
                        error = persistenceError
                            ?: if (finalStatus == MessageStatus.FAILED) snapshot.error else null,
                        liveContextLabel = null,
                        pendingToolSearch = null,
                        retryableAssistantMessageId =
                            if (persistenceError == null &&
                                finalStatus == MessageStatus.FAILED &&
                                snapshot.retryable &&
                                completedTurn != null
                            ) {
                                completedTurn.assistant.id
                            } else {
                                null
                            },
                    )
                }
                activeJob = null
                running.set(false)
                if (finalStatus == MessageStatus.COMPLETED) {
                    memoryCoordinator.onTurnCompleted(conversationId, companion)
                }
            }
        }
    }

    private suspend fun streamStep(
        assistantMessageId: String,
        request: ChatRequest,
    ): StreamSnapshot {
        var stepSnapshot = StreamSnapshot()
        var lastSavedText = ""
        var lastSavedAt = 0L

        provider.stream(request).collect { event ->
            stepSnapshot = stepSnapshot.reduce(event)
            if (event is GenerationEvent.Delta && stepSnapshot.toolCallBuilders.isEmpty()) {
                if (System.currentTimeMillis() - lastSavedAt >= SAVE_INTERVAL_MS &&
                    stepSnapshot.text != lastSavedText
                ) {
                    store.updateMessage(
                        id = assistantMessageId,
                        content = stepSnapshot.text,
                        status = MessageStatus.STREAMING,
                    )
                    lastSavedText = stepSnapshot.text
                    lastSavedAt = System.currentTimeMillis()
                }
            }
        }
        return stepSnapshot
    }

    private suspend fun executeToolSafely(
        tool: com.xinyu.companion.domain.tools.CompanionTool,
        argumentsJson: String,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        return try {
            if (tool.definition.name == WebSearchTool.NAME) {
                tool.execute(argumentsJson, context)
            } else {
                withTimeout(toolTimeoutMs) {
                    withContext(ioDispatcher) {
                        tool.execute(argumentsJson, context)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            ToolExecutionResult(
                content = ToolResultSanitizer.wrapUntrusted(
                    tool.definition.name,
                    "工具 ${tool.definition.name} 执行超时，请不要编造结果。",
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ToolExecutionResult(
                content = ToolResultSanitizer.wrapUntrusted(
                    tool.definition.name,
                    "工具执行失败：${error.message ?: "未知错误"}",
                ),
            )
        }
    }

    private suspend fun awaitToolSearchApproval(suggestedQuery: String): String? {
        val deferred = CompletableDeferred<String?>()
        pendingSearchDeferred = deferred
        mutableState.update {
            it.copy(
                liveContextLabel = "等待你确认联网搜索词",
                pendingToolSearch = PendingToolSearch(suggestedQuery = suggestedQuery),
            )
        }
        return try {
            deferred.await()
        } finally {
            if (pendingSearchDeferred === deferred) {
                pendingSearchDeferred = null
            }
            mutableState.update { it.copy(pendingToolSearch = null) }
        }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 450L
        const val PERCEPTION_TIMEOUT_MS = 30_000L
        const val DEFAULT_MAX_TOOL_STEPS = 4
        const val DEFAULT_TOOL_TIMEOUT_MS = 15_000L
        const val MAX_TOOLS_PER_STEP = 3

        fun perceptionStatus(context: PerceptionPromptContext): String? = when {
            context.search != null -> "已联网搜索 ${context.search.results.size} 个来源"
            context.weather != null -> "已读取当前天气"
            context.notifications.isNotEmpty() -> "参考了 ${context.notifications.size} 条近期通知"
            context.foregroundApp != null -> "参考了当前应用状态"
            else -> null
        }

        fun toolResultStatus(
            search: WebSearchResponse?,
            usedWeather: Boolean,
            step: Int,
        ): String = when {
            search != null -> "已联网搜索 ${search.results.size} 个来源"
            usedWeather -> "已读取当前天气"
            else -> "已完成第 $step 步信息整理"
        }

        fun appendLiveContextSources(
            answer: String,
            search: WebSearchResponse?,
            usedWeather: Boolean,
        ): String {
            if (search != null) {
                val citedIndexes = Regex("""\[S(\d+)]""")
                    .findAll(answer)
                    .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                    .filter { it in 1..search.results.size }
                    .distinct()
                    .sorted()
                    .take(5)
                    .toList()
                if (citedIndexes.isNotEmpty()) {
                    var cleanAnswer = answer
                    search.results.forEachIndexed { index, _ ->
                        cleanAnswer = cleanAnswer.replace("[S${index + 1}]", "[${index + 1}]")
                    }
                    return buildString {
                        append(cleanAnswer.trimEnd())
                        appendLine()
                        appendLine()
                        appendLine("参考来源：")
                        citedIndexes.forEach { sourceIndex ->
                            val result = search.results[sourceIndex - 1]
                            append(sourceIndex)
                            append(". ")
                            append(result.title.take(100))
                            appendLine()
                            appendLine(result.url)
                        }
                    }.trimEnd()
                }
            }
            if (usedWeather && !answer.contains("Open-Meteo")) {
                return answer.trimEnd() + "\n\n天气数据：Open-Meteo"
            }
            return answer
        }
    }
}

class ChatEngineException(message: String) : IllegalStateException(message)
