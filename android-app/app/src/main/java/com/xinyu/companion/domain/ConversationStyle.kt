package com.xinyu.companion.domain

enum class ReplyLength {
    SHORT,
    NATURAL,
    DETAILED,
}

data class ConversationStyle(
    val temperature: Double = 0.72,
    val closeness: Int = 45,
    val replyLength: ReplyLength = ReplyLength.NATURAL,
) {
    init {
        require(temperature in MIN_TEMPERATURE..MAX_TEMPERATURE)
        require(closeness in 0..100)
    }

    companion object {
        const val MIN_TEMPERATURE = 0.0
        const val MAX_TEMPERATURE = 1.5
    }
}
