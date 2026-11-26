package com.xinyu.companion.domain.memory

import javax.inject.Inject
import javax.inject.Singleton

interface MemoryContextProvider {
    suspend fun build(
        companionId: String,
        conversationId: String,
        query: String,
    ): MemoryPromptContext
}

@Singleton
class MemoryContextBuilder @Inject constructor(
    private val store: MemoryStore,
) : MemoryContextProvider {
    override suspend fun build(
        companionId: String,
        conversationId: String,
        query: String,
    ): MemoryPromptContext {
        val profile = buildProfile(store.activeProfileMemories(companionId))
        val summary = store.latestSummary(conversationId)
            ?.content
            ?.let(MemoryPolicy::sanitizeSummary)
            ?.takeIf(String::isNotBlank)
            ?.take(SUMMARY_LIMIT)
        val memories = rankMemories(
            query = query,
            memories = store.searchActiveMemories(companionId, query)
                .filter(MemoryPolicy::isEligibleForPrompt),
        )
        if (memories.isNotEmpty()) {
            store.markMemoryAccessed(memories.map { it.id }, System.currentTimeMillis())
        }
        return MemoryPromptContext(
            profileItems = profile,
            recentSummary = summary,
            relevantMemories = memories,
        )
    }

    private fun buildProfile(memories: List<LongTermMemory>): List<String> {
        val eligible = memories
            .asSequence()
            .filter(MemoryPolicy::isEligibleForPrompt)
            .filter { it.sensitivity == MemorySensitivity.NORMAL }
            .filter {
                it.kind in setOf(
                    MemoryKind.BOUNDARY,
                    MemoryKind.PREFERENCE,
                    MemoryKind.RELATIONSHIP_PREFERENCE,
                    MemoryKind.FACT,
                )
            }
            .sortedWith(
                compareBy<LongTermMemory> {
                    when (it.kind) {
                        MemoryKind.BOUNDARY -> 0
                        MemoryKind.RELATIONSHIP_PREFERENCE -> 1
                        MemoryKind.PREFERENCE -> 2
                        else -> 3
                    }
                }.thenByDescending { it.importance }
                    .thenByDescending { it.updatedAt },
            )
            .map { it.statement }
            .distinct()
            .take(PROFILE_MAX_ITEMS)
            .toList()

        val result = mutableListOf<String>()
        var used = 0
        for (item in eligible) {
            val remaining = PROFILE_LIMIT - used
            if (remaining <= 0) break
            val text = if (item.length <= remaining) item else item.take(remaining)
            if (text.isNotBlank()) result += text
            used += text.length
        }
        return result
    }

    private fun rankMemories(
        query: String,
        memories: List<LongTermMemory>,
    ): List<MemorySnippet> {
        val terms = tokenize(query)
        val now = System.currentTimeMillis()
        val ranked = memories
            .asSequence()
            .map { memory ->
                val haystack = (
                    memory.statement + " " +
                        memory.subject + " " +
                        memory.predicate + " " +
                        memory.objectText
                    ).lowercase()
                val keywordHits = terms.count { haystack.contains(it) }
                val recency = recencyScore(now, memory.observedAt)
                val typeBoost = when (memory.kind) {
                    MemoryKind.BOUNDARY -> 0.45
                    MemoryKind.PREFERENCE -> 0.25
                    MemoryKind.RELATIONSHIP_PREFERENCE -> 0.3
                    MemoryKind.PLAN -> 0.2
                    else -> 0.0
                }
                val score = keywordHits * 2.5 +
                    recency * 1.5 +
                    memory.importance * 0.35 +
                    memory.confidence * 0.5 +
                    typeBoost
                memory to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .filter { it.kind != MemoryKind.BOUNDARY }
            .toList()

        val selected = mutableListOf<LongTermMemory>()
        var used = 0
        for (memory in ranked) {
            val snippet = memory.statement.take(MEMORY_ITEM_LIMIT)
            if (used + snippet.length > MEMORY_CHAR_BUDGET) continue
            selected += memory.copy(statement = snippet)
            used += snippet.length
            if (selected.size >= MEMORY_MAX_ITEMS) break
        }
        return selected.map {
            MemorySnippet(
                id = it.id,
                kind = it.kind,
                statement = it.statement,
                observedAt = it.observedAt,
            )
        }
    }

    private fun tokenize(query: String): List<String> =
        MemoryQueryTokenizer.terms(query)

    private fun recencyScore(now: Long, observedAt: Long): Double {
        val ageDays = ((now - observedAt).coerceAtLeast(0L) / DAY_MS).toDouble()
        return (1.0 - ageDays / 365.0).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val PROFILE_MAX_ITEMS = 8
        const val PROFILE_LIMIT = 600
        const val SUMMARY_LIMIT = 1_200
        const val MEMORY_MAX_ITEMS = 8
        const val MEMORY_ITEM_LIMIT = 320
        const val MEMORY_CHAR_BUDGET = 2_000
        const val DAY_MS = 86_400_000L
    }
}

