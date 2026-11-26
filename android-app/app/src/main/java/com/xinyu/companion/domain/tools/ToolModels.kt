package com.xinyu.companion.domain.tools

import com.xinyu.companion.domain.ToolDefinition
import com.xinyu.companion.domain.perception.WebSearchResponse

data class ToolExecutionContext(
    val companionId: String,
    val conversationId: String,
    val userMessage: String,
    val approvedSearchQuery: String? = null,
    val preFetchedSearch: WebSearchResponse? = null,
    val requestSearchApproval: suspend (suggestedQuery: String) -> String? = { null },
)

data class ToolExecutionResult(
    val content: String,
    val searchResponse: WebSearchResponse? = null,
    val usedWeather: Boolean = false,
)

interface CompanionTool {
    val definition: ToolDefinition
    val statusLabel: String

    suspend fun execute(
        argumentsJson: String,
        context: ToolExecutionContext,
    ): ToolExecutionResult
}

class ToolRegistry(
    tools: List<CompanionTool>,
) {
    private val byName: Map<String, CompanionTool> = tools.associateBy { it.definition.name }

    val definitions: List<ToolDefinition> = tools.map { it.definition }

    fun isEmpty(): Boolean = byName.isEmpty()

    fun find(name: String): CompanionTool? = byName[name]

    companion object {
        val EMPTY = ToolRegistry(emptyList())
    }
}
