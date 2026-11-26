package com.xinyu.companion.data.network

import com.xinyu.companion.BuildConfig
import com.xinyu.companion.domain.GenerationEvent
import com.xinyu.companion.domain.ReplyLength
import com.xinyu.companion.domain.provider.ChatProvider
import com.xinyu.companion.domain.provider.ChatRequest
import com.xinyu.companion.domain.provider.ProviderCapabilities
import com.xinyu.companion.domain.provider.ProviderConfig
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class DeepSeekApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val parser: DeepSeekStreamParser = DeepSeekStreamParser(json),
) : ChatProvider {
    override val id: String = "deepseek"

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        streaming = true,
        images = false,
        tools = true,
        usageReporting = true,
    )

    override fun stream(request: ChatRequest): Flow<GenerationEvent> = callbackFlow {
        val config = request.config
        require(config.isConfigured) { "请先配置 DeepSeek API Key" }

        val generationId = "local_${UUID.randomUUID()}"
        val finished = AtomicBoolean(false)
        val sawDone = AtomicBoolean(false)
        val httpRequest = Request.Builder()
            .url(chatCompletionsUrl(config.baseUrl))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "text/event-stream")
            .header("X-Client", "YanYu-Android/${BuildConfig.VERSION_NAME}")
            .post(
                streamPayload(request)
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()

        fun finish(event: GenerationEvent) {
            if (finished.compareAndSet(false, true)) {
                trySend(event)
                close()
            }
        }

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                trySend(GenerationEvent.Started(generationId, null))
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                val events = runCatching { parser.parseEvents(data) }.getOrElse {
                    finish(
                        GenerationEvent.Failed(
                            code = "stream_parse_error",
                            message = "流式回复格式异常，请重试",
                            retryable = true,
                        ),
                    )
                    return
                }
                for (event in events) {
                    when (event) {
                        is GenerationEvent.Completed -> {
                            sawDone.set(true)
                            finish(GenerationEvent.Completed)
                            return
                        }
                        is GenerationEvent.Failed -> {
                            finish(event)
                            return
                        }
                        else -> trySend(event)
                    }
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                val fallbackBody = response
                    ?.takeIf { it.isSuccessful }
                    ?.let {
                        runCatching { it.peekBody(BODY_INSPECTION_LIMIT).string() }.getOrNull()
                    }
                val fallbackEvents = runCatching {
                    fallbackBody?.let(parser::parseNonStreamingEvents).orEmpty()
                }.getOrDefault(emptyList())

                val failure = fallbackEvents.filterIsInstance<GenerationEvent.Failed>().firstOrNull()
                if (failure != null) {
                    finish(failure)
                    return
                }
                val meaningful = fallbackEvents.filter {
                    it is GenerationEvent.Delta || it is GenerationEvent.ToolCallDelta
                }
                if (meaningful.isNotEmpty()) {
                    meaningful.forEach { trySend(it) }
                    finish(GenerationEvent.Completed)
                } else {
                    finish(response.toFailureEvent(t))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (sawDone.get()) {
                    finish(GenerationEvent.Completed)
                } else {
                    finish(
                        GenerationEvent.Failed(
                            code = "stream_incomplete",
                            message = "回复连接提前结束，请重试",
                            retryable = true,
                        ),
                    )
                }
            }
        }

        val source = EventSources.createFactory(client).newEventSource(httpRequest, listener)
        awaitClose {
            finished.set(true)
            source.cancel()
        }
    }

    override suspend fun testConnection(config: ProviderConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(config.isConfigured) { "请先填写 DeepSeek API Key" }
            val request = Request.Builder()
                .url(chatCompletionsUrl(config.baseUrl))
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("X-Client", "YanYu-Android/${BuildConfig.VERSION_NAME}")
                .post(
                    testPayload(config).toRequestBody(JSON_MEDIA_TYPE),
                )
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ProviderConnectionException(response.httpFailureMessage())
                }
                val body = response.body?.string().orEmpty()
                if (!body.contains("\"choices\"")) {
                    throw ProviderConnectionException("DeepSeek 返回了无法识别的数据")
                }
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun streamPayload(request: ChatRequest): String = buildJsonObject {
        put(
            "model",
            request.config.model.trim().ifBlank { ProviderConfig.DEFAULT_MODEL },
        )
        putJsonArray("messages") {
            request.context.forEach { turn ->
                add(
                    buildJsonObject {
                        put("role", turn.role)
                        put("content", turn.content)
                        if (turn.toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                turn.toolCalls.forEach { call ->
                                    add(
                                        buildJsonObject {
                                            put("id", call.id)
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", call.name)
                                                put("arguments", call.argumentsJson)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        turn.toolCallId?.let { put("tool_call_id", it) }
                        turn.toolName?.let { put("name", it) }
                    },
                )
            }
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    val schema = runCatching {
                        json.parseToJsonElement(tool.parametersJsonSchema) as? JsonObject
                    }.getOrNull() ?: buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                    }
                    add(
                        buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", schema)
                            }
                        },
                    )
                }
            }
            put("tool_choice", request.toolChoice ?: "auto")
        }
        put("stream", true)
        putJsonObject("stream_options") {
            put("include_usage", true)
        }
        put("temperature", request.conversationStyle.temperature)
        put(
            "max_tokens",
            request.maxOutputTokens ?: when (request.conversationStyle.replyLength) {
                ReplyLength.SHORT -> 300
                ReplyLength.NATURAL -> 600
                ReplyLength.DETAILED -> 1_000
            },
        )
    }.toString()

    private fun testPayload(config: ProviderConfig): String = buildJsonObject {
        put("model", config.model.trim().ifBlank { ProviderConfig.DEFAULT_MODEL })
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", "只回复 OK")
                },
            )
        }
        put("stream", false)
        put("max_tokens", 8)
    }.toString()

    private fun Response.httpFailureMessage(): String = when (code) {
        400 -> "请求格式或模型名称不正确"
        401 -> "DeepSeek Key 无效，请重新填写"
        402 -> "DeepSeek 账户余额不足"
        403 -> "当前 Key 没有调用权限"
        404 -> "找不到接口或模型，请检查模型名称"
        429 -> "请求太频繁或额度受限，请稍后再试"
        in 500..599 -> "DeepSeek 服务暂时不可用，请稍后重试"
        else -> "DeepSeek 请求失败（HTTP $code）"
    }

    private fun Response.isRetryableFailure(): Boolean =
        code == 408 || code == 409 || code == 429 || code >= 500

    private fun Response?.toFailureEvent(throwable: Throwable?): GenerationEvent.Failed {
        if (this == null) {
            return GenerationEvent.Failed(
                code = "network_error",
                message = throwable?.toUserMessage() ?: "无法连接 DeepSeek，请检查网络后重试",
                retryable = true,
            )
        }
        if (!isSuccessful) {
            return GenerationEvent.Failed(
                code = "http_$code",
                message = httpFailureMessage(),
                retryable = isRetryableFailure(),
            )
        }

        val bodyPreview = runCatching {
            peekBody(BODY_INSPECTION_LIMIT).string()
        }.getOrDefault("")
        val looksLikeHtml = bodyPreview
            .trimStart()
            .startsWith("<!DOCTYPE", ignoreCase = true) ||
            bodyPreview.trimStart().startsWith("<html", ignoreCase = true)
        return when {
            looksLikeHtml -> GenerationEvent.Failed(
                code = "html_response",
                message = "服务器返回了网页而不是 API 数据，可能有网络代理或登录页面拦截了请求",
                retryable = true,
            )
            throwable?.message.orEmpty().contains("Invalid content-type", ignoreCase = true) ->
                GenerationEvent.Failed(
                    code = "invalid_content_type",
                    message = "服务已连接，但返回的不是流式响应。请检查网络代理、接口地址或供应商状态",
                    retryable = true,
                )
            throwable?.message.orEmpty().contains("unexpected end", ignoreCase = true) ->
                GenerationEvent.Failed(
                    code = "stream_incomplete",
                    message = "回复连接提前结束，请重试",
                    retryable = true,
                )
            else -> GenerationEvent.Failed(
                code = "invalid_response",
                message = "服务返回了 HTTP 200，但内容不完整或格式异常，请重试",
                retryable = true,
            )
        }
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is UnknownHostException -> "找不到 DeepSeek 服务，请检查网络"
        is SocketTimeoutException -> "连接 DeepSeek 超时，请稍后重试"
        is SSLException -> "安全连接失败，请检查手机网络或代理"
        is IOException -> "网络连接中断，请检查网络后重试"
        else -> message ?: "无法连接 DeepSeek"
    }

    private companion object {
        const val BODY_INSPECTION_LIMIT = 128L * 1024L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class ProviderConnectionException(message: String) : Exception(message)
