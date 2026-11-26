package com.xinyu.companion.domain

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

enum class MessageStatus {
    PENDING,
    STREAMING,
    COMPLETED,
    INTERRUPTED,
    FAILED,
}

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val sequence: Long,
    val content: String,
    val status: MessageStatus,
    val createdAt: Long,
    val clientMessageId: String? = null,
    val replyToMessageId: String? = null,
)

data class ChatTurn(
    val user: ChatMessage,
    val assistant: ChatMessage,
)

data class MemoryRecord(
    val id: String,
    val companionId: String,
    val type: String,
    val content: String,
    val importance: Int,
    val userConfirmed: Boolean,
    val createdAt: Long,
)

data class CompanionProfile(
    val id: String = MAIN_COMPANION_ID,
    val name: String = "林",
    val persona: String = "温和但不黏人，敏锐但不抢着分析，有自己的判断和一点干幽默",
    val speakingStyle: String = "自然的中文口语，具体、克制，不表演温柔，不连续追问",
    val boundaries: List<String> = listOf(
        "不声称自己有真实身体、现实经历或线下能力；用户误解时再澄清",
        "不把普通聊天变成心理分析，不诱导依赖，不施加情感压力",
        "遇到危机表达时停止普通角色扮演，优先处理现实安全",
        "不写露骨色情内容，亲密表达保持自然和克制",
    ),
) {
    companion object {
        const val MAIN_COMPANION_ID = "companion-linyu"
    }
}

object ConversationIds {
    const val DEFAULT = "main-conversation"
}

data class ContextTurn(
    val id: String,
    val role: String,
    val content: String,
    val sequence: Long,
)

sealed interface GenerationEvent {
    data class Started(val generationId: String, val assistantMessageId: String?) : GenerationEvent

    data class Delta(val text: String) : GenerationEvent

    data class Safety(
        val level: String,
        val action: String,
    ) : GenerationEvent

    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
    ) : GenerationEvent

    data object Completed : GenerationEvent

    data class Failed(
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : GenerationEvent
}

data class StreamSnapshot(
    val text: String = "",
    val completed: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
    val retryable: Boolean = false,
    val safetyLevel: String = "normal",
)

fun StreamSnapshot.reduce(event: GenerationEvent): StreamSnapshot = when (event) {
    is GenerationEvent.Delta -> copy(text = text + event.text)
    is GenerationEvent.Safety -> copy(safetyLevel = event.level)
    GenerationEvent.Completed -> copy(completed = true, error = null)
    is GenerationEvent.Failed -> copy(
        completed = true,
        error = event.message,
        errorCode = event.code,
        retryable = event.retryable,
    )
    is GenerationEvent.Started, is GenerationEvent.Usage -> this
}

