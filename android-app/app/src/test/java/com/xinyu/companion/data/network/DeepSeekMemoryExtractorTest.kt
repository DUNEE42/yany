package com.xinyu.companion.data.network

import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.domain.GenerationEvent
import com.xinyu.companion.domain.MessageRole
import com.xinyu.companion.domain.memory.MemoryJob
import com.xinyu.companion.domain.memory.MemoryJobInput
import com.xinyu.companion.domain.memory.MemoryJobStatus
import com.xinyu.companion.domain.memory.MemoryKind
import com.xinyu.companion.domain.memory.MemorySourceKind
import com.xinyu.companion.domain.memory.MemorySourceMessage
import com.xinyu.companion.domain.provider.ChatProvider
import com.xinyu.companion.domain.provider.ChatRequest
import com.xinyu.companion.domain.provider.ProviderCapabilities
import com.xinyu.companion.domain.provider.ProviderConfig
import com.xinyu.companion.domain.settings.ProviderSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

class DeepSeekMemoryExtractorTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `parses strict extraction json and preserves source ids`() = runTest {
        val provider = FakeMemoryProvider(
            """
            ```json
            {
              "summary": "2026-11-25，用户明确表示周五要去杭州面试。",
              "memories": [
                {
                  "kind": "PLAN",
                  "scope": "USER",
                  "subject": "user",
                  "predicate": "plans_interview_in",
                  "objectText": "杭州",
                  "statement": "用户计划周五去杭州参加面试。",
                  "sourceMessageIds": ["m1"],
                  "observedAt": "2026-11-25T20:00:00+08:00",
                  "validFrom": "2026-11-25T20:00:00+08:00",
                  "validTo": null,
                  "confidence": 0.96,
                  "sourceKind": "USER_EXPLICIT",
                  "sensitivity": "NORMAL",
                  "importance": 4
                }
              ]
            }
            ```
            """.trimIndent(),
        )
        val extractor = DeepSeekMemoryExtractor(
            provider = provider,
            settingsStore = FakeMemorySettingsStore(),
            json = json,
        )

        val result = extractor.extract(input()).getOrThrow()

        assertThat(result.summary).contains("杭州面试")
        assertThat(result.candidates).hasSize(1)
        assertThat(result.candidates.single().kind).isEqualTo(MemoryKind.PLAN)
        assertThat(result.candidates.single().sourceKind)
            .isEqualTo(MemorySourceKind.USER_EXPLICIT)
        assertThat(result.candidates.single().sourceMessageIds).containsExactly("m1")
        assertThat(provider.lastRequest!!.conversationStyle.temperature).isEqualTo(0.15)
        assertThat(provider.lastRequest!!.maxOutputTokens).isEqualTo(1_600)
        assertThat(provider.lastRequest!!.context.first().content).contains("只输出一个 JSON")
    }

    @Test
    fun `invalid json is a failed extraction`() = runTest {
        val extractor = DeepSeekMemoryExtractor(
            provider = FakeMemoryProvider("这不是 JSON"),
            settingsStore = FakeMemorySettingsStore(),
            json = json,
        )

        val result = extractor.extract(input())

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("有效 JSON")
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
                id = "m1",
                role = MessageRole.USER,
                sequence = 1,
                content = "我周五要去杭州面试",
                createdAt = 1_764_072_000_000L,
            ),
            MemorySourceMessage(
                id = "m2",
                role = MessageRole.ASSISTANT,
                sequence = 2,
                content = "祝你顺利。",
                createdAt = 1_764_072_001_000L,
            ),
        ),
        existingMemories = emptyList(),
    )
}

private class FakeMemoryProvider(
    response: String,
) : ChatProvider {
    var lastRequest: ChatRequest? = null
    private val events: Flow<GenerationEvent> = flowOf(
        GenerationEvent.Delta(response),
        GenerationEvent.Completed,
    )

    override val id: String = "fake-memory"
    override val capabilities = ProviderCapabilities(
        streaming = true,
        images = false,
        tools = false,
        usageReporting = false,
    )

    override fun stream(request: ChatRequest): Flow<GenerationEvent> {
        lastRequest = request
        return events
    }

    override suspend fun testConnection(config: ProviderConfig): Result<Unit> =
        Result.success(Unit)
}

private class FakeMemorySettingsStore : ProviderSettingsStore {
    override fun load(): ProviderConfig = ProviderConfig(apiKey = "test-key")

    override fun save(config: ProviderConfig) = Unit

    override fun clearApiKey() = Unit
}

