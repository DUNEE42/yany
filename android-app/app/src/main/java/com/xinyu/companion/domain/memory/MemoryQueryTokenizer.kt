package com.xinyu.companion.domain.memory

/**
 * Small dependency-free tokenizer for local Chinese memory retrieval.
 *
 * SQLite FTS treats a long Chinese sentence as one token in many configurations,
 * so we also produce two- and three-character windows for direct matching. This
 * is intentionally a retrieval fallback, not a linguistic parser.
 */
object MemoryQueryTokenizer {
    private val separators = Regex("""[\s，。！？、,.!?;；:：()（）\[\]【】“”"'《》]+""")
    private val hanOnly = Regex("""^\p{IsHan}+$""")
    private val stopTerms = setOf(
        "你还", "还记", "记得", "记不", "不记", "什么", "怎么", "是否",
        "有没", "没有", "告诉", "说说", "一下", "关于", "我的", "你的",
        "我们", "你们", "这个", "那个", "可以", "能不能",
    )

    fun terms(query: String): List<String> {
        val result = linkedSetOf<String>()
        query.lowercase()
            .split(separators)
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { chunk ->
                addLatinTerms(chunk, result)
                if (!hanOnly.matches(chunk)) return@forEach
                val normalized = chunk.trimEnd('吗', '呢', '啊', '吧', '呀', '嘛')
                if (normalized.length == 1) result += normalized

                if (normalized.length in 1..2) {
                    addIfUseful(normalized, result)
                }
                for (size in 2..3) {
                    if (normalized.length < size) continue
                    normalized.windowed(size).forEach { addIfUseful(it, result) }
                }
            }
        return result.take(24)
    }

    private fun addLatinTerms(chunk: String, result: MutableSet<String>) {
        Regex("""[a-z0-9_]+""").findAll(chunk).forEach { match ->
            addIfUseful(match.value, result)
        }
    }

    private fun addIfUseful(term: String, result: MutableSet<String>) {
        if (term.isBlank() || term in stopTerms) return
        if (term.length == 1 && term[0].code !in 48..122) return
        result += term
    }
}
