package com.xinyu.companion.data.network

import com.xinyu.companion.domain.GenerationEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

class DeepSeekStreamParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(data: String): GenerationEvent? {
        val clean = data.trim()
        if (clean.isEmpty()) return null
        if (clean == "[DONE]") return GenerationEvent.Completed

        val payload = runCatching {
            json.parseToJsonElement(clean) as? JsonObject
        }.getOrNull() ?: run {
            return GenerationEvent.Failed(
                code = "malformed_stream",
                message = "模型返回了无法识别的数据，请重试",
                retryable = true,
            )
        }

        providerError(payload["error"])?.let { return it }

        val choice = (payload["choices"] as? JsonArray)
            ?.firstOrNull()
            as? JsonObject
        val content = (
            (choice?.get("delta") as? JsonObject)
                ?.get("content") as? JsonPrimitive
            )?.contentOrNull

        if (!content.isNullOrEmpty()) {
            return GenerationEvent.Delta(content)
        }

        val usage = payload["usage"] as? JsonObject ?: return null
        return GenerationEvent.Usage(
            inputTokens = (usage["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
            outputTokens = (usage["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    fun parseNonStreaming(data: String): GenerationEvent? {
        val clean = data.trim()
        if (clean.isEmpty()) return null
        val payload = runCatching {
            json.parseToJsonElement(clean) as? JsonObject
        }.getOrNull() ?: return null

        providerError(payload["error"])?.let { return it }

        val choice = (payload["choices"] as? JsonArray)
            ?.firstOrNull()
            as? JsonObject
        val content = (
            (choice?.get("message") as? JsonObject)
                ?.get("content") as? JsonPrimitive
            )?.contentOrNull

        return content
            ?.takeIf(String::isNotBlank)
            ?.let(GenerationEvent::Delta)
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
