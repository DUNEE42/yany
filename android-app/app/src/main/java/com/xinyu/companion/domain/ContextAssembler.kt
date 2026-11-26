package com.xinyu.companion.domain

import com.xinyu.companion.domain.memory.MemoryPromptContext
import com.xinyu.companion.domain.perception.PerceptionPromptContext

class ContextAssembler(
    private val maxRecentMessages: Int = 24,
    private val maxCharacters: Int = 12_000,
    private val promptBuilder: CompanionPromptBuilder = CompanionPromptBuilder(),
) {
    fun assemble(
        companion: CompanionProfile,
        memoryContext: MemoryPromptContext = MemoryPromptContext(),
        messages: List<ChatMessage>,
        currentUserMessageId: String,
        safetyInstruction: String? = null,
        conversationStyle: ConversationStyle = ConversationStyle(),
        perceptionContext: PerceptionPromptContext? = null,
    ): List<ContextTurn> {
        val currentUserMessage = messages
            .firstOrNull { it.id == currentUserMessageId }
            ?.takeIf { it.role == MessageRole.USER }
            ?: throw ContextBuildException("找不到本轮用户消息")

        val system = promptBuilder.build(
            companion = companion,
            memoryContext = memoryContext,
            perceptionContext = perceptionContext,
            safetyInstruction = safetyInstruction,
            conversationStyle = conversationStyle,
        )

        val turns = mutableListOf(
            ContextTurn(
                id = "system",
                role = "system",
                content = system,
                sequence = 0,
            ),
        )

        var remaining = maxCharacters - system.length
        if (remaining < 0) {
            throw ContextBuildException("人格与记忆过长，请先精简设置")
        }

        val recent = messages
            .filter {
                it.id == currentUserMessageId ||
                    (it.status == MessageStatus.COMPLETED && it.role != MessageRole.SYSTEM)
            }
            .takeLast(maxRecentMessages)

        val selected = ArrayDeque<ChatMessage>()
        for (message in recent.asReversed()) {
            if (message.content.length > remaining) {
                if (message.id == currentUserMessageId) {
                    throw ContextBuildException("当前消息太长，请缩短后再发送")
                }
                break
            }
            selected.addFirst(message)
            remaining -= message.content.length
        }

        if (selected.none { it.id == currentUserMessageId }) {
            throw ContextBuildException("本轮用户消息未进入上下文")
        }
        if (currentUserMessage.content.isBlank()) {
            throw ContextBuildException("当前消息不能为空")
        }

        selected.forEach { message ->
            turns += ContextTurn(
                id = message.id,
                role = message.role.name.lowercase(),
                content = message.content,
                sequence = message.sequence,
            )
        }
        return turns
    }
}

class ContextBuildException(message: String) : IllegalArgumentException(message)
