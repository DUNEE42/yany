package com.xinyu.companion.domain

import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.domain.memory.MemoryKind
import com.xinyu.companion.domain.memory.MemoryPromptContext
import com.xinyu.companion.domain.memory.MemorySnippet
import org.junit.Test

class ContextAssemblerTest {
    @Test
    fun `includes profile and relevant memories before recent messages`() {
        val assembler = ContextAssembler()
        val context = assembler.assemble(
            companion = CompanionProfile(),
            memoryContext = MemoryPromptContext(
                profileItems = listOf("用户喜欢被叫小满"),
                relevantMemories = listOf(
                    MemorySnippet(
                        id = "m2",
                        kind = MemoryKind.PREFERENCE,
                        statement = "用户喜欢安静的咖啡馆",
                        observedAt = 1,
                    ),
                ),
            ),
            messages = listOf(
                message(1, MessageRole.USER, "今天有点累"),
                message(2, MessageRole.ASSISTANT, "那就先慢一点"),
            ),
            currentUserMessageId = "1",
        )

        assertThat(context.first().role).isEqualTo("system")
        assertThat(context.first().content).contains("用户喜欢被叫小满")
        assertThat(context.first().content).contains("用户喜欢安静的咖啡馆")
        assertThat(context.last().content).isEqualTo("那就先慢一点")
    }

    @Test
    fun `drops the oldest messages when the character budget is exhausted`() {
        val promptLength = CompanionPromptBuilder().build(
            companion = CompanionProfile(),
            memoryContext = MemoryPromptContext(),
            safetyInstruction = "",
        ).length
        val assembler = ContextAssembler(
            maxRecentMessages = 10,
            maxCharacters = promptLength + 250,
        )
        val context = assembler.assemble(
            companion = CompanionProfile(),
            memoryContext = MemoryPromptContext(),
            messages = listOf(
                message(1, MessageRole.USER, "A".repeat(300)),
                message(2, MessageRole.ASSISTANT, "B".repeat(300)),
                message(3, MessageRole.USER, "C".repeat(150)),
            ),
            currentUserMessageId = "3",
        )

        assertThat(context.size).isLessThan(4)
        assertThat(context.last().content).startsWith("C")
        assertThat(context.none { it.content.startsWith("A") }).isTrue()
    }

    @Test
    fun `excludes failed assistant text`() {
        val assembler = ContextAssembler()
        val context = assembler.assemble(
            companion = CompanionProfile(),
            memoryContext = MemoryPromptContext(
                profileItems = listOf("已确认"),
            ),
            messages = listOf(
                message(1, MessageRole.USER, "第一句"),
                message(2, MessageRole.ASSISTANT, "失败占位", MessageStatus.FAILED),
                message(3, MessageRole.USER, "当前消息"),
            ),
            currentUserMessageId = "3",
        )

        assertThat(context.first().content).contains("已确认")
        assertThat(context.none { it.content == "失败占位" }).isTrue()
        assertThat(context.last().content).isEqualTo("当前消息")
    }

    @Test(expected = ContextBuildException::class)
    fun `rejects a current user message that cannot fit the budget`() {
        val promptLength = CompanionPromptBuilder().build(
            companion = CompanionProfile(),
            memoryContext = MemoryPromptContext(),
            safetyInstruction = "",
        ).length
        val assembler = ContextAssembler(maxCharacters = promptLength + 100)
        assembler.assemble(
            companion = CompanionProfile(),
            memoryContext = MemoryPromptContext(),
            messages = listOf(
                message(1, MessageRole.USER, "X".repeat(300)),
            ),
            currentUserMessageId = "1",
        )
    }

    private fun message(
        id: Long,
        role: MessageRole,
        content: String,
        status: MessageStatus = MessageStatus.COMPLETED,
    ) = ChatMessage(
        id = id.toString(),
        conversationId = "test",
        role = role,
        sequence = id,
        content = content,
        status = status,
        createdAt = id,
    )

}

