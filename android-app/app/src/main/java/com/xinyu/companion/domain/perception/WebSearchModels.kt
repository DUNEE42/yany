package com.xinyu.companion.domain.perception

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val engine: String? = null,
    val publishedAt: String? = null,
)

data class WebSearchResponse(
    val query: String,
    val results: List<WebSearchResult>,
    val searchedAt: Long,
)

interface WebSearchProvider {
    suspend fun search(
        baseUrl: String,
        query: String,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): Result<WebSearchResponse>

    companion object {
        const val DEFAULT_MAX_RESULTS = 5
        const val HARD_MAX_RESULTS = 8
    }
}

class WebSearchException(message: String) : IllegalStateException(message)
