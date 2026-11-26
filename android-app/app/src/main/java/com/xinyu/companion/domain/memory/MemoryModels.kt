package com.xinyu.companion.domain.memory

import com.xinyu.companion.domain.ChatMessage
import com.xinyu.companion.domain.MessageRole

enum class MemoryKind {
    FACT,
    PREFERENCE,
    BOUNDARY,
    PLAN,
    EVENT,
    RELATIONSHIP_PREFERENCE,
    SUMMARY,
}

enum class MemoryScope {
    USER,
    COMPANION,
    RELATIONSHIP,
}

enum class MemorySourceKind {
    USER_EXPLICIT,
    COMPANION_EXPLICIT,
    USER_FEEDBACK,
    INFERRED,
    MANUAL,
}

enum class MemoryConfirmationState {
    UNCONFIRMED,
    CONFIRMED,
    REJECTED,
}

enum class MemoryStatus {
    CANDIDATE,
    ACTIVE,
    SUPERSEDED,
    EXPIRED,
    DELETED,
}

enum class MemorySensitivity {
    NORMAL,
    SENSITIVE,
}

enum class MemoryJobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}

data class ConversationSummary(
    val id: String,
    val conversationId: String,
    val startSequence: Long,
    val endSequence: Long,
    val periodStart: Long,
    val periodEnd: Long,
    val content: String,
    val createdAt: Long,
)

data class LongTermMemory(
    val id: String,
    val companionId: String,
    val kind: MemoryKind,
    val scope: MemoryScope,
    val subject: String,
    val predicate: String,
    val objectText: String,
    val statement: String,
    val normalizedKey: String,
    val sourceKind: MemorySourceKind,
    val confirmationState: MemoryConfirmationState,
    val status: MemoryStatus,
    val confidence: Double,
    val importance: Int,
    val sensitivity: MemorySensitivity,
    val observedAt: Long,
    val validFrom: Long?,
    val validTo: Long?,
    val supersededById: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long?,
    val evidenceCount: Int = 0,
)

data class MemoryJob(
    val id: String,
    val conversationId: String,
    val companionId: String,
    val fromSequence: Long,
    val toSequence: Long,
    val status: MemoryJobStatus,
    val attempts: Int,
    val lastError: String?,
    val scheduledAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
) {
    val messageCount: Long
        get() = (toSequence - fromSequence + 1).coerceAtLeast(0)
}

data class MemorySourceMessage(
    val id: String,
    val role: MessageRole,
    val sequence: Long,
    val content: String,
    val createdAt: Long,
)

data class MemoryCandidate(
    val kind: MemoryKind,
    val scope: MemoryScope,
    val subject: String,
    val predicate: String,
    val objectText: String,
    val statement: String,
    val sourceMessageIds: List<String>,
    val observedAt: Long,
    val validFrom: Long?,
    val validTo: Long?,
    val confidence: Double,
    val sourceKind: MemorySourceKind,
    val sensitivity: MemorySensitivity,
    val importance: Int = 3,
)

data class MemoryExtractionResult(
    val summary: String,
    val candidates: List<MemoryCandidate>,
)

data class MemoryJobInput(
    val job: MemoryJob,
    val messages: List<MemorySourceMessage>,
    val existingMemories: List<MemorySnippet>,
)

data class MemorySnippet(
    val id: String,
    val kind: MemoryKind,
    val statement: String,
    val observedAt: Long,
)

data class MemoryPromptContext(
    val profileItems: List<String> = emptyList(),
    val recentSummary: String? = null,
    val relevantMemories: List<MemorySnippet> = emptyList(),
) {
    val isEmpty: Boolean
        get() = profileItems.isEmpty() && recentSummary.isNullOrBlank() && relevantMemories.isEmpty()
}

data class ManualMemoryInput(
    val companionId: String,
    val kind: MemoryKind,
    val statement: String,
    val importance: Int = 4,
)

fun ChatMessage.toMemorySource(): MemorySourceMessage = MemorySourceMessage(
    id = id,
    role = role,
    sequence = sequence,
    content = content,
    createdAt = createdAt,
)

