package com.xinyu.companion.data.search

import com.xinyu.companion.BuildConfig
import com.xinyu.companion.domain.perception.WebSearchException
import com.xinyu.companion.domain.perception.WebSearchProvider
import com.xinyu.companion.domain.perception.WebSearchResponse
import com.xinyu.companion.domain.perception.WebSearchResult
import com.xinyu.companion.data.network.readUtf8Within
import java.net.URI
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class SearxngWebSearchProvider internal constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val requireHttps: Boolean = true,
) : WebSearchProvider {
    @Inject
    constructor(
        client: OkHttpClient,
        json: Json,
    ) : this(client, json, requireHttps = true)

    override suspend fun search(
        baseUrl: String,
        query: String,
        maxResults: Int,
    ): Result<WebSearchResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanQuery = query.trim()
            require(cleanQuery.isNotBlank()) { "搜索内容不能为空" }
            require(cleanQuery.length <= MAX_QUERY_CHARS) { "搜索内容过长" }
            val root = validatedHttpsRoot(baseUrl)
            val endpoint = root.newBuilder()
                .addPathSegments("search")
                .addQueryParameter("q", cleanQuery)
                .addQueryParameter("format", "json")
                .addQueryParameter("language", "zh-CN")
                .addQueryParameter("safesearch", "1")
                .addQueryParameter("categories", "general")
                .build()
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("User-Agent", "YanYu-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            client.newBuilder()
                .callTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) {
                        throw WebSearchException(httpMessage(response.code))
                    }
                    val declaredLength = response.body?.contentLength() ?: 0L
                    if (declaredLength > MAX_RESPONSE_BYTES) {
                        throw WebSearchException("搜索服务返回内容过大")
                    }
                    val body = response.body?.readUtf8Within(MAX_RESPONSE_BYTES).orEmpty()
                    val dto = json.decodeFromString<SearxResponse>(body)
                    val results = dto.results
                        .asSequence()
                        .mapNotNull(::toResult)
                        .distinctBy { it.url }
                        .take(maxResults.coerceIn(1, WebSearchProvider.HARD_MAX_RESULTS))
                        .toList()
                    WebSearchResponse(
                        query = cleanQuery,
                        results = results,
                        searchedAt = System.currentTimeMillis(),
                    )
                }
        }.recoverCatching { error ->
            if (error is CancellationException) throw error
            if (error is WebSearchException) throw error
            throw WebSearchException(error.message ?: "联网搜索失败")
        }
    }

    private fun validatedHttpsRoot(raw: String) = raw.trim()
        .takeIf(String::isNotBlank)
        ?.let { URI(it) }
        ?.also { uri ->
            val validScheme = if (requireHttps) {
                uri.scheme.equals("https", ignoreCase = true)
            } else {
                uri.scheme.lowercase() in setOf("http", "https")
            }
            if (!validScheme || uri.host.isNullOrBlank()) {
                throw WebSearchException("SearXNG 地址必须是有效的 HTTPS 地址")
            }
            if (!uri.userInfo.isNullOrBlank()) {
                throw WebSearchException("SearXNG 地址不能包含账号密码")
            }
            if (!uri.query.isNullOrBlank() || !uri.fragment.isNullOrBlank()) {
                throw WebSearchException("SearXNG 地址只能填写服务根地址")
            }
        }
        ?.let { raw.trim().trimEnd('/').toHttpUrlOrNull() }
        ?: throw WebSearchException("请先填写 SearXNG 地址")

    private fun toResult(dto: SearxResultDto): WebSearchResult? {
        val normalizedUrl = ContentSanitizer.normalizedHttpUrl(dto.url ?: return null)
            ?: return null
        val title = ContentSanitizer.plainText(dto.title.orEmpty(), maxChars = 160)
        if (title.isBlank()) return null
        val snippet = ContentSanitizer.plainText(dto.content.orEmpty())
        if (ContentSanitizer.containsInstructionLikeText("$title $snippet")) return null
        return WebSearchResult(
            title = title,
            url = normalizedUrl,
            snippet = snippet,
            engine = dto.engine?.trim()?.take(40),
            publishedAt = dto.publishedDate?.trim()?.take(64),
        )
    }

    private fun httpMessage(code: Int): String = when (code) {
        400 -> "SearXNG 拒绝了搜索请求，请检查服务是否启用 JSON 输出"
        401, 403 -> "当前 SearXNG 服务不允许此客户端搜索"
        404 -> "找不到 SearXNG 搜索接口"
        429 -> "SearXNG 请求过于频繁，请稍后再试"
        in 500..599 -> "SearXNG 服务暂时不可用"
        else -> "联网搜索失败（HTTP $code）"
    }

    @Serializable
    private data class SearxResponse(
        val results: List<SearxResultDto> = emptyList(),
    )

    @Serializable
    private data class SearxResultDto(
        val url: String? = null,
        val title: String? = null,
        val content: String? = null,
        val engine: String? = null,
        @SerialName("publishedDate") val publishedDate: String? = null,
    )

    private companion object {
        const val MAX_QUERY_CHARS = 300
        const val MAX_RESPONSE_BYTES = 1_000_000L
        const val REQUEST_TIMEOUT_SECONDS = 12L
    }
}
