package com.xinyu.companion.data.repository

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xinyu.companion.data.local.AppDatabase
import com.xinyu.companion.data.local.ConversationSummaryEntity
import com.xinyu.companion.data.local.MemoryItemEntity
import com.xinyu.companion.data.local.MemoryJobEntity
import com.xinyu.companion.data.local.MemorySourceEntity
import com.xinyu.companion.domain.MessageRole
import com.xinyu.companion.domain.memory.ConversationSummary
import com.xinyu.companion.domain.memory.LongTermMemory
import com.xinyu.companion.domain.memory.ManualMemoryInput
import com.xinyu.companion.domain.memory.MemoryCandidate
import com.xinyu.companion.domain.memory.MemoryConfirmationState
import com.xinyu.companion.domain.memory.MemoryJob
import com.xinyu.companion.domain.memory.MemoryJobInput
import com.xinyu.companion.domain.memory.MemoryJobStatus
import com.xinyu.companion.domain.memory.MemoryKind
import com.xinyu.companion.domain.memory.MemoryPolicy
import com.xinyu.companion.domain.memory.MemoryQueryTokenizer
import com.xinyu.companion.domain.memory.MemoryScope
import com.xinyu.companion.domain.memory.MemorySensitivity
import com.xinyu.companion.domain.memory.MemorySnippet
import com.xinyu.companion.domain.memory.MemorySourceKind
import com.xinyu.companion.domain.memory.MemorySourceMessage
import com.xinyu.companion.domain.memory.MemoryStatus
import com.xinyu.companion.domain.memory.MemoryStore
import com.xinyu.companion.domain.memory.MemoryValidator
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class MemoryRepository @Inject constructor(
    private val databaseProvider: Provider<AppDatabase>,
) : MemoryStore {
    private val database by lazy { databaseProvider.get() }
    private val dao by lazy { database.memoryDao() }
    private val ftsMutex = Mutex()

    @Volatile
    private var ftsInitialized = false

    @Volatile
    private var ftsEnabled = false

    override fun observeMemories(companionId: String): Flow<List<LongTermMemory>> =
        dao.observeMemories(companionId).map { rows ->
            rows.map(MemoryItemEntity::toDomain)
        }

    override fun observeLatestSummary(conversationId: String): Flow<ConversationSummary?> =
        dao.observeLatestSummary(conversationId).map { it?.toDomain() }

    override fun observeLatestJob(conversationId: String): Flow<MemoryJob?> =
        dao.observeLatestJob(conversationId).map { it?.toDomain() }

    override suspend fun enqueue(
        conversationId: String,
        companionId: String,
    ): MemoryJob? = database.withTransaction {
        if (dao.getRunningJob(conversationId) != null) {
            return@withTransaction null
        }
        val lastSummarized = dao.latestSummarizedSequence(conversationId)
        val fromSequence = lastSummarized + 1
        val messages = dao.getCompletedMessagesFrom(
            conversationId = conversationId,
            fromSequence = fromSequence,
            limit = MAX_MESSAGES_SCANNED_PER_ENQUEUE,
        )
        val first = messages.firstOrNull() ?: return@withTransaction null
        val horizonEnd = first.createdAt + SHORT_TERM_WINDOW_MS
        val selected = buildList {
            var characterCount = 0
            for (message in messages) {
                if (message.createdAt > horizonEnd) break
                if (size >= MAX_MESSAGES_PER_JOB) break
                val nextSize = characterCount + message.content.length
                if (isNotEmpty() && nextSize > MAX_INPUT_CHARS_PER_JOB) break
                add(message)
                characterCount = nextSize
            }
        }
        val latest = selected.lastOrNull()?.sequence ?: first.sequence

        val pending = dao.getPendingJob(conversationId)
        if (pending != null) {
            if (pending.toSequence < latest) {
                dao.extendPendingJob(pending.id, latest)
            }
            return@withTransaction dao.getJob(pending.id)?.toDomain()
        }
        val latestFailed = dao.getLatestJob(conversationId)
            ?.takeIf { it.status == MemoryJobStatus.FAILED.name }
        if (latestFailed != null &&
            latestFailed.toSequence >= latest &&
            latestFailed.attempts >= MAX_JOB_ATTEMPTS
        ) {
            return@withTransaction null
        }

        val now = System.currentTimeMillis()
        val job = MemoryJobEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            companionId = companionId,
            fromSequence = fromSequence,
            toSequence = latest,
            status = MemoryJobStatus.PENDING.name,
            attempts = 0,
            lastError = null,
            scheduledAt = now,
            startedAt = null,
            completedAt = null,
        )
        dao.insertJob(job)
        job.toDomain()
    }

    override suspend fun recoverRunningJobs() {
        dao.recoverRunningJobs()
    }

    override suspend fun loadJobInput(job: MemoryJob): MemoryJobInput? {
        val current = dao.getJob(job.id)?.toDomain() ?: return null
        if (current.status != MemoryJobStatus.PENDING) return null
        val messages = dao.getJobMessages(
            conversationId = current.conversationId,
            fromSequence = current.fromSequence,
            toSequence = current.toSequence,
        ).map {
            MemorySourceMessage(
                id = it.id,
                role = runCatching { MessageRole.valueOf(it.role) }
                    .getOrDefault(MessageRole.ASSISTANT),
                sequence = it.sequence,
                content = it.content,
                createdAt = it.createdAt,
            )
        }
        val existing = dao.getActiveMemories(
            current.companionId,
            now = System.currentTimeMillis(),
            limit = 50,
        )
            .map {
                MemorySnippet(
                    id = it.id,
                    kind = it.kind.toEnum(MemoryKind.FACT),
                    statement = it.statement,
                    observedAt = it.observedAt,
                )
            }
        return MemoryJobInput(current, messages, existing)
    }

    override suspend fun markJobRunning(jobId: String) {
        dao.markJobRunning(jobId, System.currentTimeMillis())
    }

    override suspend fun completeJob(
        jobId: String,
        conversationId: String,
        summary: String,
        candidates: List<MemoryCandidate>,
    ) {
        ensureFtsReady()
        database.withTransaction {
            val job = dao.getJob(jobId) ?: return@withTransaction
            val messages = dao.getJobMessages(
                conversationId = conversationId,
                fromSequence = job.fromSequence,
                toSequence = job.toSequence,
            )
            val sourceMessages = messages.map {
                MemorySourceMessage(
                    id = it.id,
                    role = runCatching { MessageRole.valueOf(it.role) }
                        .getOrDefault(MessageRole.ASSISTANT),
                    sequence = it.sequence,
                    content = it.content,
                    createdAt = it.createdAt,
                )
            }
            val now = System.currentTimeMillis()
            val startSequence = messages.minOfOrNull { it.sequence } ?: job.fromSequence
            val endSequence = messages.maxOfOrNull { it.sequence } ?: job.toSequence
            val periodStart = messages.minOfOrNull { it.createdAt } ?: now
            val periodEnd = messages.maxOfOrNull { it.createdAt } ?: now
            val summaryId = UUID.randomUUID().toString()
            val hash = sha256("$conversationId|$startSequence|$endSequence|$summary")
            val summaryEntity = ConversationSummaryEntity(
                id = summaryId,
                conversationId = conversationId,
                startSequence = startSequence,
                endSequence = endSequence,
                periodStart = periodStart,
                periodEnd = periodEnd,
                content = summary,
                contentHash = hash,
                status = "ACTIVE",
                createdAt = now,
                updatedAt = now,
            )
            dao.insertSummary(summaryEntity)

            val policyCheckedCandidates = candidates.mapNotNull {
                MemoryPolicy.prepare(sourceMessages, it)
            }
            for (candidate in policyCheckedCandidates) {
                val normalizedKey = MemoryValidator.normalizedKey(
                    kind = candidate.kind,
                    scope = candidate.scope,
                    subject = candidate.subject,
                    predicate = candidate.predicate,
                    objectText = candidate.objectText,
                )
                val existing = dao.findActiveByNormalizedKey(
                    companionId = job.companionId,
                    normalizedKey = normalizedKey,
                )
                val memoryId = existing?.id ?: UUID.randomUUID().toString()
                val candidateMessages = sourceMessages.filter {
                    it.id in candidate.sourceMessageIds
                }
                val confirmationState = confirmationState(candidate, candidateMessages)
                val status = initialStatus(candidate, confirmationState)

                if (existing == null) {
                    val memory = MemoryItemEntity(
                        id = memoryId,
                        companionId = job.companionId,
                        kind = candidate.kind.name,
                        scope = candidate.scope.name,
                        subject = candidate.subject,
                        predicate = candidate.predicate,
                        objectText = candidate.objectText,
                        statement = candidate.statement,
                        normalizedKey = normalizedKey,
                        sourceKind = candidate.sourceKind.name,
                        confirmationState = confirmationState.name,
                        status = status.name,
                        confidence = candidate.confidence,
                        importance = candidate.importance,
                        sensitivity = candidate.sensitivity.name,
                        observedAt = candidate.observedAt,
                        validFrom = candidate.validFrom ?: candidate.observedAt,
                        validTo = candidate.validTo,
                        supersededById = null,
                        createdAt = now,
                        updatedAt = now,
                        lastAccessedAt = null,
                    )
                    dao.insertMemory(memory)
                    indexMemory(memory)

                    if (status == MemoryStatus.ACTIVE) {
                        invalidateConflicts(memory, job.companionId, now)
                    }
                }

                candidate.sourceMessageIds.forEach { messageId ->
                    dao.insertSource(
                        MemorySourceEntity(
                            id = UUID.randomUUID().toString(),
                            memoryId = memoryId,
                            messageId = messageId,
                            summaryId = summaryId,
                            evidenceHash = sha256("$memoryId|$messageId|$summaryId"),
                        ),
                    )
                }
            }
            dao.completeJob(jobId, now)
        }
    }

    override suspend fun failJob(jobId: String, message: String) {
        dao.failJob(
            id = jobId,
            error = message.take(500),
            completedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun latestSummary(conversationId: String): ConversationSummary? =
        dao.getLatestSummary(conversationId)?.toDomain()

    override suspend fun activeProfileMemories(companionId: String): List<LongTermMemory> =
        dao.getProfileCandidates(companionId).map(MemoryItemEntity::toDomain)

    override suspend fun activeMemories(companionId: String): List<LongTermMemory> =
        dao.getActiveMemories(
            companionId,
            now = System.currentTimeMillis(),
        ).map(MemoryItemEntity::toDomain)

    override suspend fun searchActiveMemories(
        companionId: String,
        query: String,
    ): List<LongTermMemory> {
        val all = dao.getActiveMemories(
            companionId,
            now = System.currentTimeMillis(),
        ).map(MemoryItemEntity::toDomain)
        if (all.isEmpty()) return emptyList()
        val terms = searchTerms(query)
        if (terms.isEmpty()) return emptyList()

        val ftsIds = runCatching { searchFtsIds(terms) }.getOrDefault(emptySet())
        val direct = all.filter { memory ->
            val text = (
                memory.statement + " " +
                    memory.subject + " " +
                    memory.predicate + " " +
                    memory.objectText
                ).lowercase()
            terms.any(text::contains)
        }
        val selected = (direct + all.filter { it.id in ftsIds })
            .distinctBy(LongTermMemory::id)
            .take(200)
        return selected
    }

    override suspend fun markMemoryAccessed(ids: List<String>, at: Long) {
        if (ids.isNotEmpty()) dao.markAccessed(ids, at)
    }

    override suspend fun addManualMemory(input: ManualMemoryInput): LongTermMemory {
        ensureFtsReady()
        val statement = input.statement.trim().take(320)
        require(statement.isNotBlank()) { "记忆内容不能为空" }
        val now = System.currentTimeMillis()
        val normalized = MemoryValidator.normalizedKey(
            input.kind,
            MemoryScope.USER,
            "user",
            "states",
            statement,
        )
        dao.findActiveByNormalizedKey(input.companionId, normalized)?.let {
            return it.toDomain()
        }
        val memory = MemoryItemEntity(
            id = UUID.randomUUID().toString(),
            companionId = input.companionId,
            kind = input.kind.name,
            scope = MemoryScope.USER.name,
            subject = "user",
            predicate = "states",
            objectText = statement,
            statement = statement,
            normalizedKey = normalized,
            sourceKind = MemorySourceKind.MANUAL.name,
            confirmationState = MemoryConfirmationState.CONFIRMED.name,
            status = MemoryStatus.ACTIVE.name,
            confidence = 1.0,
            importance = input.importance.coerceIn(1, 5),
            sensitivity = MemorySensitivity.NORMAL.name,
            observedAt = now,
            validFrom = now,
            validTo = null,
            supersededById = null,
            createdAt = now,
            updatedAt = now,
            lastAccessedAt = null,
        )
        database.withTransaction {
            dao.insertMemory(memory)
            indexMemory(memory)
        }
        return memory.toDomain()
    }

    override suspend fun updateMemoryStatement(id: String, statement: String) {
        val clean = statement.trim().take(320)
        require(clean.isNotBlank()) { "记忆内容不能为空" }
        val memory = dao.getMemory(id) ?: return
        val normalized = MemoryValidator.normalizedKey(
            kind = memory.kind.toEnum(MemoryKind.FACT),
            scope = memory.scope.toEnum(MemoryScope.USER),
            subject = memory.subject,
            predicate = memory.predicate,
            objectText = clean,
        )
        ensureFtsReady()
        database.withTransaction {
            dao.updateMemoryStatement(
                id = id,
                statement = clean,
                objectText = clean,
                normalizedKey = normalized,
                updatedAt = System.currentTimeMillis(),
            )
            indexMemory(memory.copy(statement = clean, objectText = clean))
        }
    }

    override suspend fun confirmMemory(id: String) {
        dao.updateMemoryConfirmation(
            id = id,
            confirmationState = MemoryConfirmationState.CONFIRMED.name,
            status = MemoryStatus.ACTIVE.name,
            updatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun rejectMemory(id: String) {
        dao.updateMemoryConfirmation(
            id = id,
            confirmationState = MemoryConfirmationState.REJECTED.name,
            status = MemoryStatus.CANDIDATE.name,
            updatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun deleteMemory(id: String) {
        ensureFtsReady()
        database.withTransaction {
            dao.deleteMemory(id)
            removeMemoryIndex(id)
        }
    }

    override suspend fun forgetObservedSince(companionId: String, since: Long) {
        ensureFtsReady()
        database.withTransaction {
            val ids = dao.getMemoryIdsObservedSince(companionId, since)
            ids.forEach { id ->
                dao.deleteMemory(id)
                removeMemoryIndex(id)
            }
        }
    }

    override suspend fun expireTemporalMemories(companionId: String, at: Long) {
        dao.expireTemporalMemories(companionId, at)
    }

    override suspend fun discardPendingCandidates(companionId: String) {
        ensureFtsReady()
        database.withTransaction {
            val ids = dao.getPendingCandidateIds(companionId)
            ids.forEach { id ->
                dao.deleteMemory(id)
                removeMemoryIndex(id)
            }
        }
    }

    override suspend fun clearAllMemories(conversationId: String) {
        ensureFtsReady()
        database.withTransaction {
            val now = System.currentTimeMillis()
            val latestSequence = dao.latestCompletedSequence(conversationId)
            dao.deleteAllMemories()
            dao.deleteAllSummaries()
            dao.deleteAllJobs()
            if (ftsEnabled) {
                database.openHelper.writableDatabase.execSQL("DELETE FROM memory_items_fts")
            }
            if (latestSequence != null) {
                val checkpointContent = ""
                dao.insertSummary(
                    ConversationSummaryEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        startSequence = latestSequence,
                        endSequence = latestSequence,
                        periodStart = now,
                        periodEnd = now,
                        content = checkpointContent,
                        contentHash = sha256("checkpoint|$conversationId|$latestSequence|$now"),
                        status = "CHECKPOINT",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    private suspend fun invalidateConflicts(
        memory: MemoryItemEntity,
        companionId: String,
        now: Long,
    ) {
        val conflicts = dao.findPotentialConflicts(
            companionId = companionId,
            scope = memory.scope,
            subject = memory.subject,
            predicate = memory.predicate,
            normalizedKey = memory.normalizedKey,
        )
        val newStart = memory.validFrom ?: memory.observedAt
        val newEnd = memory.validTo ?: Long.MAX_VALUE
        conflicts.forEach { old ->
            val oldStart = old.validFrom ?: Long.MIN_VALUE
            val oldEnd = old.validTo ?: Long.MAX_VALUE
            if (oldStart < newEnd && newStart < oldEnd) {
                dao.supersedeMemory(
                    id = old.id,
                    validTo = newStart,
                    supersededById = memory.id,
                    updatedAt = now,
                )
            }
        }
    }

    private fun confirmationState(
        candidate: MemoryCandidate,
        sourceMessages: List<MemorySourceMessage>,
    ): MemoryConfirmationState {
        return if (MemoryPolicy.canAutoActivate(candidate, sourceMessages)) {
            MemoryConfirmationState.CONFIRMED
        } else {
            MemoryConfirmationState.UNCONFIRMED
        }
    }

    private fun initialStatus(
        candidate: MemoryCandidate,
        confirmation: MemoryConfirmationState,
    ): MemoryStatus = if (confirmation == MemoryConfirmationState.CONFIRMED) {
        MemoryStatus.ACTIVE
    } else {
        MemoryStatus.CANDIDATE
    }

    private suspend fun ensureFtsReady() {
        if (ftsInitialized) return
        ftsMutex.withLock {
            if (ftsInitialized) return
            ftsEnabled = runCatching {
                val sqlite = database.openHelper.writableDatabase
                sqlite.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `memory_items_fts` " +
                        "USING fts4(`memoryId`, `statement`, `subject`, `predicate`, `objectText`)",
                )
            }.isSuccess
            ftsInitialized = true
        }
    }

    private fun indexMemory(memory: MemoryItemEntity) {
        if (!ftsEnabled) return
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("DELETE FROM memory_items_fts WHERE memoryId = ?", arrayOf(memory.id))
        sqlite.execSQL(
            """
            INSERT INTO memory_items_fts(
              memoryId, statement, subject, predicate, objectText
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                memory.id,
                memory.statement,
                memory.subject,
                memory.predicate,
                memory.objectText,
            ),
        )
    }

    private fun removeMemoryIndex(id: String) {
        if (!ftsEnabled) return
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("DELETE FROM memory_items_fts WHERE memoryId = ?", arrayOf(id))
    }

    private suspend fun searchFtsIds(terms: List<String>): Set<String> {
        ensureFtsReady()
        if (!ftsEnabled) return emptySet()
        val query = terms
            .map { "\"${it.replace("\"", "\"\"")}\"*" }
            .joinToString(" OR ")
        if (query.isBlank()) return emptySet()
        val sqlite = database.openHelper.writableDatabase
        val ids = mutableSetOf<String>()
        sqlite.query(
            "SELECT memoryId FROM memory_items_fts " +
                "WHERE memory_items_fts MATCH ? LIMIT 200",
            arrayOf(query),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                ids += cursor.getString(0)
            }
        }
        return ids
    }

    private fun searchTerms(query: String): List<String> =
        MemoryQueryTokenizer.terms(query)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val MAX_JOB_ATTEMPTS = 3
        const val MAX_MESSAGES_SCANNED_PER_ENQUEUE = 200
        const val MAX_MESSAGES_PER_JOB = 40
        const val MAX_INPUT_CHARS_PER_JOB = 8_000
        const val SHORT_TERM_WINDOW_MS = 5L * 24L * 60L * 60L * 1_000L
    }
}

private fun ConversationSummaryEntity.toDomain() = ConversationSummary(
    id = id,
    conversationId = conversationId,
    startSequence = startSequence,
    endSequence = endSequence,
    periodStart = periodStart,
    periodEnd = periodEnd,
    content = content,
    createdAt = createdAt,
)

private fun MemoryJobEntity.toDomain() = MemoryJob(
    id = id,
    conversationId = conversationId,
    companionId = companionId,
    fromSequence = fromSequence,
    toSequence = toSequence,
    status = status.toEnum(MemoryJobStatus.PENDING),
    attempts = attempts,
    lastError = lastError,
    scheduledAt = scheduledAt,
    startedAt = startedAt,
    completedAt = completedAt,
)

private fun MemoryItemEntity.toDomain() = LongTermMemory(
    id = id,
    companionId = companionId,
    kind = kind.toEnum(MemoryKind.FACT),
    scope = scope.toEnum(MemoryScope.USER),
    subject = subject,
    predicate = predicate,
    objectText = objectText,
    statement = statement,
    normalizedKey = normalizedKey,
    sourceKind = sourceKind.toEnum(MemorySourceKind.MANUAL),
    confirmationState = confirmationState.toEnum(MemoryConfirmationState.UNCONFIRMED),
    status = status.toEnum(MemoryStatus.CANDIDATE),
    confidence = confidence,
    importance = importance,
    sensitivity = sensitivity.toEnum(MemorySensitivity.NORMAL),
    observedAt = observedAt,
    validFrom = validFrom,
    validTo = validTo,
    supersededById = supersededById,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastAccessedAt = lastAccessedAt,
)

private inline fun <reified T : Enum<T>> String.toEnum(default: T): T =
    runCatching { enumValueOf<T>(this) }.getOrDefault(default)

