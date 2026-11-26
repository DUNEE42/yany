package com.xinyu.companion.domain.memory

import com.xinyu.companion.domain.CompanionProfile
import com.xinyu.companion.domain.coroutines.ApplicationScope
import com.xinyu.companion.domain.coroutines.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class MemoryCoordinator @Inject constructor(
    private val store: MemoryStore,
    private val extractor: MemoryExtractor,
    private val settings: MemorySettingsStore,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MemoryWorkCoordinator {
    private val processMutex = Mutex()
    private val mutableProcessing = MutableStateFlow(false)

    override val isProcessing: StateFlow<Boolean> = mutableProcessing.asStateFlow()

    override fun start(
        conversationId: String,
        companion: CompanionProfile,
    ) {
        scope.launch {
            withContext(ioDispatcher) {
                store.recoverRunningJobs()
                store.expireTemporalMemories(companion.id, System.currentTimeMillis())
                if (!settings.isAutoMemoryEnabled()) return@withContext
                val job = store.enqueue(conversationId, companion.id) ?: return@withContext
                schedule(conversationId, companion, job)
            }
        }
    }

    override fun onTurnCompleted(
        conversationId: String,
        companion: CompanionProfile,
    ) {
        scope.launch {
            withContext(ioDispatcher) {
                if (!settings.isAutoMemoryEnabled()) return@withContext
                val job = store.enqueue(conversationId, companion.id) ?: return@withContext
                schedule(conversationId, companion, job)
            }
        }
    }

    override fun consolidateNow(
        conversationId: String,
        companion: CompanionProfile,
    ) {
        scope.launch {
            withContext(ioDispatcher) {
                val job = store.enqueue(conversationId, companion.id) ?: return@withContext
                process(conversationId, companion, job, force = true)
            }
        }
    }

    private suspend fun schedule(
        conversationId: String,
        companion: CompanionProfile,
        job: MemoryJob,
    ) {
        val now = System.currentTimeMillis()
        val eligible = isEligible(job, now)
        if (eligible) {
            process(conversationId, companion, job, force = false)
        } else {
            val waitMs = (job.scheduledAt + IDLE_BEFORE_PROCESS_MS - now)
                .coerceIn(1_000L, IDLE_BEFORE_PROCESS_MS)
            scope.launch {
                delay(waitMs)
                withContext(ioDispatcher) {
                    if (!settings.isAutoMemoryEnabled()) return@withContext
                    if (isEligible(job, System.currentTimeMillis())) {
                        process(conversationId, companion, job, force = false)
                    }
                }
            }
        }
    }

    private suspend fun process(
        conversationId: String,
        companion: CompanionProfile,
        job: MemoryJob,
        force: Boolean,
    ) {
        var nextJob: MemoryJob? = null
        processMutex.withLock {
            val latest = store.loadJobInput(job)
            if (latest == null) return
            if (latest.messages.isEmpty()) {
                store.failJob(job.id, "没有可整理的新消息")
                return
            }
            if (!force && !isEligible(latest.job, System.currentTimeMillis())) return

            mutableProcessing.value = true
            var completed = false
            try {
                store.markJobRunning(latest.job.id)
                val extraction = extractor.extract(latest).getOrElse { error ->
                    store.failJob(
                        latest.job.id,
                        error.message ?: "记忆整理失败",
                    )
                    return
                }
                val validated = MemoryValidator.validate(latest, extraction)
                store.completeJob(
                    jobId = latest.job.id,
                    conversationId = conversationId,
                    summary = validated.summary,
                    candidates = validated.candidates,
                )
                completed = true
            } catch (error: Throwable) {
                store.failJob(
                    latest.job.id,
                    error.message ?: "记忆整理失败",
                )
            } finally {
                if (completed && settings.isAutoMemoryEnabled()) {
                    nextJob = store.enqueue(conversationId, companion.id)
                }
                mutableProcessing.value = false
            }
        }
        nextJob?.takeIf { isEligible(it, System.currentTimeMillis()) }?.let {
            schedule(conversationId, companion, it)
        }
    }

    private fun isEligible(job: MemoryJob, now: Long): Boolean =
        job.messageCount >= IMMEDIATE_MESSAGE_COUNT ||
            (
                job.messageCount >= MIN_MESSAGES_TO_PROCESS &&
                    now - job.scheduledAt >= IDLE_BEFORE_PROCESS_MS
                )

    private companion object {
        const val IMMEDIATE_MESSAGE_COUNT = 12L
        const val MIN_MESSAGES_TO_PROCESS = 4L
        const val IDLE_BEFORE_PROCESS_MS = 30L * 60L * 1_000L
    }
}

