package com.xinyu.companion.data.network

import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.domain.ContextTurn
import com.xinyu.companion.domain.ConversationStyle
import com.xinyu.companion.domain.GenerationEvent
import com.xinyu.companion.domain.ReplyLength
import com.xinyu.companion.domain.provider.ChatRequest
import com.xinyu.companion.domain.provider.ProviderConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class DeepSeekApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: DeepSeekApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = DeepSeekApi(
            client = OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `streams deltas and completes after done marker`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"你"}}]}

                    data: {"choices":[{"delta":{"content":"好。"}}]}

                    data: [DONE]

                    """.trimIndent() + "\n\n",
                ),
        )

        val events = withTimeout(5_000) {
            api.stream(request()).toList()
        }

        assertThat(events.filterIsInstance<GenerationEvent.Delta>().map { it.text })
            .containsExactly("你", "好。")
            .inOrder()
        assertThat(events.last()).isEqualTo(GenerationEvent.Completed)
    }

    @Test
    fun `maps unauthorized response to non retryable failure`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"invalid key"}}"""),
        )

        val events = withTimeout(5_000) {
            api.stream(request()).toList()
        }
        val failure = events.filterIsInstance<GenerationEvent.Failed>().single()

        assertThat(failure.message).contains("Key")
        assertThat(failure.retryable).isFalse()
    }

    @Test
    fun `premature close is reported as retryable incomplete stream`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"半句"}}]}

                    """.trimIndent() + "\n\n",
                ),
        )

        val events = withTimeout(5_000) {
            api.stream(request()).toList()
        }
        val failure = events.filterIsInstance<GenerationEvent.Failed>().single()

        assertThat(failure.code).isEqualTo("stream_incomplete")
        assertThat(failure.retryable).isTrue()
    }

    @Test
    fun `request uses style temperature and length budget`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )

        withTimeout(5_000) {
            api.stream(
                request().copy(
                    conversationStyle = ConversationStyle(
                        temperature = 1.1,
                        closeness = 70,
                        replyLength = ReplyLength.DETAILED,
                    ),
                ),
            ).toList()
        }

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"temperature\":1.1")
        assertThat(body).contains("\"max_tokens\":1000")
    }

    @Test
    fun `request can override max tokens for structured memory extraction`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )

        withTimeout(5_000) {
            api.stream(request().copy(maxOutputTokens = 1_600)).toList()
        }

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"max_tokens\":1600")
    }

    @Test
    fun `stream provider error becomes a visible failure`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"error":{"type":"invalid_request_error","code":"invalid_model","message":"model not found"}}

                    """.trimIndent() + "\n\n",
                ),
        )

        val events = withTimeout(5_000) {
            api.stream(request()).toList()
        }
        val failure = events.filterIsInstance<GenerationEvent.Failed>().single()

        assertThat(failure.code).isEqualTo("invalid_model")
        assertThat(failure.message).isEqualTo("model not found")
        assertThat(failure.retryable).isFalse()
    }

    @Test
    fun `http 200 json completion is accepted as a fallback`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"message":{"content":"备用回复"}}]}""",
                ),
        )

        val events = withTimeout(5_000) {
            api.stream(request()).toList()
        }

        assertThat(events.filterIsInstance<GenerationEvent.Delta>().single().text)
            .isEqualTo("备用回复")
        assertThat(events.last()).isEqualTo(GenerationEvent.Completed)
    }

    @Test
    fun `http 200 json error exposes the provider message`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"error":{"type":"invalid_request_error","code":"invalid_model","message":"model unavailable"}}""",
                ),
        )

        val events = withTimeout(5_000) {
            api.stream(request()).toList()
        }
        val failure = events.filterIsInstance<GenerationEvent.Failed>().single()

        assertThat(failure.code).isEqualTo("invalid_model")
        assertThat(failure.message).isEqualTo("model unavailable")
        assertThat(failure.retryable).isFalse()
    }

    @Test
    fun `http 200 html response is identified as an intercept page`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<!DOCTYPE html><html><body>login required</body></html>"),
        )

        val events = withTimeout(5_000) {
            api.stream(request()).toList()
        }
        val failure = events.filterIsInstance<GenerationEvent.Failed>().single()

        assertThat(failure.code).isEqualTo("html_response")
        assertThat(failure.message).contains("网页")
        assertThat(failure.retryable).isTrue()
    }

    @Test
    fun `http 200 null choice does not expose kotlin reflection error`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[null]}"""),
        )

        val events = withTimeout(5_000) {
            api.stream(request()).toList()
        }
        val failure = events.filterIsInstance<GenerationEvent.Failed>().single()

        assertThat(failure.code).isEqualTo("invalid_content_type")
        assertThat(failure.message).doesNotContain("JsonNull")
        assertThat(failure.message).doesNotContain("reflection")
    }

    @Test
    fun `serializes tools and tool result turns and parses streamed tool call deltas`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":"}}]}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"苏州\"}"}}]}}]}

                    data: [DONE]

                    """.trimIndent() + "\n\n",
                ),
        )

        val events = withTimeout(5_000) {
            api.stream(
                request().copy(
                    context = listOf(
                        ContextTurn(id = "u1", role = "user", content = "苏州冷吗", sequence = 1),
                        ContextTurn(
                            id = "a1",
                            role = "assistant",
                            content = "",
                            sequence = 2,
                            toolCalls = listOf(
                                com.xinyu.companion.domain.ToolCall(
                                    id = "call_prev",
                                    name = "search_memories",
                                    argumentsJson = """{"query":"苏州"}""",
                                ),
                            ),
                        ),
                        ContextTurn(
                            id = "t1",
                            role = "tool",
                            content = "记忆内容",
                            sequence = 3,
                            toolCallId = "call_prev",
                            toolName = "search_memories",
                        ),
                    ),
                    tools = listOf(
                        com.xinyu.companion.domain.ToolDefinition(
                            name = "get_weather",
                            description = "查询实时天气",
                            parametersJsonSchema = """{"type":"object","properties":{"city":{"type":"string"}}}""",
                        ),
                    ),
                    toolChoice = "auto",
                ),
            ).toList()
        }

        val sentBody = server.takeRequest().body.readUtf8()
        assertThat(sentBody).contains("\"tools\"")
        assertThat(sentBody).contains("\"tool_choice\":\"auto\"")
        assertThat(sentBody).contains("\"tool_call_id\":\"call_prev\"")
        assertThat(sentBody).contains("\"tool_calls\"")

        val toolDeltas = events.filterIsInstance<GenerationEvent.ToolCallDelta>()
        assertThat(toolDeltas).hasSize(2)
        assertThat(toolDeltas[0].name).isEqualTo("get_weather")
        assertThat(toolDeltas[0].argumentsDelta + toolDeltas[1].argumentsDelta)
            .isEqualTo("""{"city":"苏州"}""")
        assertThat(events.last()).isEqualTo(GenerationEvent.Completed)
    }

    private fun request() = ChatRequest(
        context = listOf(
            ContextTurn(
                id = "u1",
                role = "user",
                content = "你好",
                sequence = 1,
            ),
        ),
        config = ProviderConfig(
            apiKey = "sk-test",
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            model = "deepseek-chat",
        ),
    )
}
