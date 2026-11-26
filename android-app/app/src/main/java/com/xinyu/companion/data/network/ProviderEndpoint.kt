package com.xinyu.companion.data.network

import com.xinyu.companion.domain.provider.ProviderConfig

internal fun chatCompletionsUrl(baseUrl: String): String {
    val normalized = baseUrl.trim().trimEnd('/')
        .ifBlank { ProviderConfig.DEFAULT_BASE_URL }
    return if (normalized.endsWith("/chat/completions")) {
        normalized
    } else {
        "$normalized/chat/completions"
    }
}
