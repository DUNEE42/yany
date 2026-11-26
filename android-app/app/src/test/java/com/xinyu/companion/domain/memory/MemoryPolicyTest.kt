package com.xinyu.companion.domain.memory

import com.google.common.truth.Truth.assertThat
import com.xinyu.companion.domain.MessageRole
import java.time.Duration
import org.junit.Test

class MemoryPolicyTest {
    @Test
    fun `stable preferences and boundaries may activate automatically`() {
        val messages = messages(
            user = "我不喜欢你抢话，以后先等我说完；我也更喜欢先听结论。",
            assistant = "好，我会注意。",
        )
        val preference = candidate(
            kind = MemoryKind.PREFERENCE,
            statement = "用户偏好先听结论再听解释。",
            objectText = "先听结论",
        )
        val boundary = candidate(
            kind = MemoryKind.BOUNDARY,
            statement = "用户不喜欢在话没说完时被打断。",
            objectText = "不要抢话",
        )

        assertThat(
            MemoryPolicy.canAutoActivate(
                MemoryPolicy.prepare(messages, preference)!!,
                messages,
            ),
        )
            .isTrue()
        assertThat(
            MemoryPolicy.canAutoActivate(
                MemoryPolicy.prepare(messages, boundary)!!,
                messages,
            ),
        )
            .isTrue()
    }

    @Test
    fun `AI self descriptions and capabilities never become user memory`() {
        val messages = messages(
            user = "你能看到我的地址和天气吗？",
            assistant = "我是 AI，没有真实身体，也看不到你的地址，无法查询实时天气。",
        )

        val identity = MemoryPolicy.prepare(
            messages,
            candidate(
                statement = "AI 说明自己是 AI，没有真实身体，也替代不了真人陪伴。",
                objectText = "没有真实身体",
            ),
        )
        val capability = MemoryPolicy.prepare(
            messages,
            candidate(
                statement = "AI 表示自己看不到用户地址，也没有实时天气数据。",
                objectText = "看不到地址",
            ),
        )

        assertThat(identity).isNull()
        assertThat(capability).isNull()
    }

    @Test
    fun `short companion name is filtered without swallowing ordinary words`() {
        assertThat(MemoryPolicy.isLowValueStatement("林说自己是 AI，无法看到真实地址。")).isTrue()
        assertThat(MemoryPolicy.isLowValueStatement("林子里的风很大，树叶一直在响。")).isFalse()
    }

    @Test
    fun `one-off questions and memory tests are discarded`() {
        val messages = messages(
            user = "滴水之交是什么意思？顺便测试你记不记得我最开始说了什么。",
            assistant = "它指短暂交往。",
        )

        val knowledge = MemoryPolicy.prepare(
            messages,
            candidate(
                kind = MemoryKind.EVENT,
                statement = "用户询问“滴水之交”的含义和背后故事，并表示只是好奇。",
                objectText = "滴水之交",
            ),
        )
        val debug = MemoryPolicy.prepare(
            messages,
            candidate(
                kind = MemoryKind.EVENT,
                statement = "用户测试 AI 的记忆，询问自己最开始说了什么。",
                objectText = "测试记忆",
            ),
        )

        assertThat(knowledge).isNull()
        assertThat(debug).isNull()
    }

    @Test
    fun `same-day mood is a bounded recent event rather than permanent state`() {
        val messages = messages(
            user = "我今天不太想说话。",
            assistant = "那就先安静待会儿。",
        )
        val prepared = MemoryPolicy.prepare(
            messages,
            candidate(
                kind = MemoryKind.EVENT,
                statement = "用户当天表示不想说话。",
                objectText = "当天不想说话",
            ),
        )!!

        assertThat(MemoryPolicy.canAutoActivate(prepared, messages)).isFalse()
        assertThat(prepared.validTo).isEqualTo(
            prepared.observedAt + Duration.ofDays(14).toMillis(),
        )
    }

    @Test
    fun `location facts are marked sensitive and wait for confirmation`() {
        val messages = messages(
            user = "我现在在苏州。",
            assistant = "知道了。",
        )
        val prepared = MemoryPolicy.prepare(
            messages,
            candidate(
                kind = MemoryKind.FACT,
                predicate = "lives_in",
                statement = "用户目前所在城市是苏州。",
                objectText = "苏州",
            ),
        )!!

        assertThat(prepared.sensitivity).isEqualTo(MemorySensitivity.SENSITIVE)
        assertThat(MemoryPolicy.canAutoActivate(prepared, messages)).isFalse()
    }

    @Test
    fun `common city phrasing is sensitive even when the model labels it normal`() {
        val locationMessages = messages(
            user = "我来自苏州。",
            assistant = "知道了。",
        )
        val prepared = MemoryPolicy.prepare(
            locationMessages,
            candidate(
                kind = MemoryKind.FACT,
                predicate = "located_in",
                statement = "用户提到自己在苏州。",
                objectText = "苏州",
            ),
        )!!

        assertThat(prepared.sensitivity).isEqualTo(MemorySensitivity.SENSITIVE)
        assertThat(MemoryPolicy.canAutoActivate(prepared, locationMessages)).isFalse()
    }

    @Test
    fun `unrelated preference words do not endorse another candidate`() {
        val broadMessages = messages(
            user = "别人都说我奇怪，我的问题是喜欢安静。",
            assistant = "嗯。",
        )
        val inferredFact = MemoryPolicy.prepare(
            broadMessages,
            candidate(
                kind = MemoryKind.FACT,
                statement = "用户是医生。",
                objectText = "医生",
            ),
        )!!

        assertThat(MemoryPolicy.canAutoActivate(inferredFact, broadMessages)).isFalse()
    }

    @Test
    fun `expired and legacy junk never enter the prompt`() {
        val now = 2_000_000L
        val expired = memory(
            statement = "用户计划周五去杭州面试。",
            kind = MemoryKind.PLAN,
            validTo = now - 1,
        )
        val legacyJunk = memory(
            statement = "AI 表示自己看不到用户地址，也没有实时天气数据。",
            kind = MemoryKind.FACT,
        )

        assertThat(MemoryPolicy.isEligibleForPrompt(expired, now)).isFalse()
        assertThat(MemoryPolicy.isEligibleForPrompt(legacyJunk, now)).isFalse()
    }

    @Test
    fun `summary sanitizer removes AI self talk and low-value questions`() {
        val summary = """
            【近期经历】
            - 用户提到最近找工作受挫。
            - 用户询问“滴水之交”是什么意思。
            【当前状态】
            - AI 表示自己没有身体，也看不到用户地址。
            - 用户当天不太想说话。
        """.trimIndent()

        val sanitized = MemoryPolicy.sanitizeSummary(summary)

        assertThat(sanitized).contains("找工作受挫")
        assertThat(sanitized).contains("当天不太想说话")
        assertThat(sanitized).doesNotContain("滴水之交")
        assertThat(sanitized).doesNotContain("AI 表示")
        assertThat(sanitized).doesNotContain("【未完成话题】")
    }

    private fun messages(user: String, assistant: String): List<MemorySourceMessage> = listOf(
        MemorySourceMessage(
            id = "user",
            role = MessageRole.USER,
            sequence = 1,
            content = user,
            createdAt = 1_000,
        ),
        MemorySourceMessage(
            id = "assistant",
            role = MessageRole.ASSISTANT,
            sequence = 2,
            content = assistant,
            createdAt = 2_000,
        ),
    )

    private fun candidate(
        kind: MemoryKind = MemoryKind.FACT,
        predicate: String = "states",
        objectText: String,
        statement: String,
    ) = MemoryCandidate(
        kind = kind,
        scope = MemoryScope.USER,
        subject = "user",
        predicate = predicate,
        objectText = objectText,
        statement = statement,
        sourceMessageIds = listOf("user", "assistant"),
        observedAt = 1_000,
        validFrom = 1_000,
        validTo = null,
        confidence = 0.95,
        sourceKind = MemorySourceKind.USER_EXPLICIT,
        sensitivity = MemorySensitivity.NORMAL,
        importance = 4,
    )

    private fun memory(
        statement: String,
        kind: MemoryKind,
        validTo: Long? = null,
    ) = LongTermMemory(
        id = statement,
        companionId = "companion",
        kind = kind,
        scope = MemoryScope.USER,
        subject = "user",
        predicate = "states",
        objectText = statement,
        statement = statement,
        normalizedKey = statement,
        sourceKind = MemorySourceKind.USER_EXPLICIT,
        confirmationState = MemoryConfirmationState.CONFIRMED,
        status = MemoryStatus.ACTIVE,
        confidence = 0.95,
        importance = 4,
        sensitivity = MemorySensitivity.NORMAL,
        observedAt = 1_000,
        validFrom = 1_000,
        validTo = validTo,
        supersededById = null,
        createdAt = 1_000,
        updatedAt = 1_000,
        lastAccessedAt = null,
    )
}
