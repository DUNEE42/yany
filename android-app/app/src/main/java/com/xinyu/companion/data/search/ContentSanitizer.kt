package com.xinyu.companion.data.search

import java.net.URI
import com.xinyu.companion.domain.perception.UntrustedTextPolicy

internal object ContentSanitizer {
    private val scriptOrStyle = Regex(
        """<(script|style|noscript)\b[^>]*>.*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val tags = Regex("""<[^>]+>""")
    private val whitespace = Regex("""\s+""")
    private val controlCharacters = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]""")

    fun plainText(raw: String, maxChars: Int = 800): String {
        val withoutMarkup = raw
            .replace(scriptOrStyle, " ")
            .replace(tags, " ")
        val decoded = decodeCommonEntities(withoutMarkup)
        return controlCharacters.replace(decoded, "")
            .replace(whitespace, " ")
            .trim()
            .take(maxChars)
    }

    fun normalizedHttpUrl(raw: String): String? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        if (uri.host.isNullOrBlank()) return null
        if (!uri.userInfo.isNullOrBlank()) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.path?.ifBlank { "/" } ?: "/"
        return buildString {
            append(uri.scheme.lowercase())
            append("://")
            append(uri.host.lowercase())
            append(port)
            append(path)
            uri.query?.takeIf(String::isNotBlank)?.let {
                append("?")
                append(it)
            }
        }
    }

    fun containsInstructionLikeText(value: String): Boolean =
        UntrustedTextPolicy.containsInstructionLikeText(value)

    private fun decodeCommonEntities(value: String): String = value
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
}
