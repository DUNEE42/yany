package com.xinyu.companion.domain

import com.xinyu.companion.domain.coroutines.ApplicationScope
import com.xinyu.companion.domain.coroutines.IoDispatcher
import com.xinyu.companion.domain.diagnostics.DiagnosticSink
import com.xinyu.companion.domain.diagnostics.GenerationOutcome
import com.xinyu.companion.domain.memory.MemoryContextProvider
import com.xinyu.companion.domain.memory.MemoryWorkCoordinator
import com.xinyu.companion.domain.perception.PerceptionContextProvider
import com.xinyu.companion.domain.perception.PerceptionPromptContext
import com.xinyu.companion.domain.provider.ChatProvider
import com.xinyu.companion.domain.provider.ChatRequest
import com.xinyu.companion.domain.repository.ChatConversationStore
import com.xinyu.companion.domain.settings.ChatStyleStore
import com.xinyu.companion.domain.settings.ProviderSettingsStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

data class ChatEngineState(
    val isStreaming: Boolean = false,
    val error: String? = null,
    val safetyLevel: String = "normal",
    val retryableAssistantMessageId: String? = null,
    val liveContextLabel: String? = null,
)

@Singleton
class ChatEngine @Inject constructor(
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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val running = AtomicBoolean(false)
    private val runSequence = AtomicLong(0)
    private val activeRun = AtomicLong(0)
    private var activeJob: Job? = null

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

    fun stop() {
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
        var lastSavedText = ""
        var lastSavedAt = 0L
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
            val context = contextAssembler.assemble(
                companion = companion,
                memoryContext = memoryContext,
                messages = history,
                currentUserMessageId = turn.user.id,
                safetyInstruction = safety.instruction,
                conversationStyle = conversationStyle,
                perceptionContext = perceptionContext,
            )

            provider.stream(
                ChatRequest(
                    context = context,
                    config = config,
                    conversationStyle = conversationStyle,
                ),
            ).collect { event ->
                snapshot = snapshot.reduce(event)
                if (event is GenerationEvent.Delta) {
                    if (System.currentTimeMillis() - lastSavedAt >= SAVE_INTERVAL_MS &&
                        snapshot.text != lastSavedText
                    ) {
                        store.updateMessage(
                            id = turn.assistant.id,
                            content = snapshot.text,
                            status = MessageStatus.STREAMING,
                        )
                        lastSavedText = snapshot.text
                        lastSavedAt = System.currentTimeMillis()
                    }
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
                    text = appendLiveContextSources(snapshot.text, perceptionContext),
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

    private companion object {
        const val SAVE_INTERVAL_MS = 450L
        const val PERCEPTION_TIMEOUT_MS = 30_000L

        fun perceptionStatus(context: PerceptionPromptContext): String? = when {
            context.search != null -> "已联网搜索 ${context.search.results.size} 个来源"
            context.weather != null -> "已读取当前天气"
            context.notifications.isNotEmpty() -> "参考了 ${context.notifications.size} 条近期通知"
            context.foregroundApp != null -> "参考了当前应用状态"
            else -> null
        }

        fun appendLiveContextSources(
            answer: String,
            context: PerceptionPromptContext,
        ): String {
            if (context.search != null) {
                val citedIndexes = Regex("""\[S(\d+)]""")
                    .findAll(answer)
                    .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                    .filter { it in 1..context.search.results.size }
                    .distinct()
                    .sorted()
                    .take(5)
                    .toList()
                if (citedIndexes.isNotEmpty()) {
                    var cleanAnswer = answer
                    context.search.results.forEachIndexed { index, result ->
                        cleanAnswer = cleanAnswer.replace("[S${index + 1}]", "[${index + 1}]")
                    }
                    return buildString {
                        append(cleanAnswer.trimEnd())
                        appendLine()
                        appendLine()
                        appendLine("参考来源：")
                        citedIndexes.forEach { sourceIndex ->
                            val result = context.search.results[sourceIndex - 1]
                            append(sourceIndex)
                            append(". ")
                            append(result.title.take(100))
                            appendLine()
                            appendLine(result.url)
                        }
                    }.trimEnd()
                }
            }
            if (context.weather != null && !answer.contains("Open-Meteo")) {
                return answer.trimEnd() + "\n\n天气数据：Open-Meteo"
            }
            return answer
        }
    }
}

class ChatEngineException(message: String) : IllegalStateException(message)
