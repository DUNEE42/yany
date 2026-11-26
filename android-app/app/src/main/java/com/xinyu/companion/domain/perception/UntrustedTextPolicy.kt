package com.xinyu.companion.domain.perception

object UntrustedTextPolicy {
    private val instructionLikePatterns = listOf(
        Regex(
            """(?i)(?:ignore|disregard|forget).{0,40}(?:previous|above|system).{0,30}(?:instruction|prompt|message)""",
        ),
        Regex(
            """(?:忽略|无视|忘记|跳过).{0,40}(?:之前|以上|系统|开发者).{0,30}(?:指令|提示|消息|规则)""",
        ),
        Regex("""(?i)(?:system prompt|developer message|jailbreak|do anything now)"""),
        Regex("""(?:从现在开始|接下来你).{0,24}(?:扮演|忽略|不要遵守|执行)"""),
    )

    fun containsInstructionLikeText(value: String): Boolean =
        instructionLikePatterns.any { it.containsMatchIn(value) }
}
