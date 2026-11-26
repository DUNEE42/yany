package com.xinyu.companion.domain.memory

import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.domain.MessageRole
import org.junit.Test

class MemoryValidatorTest {
    @Test
    fun `rejects sources outside the batch and assistant-only user facts`() {
        val input = input()
        val result = MemoryExtractionResult(
            summary = "用户今天聊了工作。",
            candidates = listOf(
                candidate(
                    statement = "用户不喜欢工作。",
                    sourceIds = listOf("assistant"),
                    sourceKind = MemorySourceKind.USER_EXPLICIT,
                ),
                candidate(
                    statement = "不存在的来源。",
                    sourceIds = listOf("missing"),
                ),
            ),
        )

        val validated = MemoryValidator.validate(input, result)

        assertThat(validated.candidates).isEmpty()
    }

    @Test
    fun `deduplicates candidates and uses the source message time`() {
        val input = input()
        val original = candidate(
            statement = "用户喜欢先听结论。",
            sourceIds = listOf("user"),
            observedAt = 99_999,
        )

        val validated = MemoryValidator.validate(
            input,
            MemoryExtractionResult(
                summary = "用户明确表达偏好。",
                candidates = listOf(original, original.copy(statement = "用户喜欢先听结论。")),
            ),
        )

        assertThat(validated.candidates).hasSize(1)
        assertThat(validated.candidates.single().observedAt).isEqualTo(1_000)
    }

    @Test
    fun `allows an empty summary for a quiet batch but still advances checkpoint`() {
        val validated = MemoryValidator.validate(
            input(),
            MemoryExtractionResult(summary = "", candidates = emptyList()),
        )

        assertThat(validated.summary).isEmpty()
        assertThat(validated.candidates).isEmpty()
    }

    private fun input() = MemoryJobInput(
        job = MemoryJob(
            id = "job",
            conversationId = "c1",
            companionId = "companion",
            fromSequence = 1,
            toSequence = 2,
            status = MemoryJobStatus.PENDING,
            attempts = 0,
            lastError = null,
            scheduledAt = 1,
            startedAt = null,
            completedAt = null,
        ),
        messages = listOf(
            MemorySourceMessage(
                id = "user",
                role = MessageRole.USER,
                sequence = 1,
                content = "我喜欢先听结论",
                createdAt = 1_000,
            ),
            MemorySourceMessage(
                id = "assistant",
                role = MessageRole.ASSISTANT,
                sequence = 2,
                content = "记住了。",
                createdAt = 2_000,
            ),
        ),
        existingMemories = emptyList(),
    )

    private fun candidate(
        statement: String,
        sourceIds: List<String>,
        sourceKind: MemorySourceKind = MemorySourceKind.USER_EXPLICIT,
        observedAt: Long = 1,
    ) = MemoryCandidate(
        kind = MemoryKind.PREFERENCE,
        scope = MemoryScope.USER,
        subject = "user",
        predicate = "prefers",
        objectText = "先听结论",
        statement = statement,
        sourceMessageIds = sourceIds,
        observedAt = observedAt,
        validFrom = observedAt,
        validTo = null,
        confidence = 0.9,
        sourceKind = sourceKind,
        sensitivity = MemorySensitivity.NORMAL,
    )
}

