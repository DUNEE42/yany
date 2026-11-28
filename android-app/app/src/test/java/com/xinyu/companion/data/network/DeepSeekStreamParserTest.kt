package com.xinyu.companion.data.network

import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.domain.GenerationEvent
import org.junit.Test

class DeepSeekStreamParserTest {
    private val parser = DeepSeekStreamParser()

    @Test
    fun `parses deepseek content delta`() {
        val event = parser.parse(
            """{"choices":[{"delta":{"content":"我在。"},"finish_reason":null}]}""",
        )

        assertThat(event).isEqualTo(GenerationEvent.Delta("我在。"))
    }

    @Test
    fun `parses done marker`() {
        assertThat(parser.parse("[DONE]")).isEqualTo(GenerationEvent.Completed)
    }

    @Test
    fun `parses usage`() {
        val event = parser.parse(
            """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":8}}""",
        )

        assertThat(event).isEqualTo(GenerationEvent.Usage(12, 8))
    }

    @Test
    fun `maps malformed payload to a visible failure`() {
        assertThat(parser.parse("not-json")).isEqualTo(
            GenerationEvent.Failed(
                code = "malformed_stream",
                message = "模型返回了无法识别的数据，请重试",
                retryable = true,
            ),
        )
    }

    @Test
    fun `maps provider error payload`() {
        val event = parser.parse(
            """{"error":{"type":"invalid_request_error","code":"invalid_model","message":"model not found"}}""",
        )

        assertThat(event).isEqualTo(
            GenerationEvent.Failed(
                code = "invalid_model",
                message = "model not found",
                retryable = false,
            ),
        )
    }

    @Test
    fun `ignores keepalive payload without content`() {
        assertThat(parser.parse("{}")).isNull()
    }

    @Test
    fun `parses non streaming completion fallback`() {
        assertThat(
            parser.parseNonStreaming(
                """{"choices":[{"message":{"content":"普通 JSON 回复"}}]}""",
            ),
        ).isEqualTo(GenerationEvent.Delta("普通 JSON 回复"))
    }

    @Test
    fun `null choice is ignored without reflection errors`() {
        assertThat(parser.parse("""{"choices":[null]}""")).isNull()
    }

    @Test
    fun `null top level json becomes a user facing failure`() {
        assertThat(parser.parse("null")).isEqualTo(
            GenerationEvent.Failed(
                code = "malformed_stream",
                message = "模型返回了无法识别的数据，请重试",
                retryable = true,
            ),
        )
    }

    @Test
    fun `null non streaming choice returns no fallback`() {
        assertThat(parser.parseNonStreaming("""{"choices":[null]}""")).isNull()
    }

    @Test
    fun `parses streaming tool call deltas including parallel calls`() {
        val events = parser.parseEvents(
            """
            {
              "choices": [{
                "delta": {
                  "tool_calls": [
                    {"index": 0, "id": "call_a", "type": "function", "function": {"name": "get_weather", "arguments": "{\"city\":\"苏州\"}"}},
                    {"index": 1, "id": "call_b", "type": "function", "function": {"name": "search_memories", "arguments": "{\"query\":\"咖啡\"}"}}
                  ]
                }
              }]
            }
            """.trimIndent(),
        )

        assertThat(events).containsExactly(
            GenerationEvent.ToolCallDelta(0, "call_a", "get_weather", """{"city":"苏州"}"""),
            GenerationEvent.ToolCallDelta(1, "call_b", "search_memories", """{"query":"咖啡"}"""),
        ).inOrder()
    }

    @Test
    fun `parses non streaming tool calls fallback`() {
        val events = parser.parseNonStreamingEvents(
            """
            {
              "choices": [{
                "message": {
                  "content": "",
                  "tool_calls": [
                    {"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{\"city\":\"南京\"}"}}
                  ]
                }
              }]
            }
            """.trimIndent(),
        )

        assertThat(events).containsExactly(
            GenerationEvent.ToolCallDelta(0, "call_1", "get_weather", """{"city":"南京"}"""),
        )
    }
}
