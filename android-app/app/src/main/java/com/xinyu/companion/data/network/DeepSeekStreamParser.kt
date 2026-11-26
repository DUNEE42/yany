package com.xinyu.companion.data.network

import com.xinyu.companion.domain.GenerationEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

class DeepSeekStreamParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(data: String): GenerationEvent? = parseEvents(data).firstOrNull()

    fun parseEvents(data: String): List<GenerationEvent> {
        val clean = data.trim()
        if (clean.isEmpty()) return emptyList()
        if (clean == "[DONE]") return listOf(GenerationEvent.Completed)

        val payload = runCatching {
            json.parseToJsonElement(clean) as? JsonObject
        }.getOrNull() ?: return listOf(
            GenerationEvent.Failed(
                code = "malformed_stream",
                message = "模型返回了无法识别的数据，请重试",
                retryable = true,
            ),
        )

        providerError(payload["error"])?.let { return listOf(it) }

        val events = mutableListOf<GenerationEvent>()
        val choice = (payload["choices"] as? JsonArray)
            ?.firstOrNull()
            as? JsonObject
        val delta = choice?.get("delta") as? JsonObject

        val content = (delta?.get("content") as? JsonPrimitive)?.contentOrNull
        if (!content.isNullOrEmpty()) {
            events += GenerationEvent.Delta(content)
        }

        val toolCalls = delta?.get("tool_calls") as? JsonArray
        toolCalls?.forEachIndexed { fallbackIndex, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            val index = (obj["index"] as? JsonPrimitive)?.intOrNull ?: fallbackIndex
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
            val functionObj = obj["function"] as? JsonObject
            val name = (functionObj?.get("name") as? JsonPrimitive)?.contentOrNull
            val arguments = (functionObj?.get("arguments") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (id != null || name != null || arguments.isNotEmpty()) {
                events += GenerationEvent.ToolCallDelta(
                    index = index,
                    id = id,
                    name = name,
                    argumentsDelta = arguments,
                )
            }
        }

        val usage = payload["usage"] as? JsonObject
        if (usage != null) {
            events += GenerationEvent.Usage(
                inputTokens = (usage["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
                outputTokens = (usage["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }

        return events
    }

    fun parseNonStreaming(data: String): GenerationEvent? =
        parseNonStreamingEvents(data).firstOrNull()

    fun parseNonStreamingEvents(data: String): List<GenerationEvent> {
        val clean = data.trim()
        if (clean.isEmpty()) return emptyList()
        val payload = runCatching {
            json.parseToJsonElement(clean) as? JsonObject
        }.getOrNull() ?: return emptyList()

        providerError(payload["error"])?.let { return listOf(it) }

        val events = mutableListOf<GenerationEvent>()
        val choice = (payload["choices"] as? JsonArray)
            ?.firstOrNull()
            as? JsonObject
        val message = choice?.get("message") as? JsonObject

        val content = (message?.get("content") as? JsonPrimitive)?.contentOrNull
        if (!content.isNullOrBlank()) {
            events += GenerationEvent.Delta(content)
        }

        val toolCalls = message?.get("tool_calls") as? JsonArray
        toolCalls?.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
            val functionObj = obj["function"] as? JsonObject
            val name = (functionObj?.get("name") as? JsonPrimitive)?.contentOrNull
            val arguments = (functionObj?.get("arguments") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (!name.isNullOrBlank()) {
                events += GenerationEvent.ToolCallDelta(
                    index = index,
                    id = id,
                    name = name,
                    argumentsDelta = arguments,
                )
            }
        }

        return events
    }

    private fun providerError(error: JsonElement?): GenerationEvent.Failed? {
        if (error == null || error is JsonNull) return null
        val objectError = error as? JsonObject
        val message = (objectError?.get("message") as? JsonPrimitive)
            ?.contentOrNull
            ?: (error as? JsonPrimitive)?.contentOrNull
            ?: "模型服务返回错误，请重试"
        val errorType = (objectError?.get("type") as? JsonPrimitive)
            ?.contentOrNull
            .orEmpty()
        val errorCode = (objectError?.get("code") as? JsonPrimitive)
            ?.contentOrNull
            .orEmpty()
        val classification = "$errorType $errorCode".lowercase()
        val retryable = listOf(
            "auth",
            "authentication",
            "invalid_api_key",
            "insufficient_quota",
            "permission",
            "invalid_request",
        ).none(classification::contains)

        return GenerationEvent.Failed(
            code = errorCode.ifBlank { "provider_stream_error" },
            message = message,
            retryable = retryable,
        )
    }
}
