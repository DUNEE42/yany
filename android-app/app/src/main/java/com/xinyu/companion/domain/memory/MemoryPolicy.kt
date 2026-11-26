package com.xinyu.companion.domain.memory

import com.xinyu.companion.domain.MessageRole
import java.time.Duration

/**
 * Deterministic write and read policy for the local memory system.
 *
 * The model may propose facts, but it never decides whether a statement is durable,
 * sensitive, or useful enough to enter every future conversation. Those decisions
 * stay in the app so prompt drift cannot silently pollute the user's profile.
 */
object MemoryPolicy {
    private val automatedSourceKinds = setOf(
        MemorySourceKind.USER_EXPLICIT,
        MemorySourceKind.USER_FEEDBACK,
    )

    private val lowValueStatementPatterns = listOf(
        Regex(
            """^(?:AI|人工智能|助手|模型|伴侣)\s*(?:在|表示|说明|认为|看不到|没有|无法|不能|会|应当|应该|回应|说|自称|是|也)""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """^林\s*(?:在|表示|说明|认为|看不到|没有|无法|不能|会|应当|应该|回应|说|自称|是|也)""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """(?:AI|人工智能|助手|模型|伴侣|林屿|林).{0,10}(?:自称|看不到.{0,8}地址|没有真实|无法获取实时|替代不了真人|无法代替真人)""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """^(?:用户|我)?\s*(?:询问|问|测试|好奇|想了解|想知道)""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """(?:测试.{0,8}记忆|记不记得|还记不记得|最开始说了什么|有没有说过|考考你|验证.{0,4}记忆)""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """(?:没有真实身体|没有真实生活|看不到.{0,8}地址|没有实时.{0,4}数据|无法获取实时|无法查询实时|替代不了真人|无法代替真人)""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """^AI\s*在用户.{0,16}(?:不催促|陪伴|回应|表示)""",
            RegexOption.IGNORE_CASE,
        ),
    )

    private val sensitiveCue = Regex(
        """(?:住在|居住|所在(?:城市|地区)|家庭住址|公司地址|地址|定位|精确位置|身份证|护照|手机号|电话|真实姓名|生日|出生日期|工资|收入|存款|财产|银行卡|病史|诊断|药物|医院|怀孕|抑郁症|焦虑症|双相|创伤|性取向|(?:我|用户|自己)(?:现在)?在(?!想|做|听|看|学|工作|考虑|准备|等待|说|问|聊|玩|用|心里|话)[\p{IsHan}]{2,8}|(?:我|用户|自己)?(?:来自|搬到|住在|居住在|人在)(?!想|做|听|看|学|工作|考虑|准备|等待|说|问|聊|玩|用|心里|话)[\p{IsHan}]{2,8})""",
        RegexOption.IGNORE_CASE,
    )

    private val sensitivePredicateCue = Regex(
        """(?:lives?_in|resid|locat|city|address|phone|salary|income|finance|health|medical)""",
        RegexOption.IGNORE_CASE,
    )

    private val transientCue = Regex(
        """(?:今天|当天|今晚|最近|这两天|这几天|目前|现在|正在|暂时|这段时间|一时|有点|心情|情绪|累|不想说话|找不到工作|受挫)""",
        RegexOption.IGNORE_CASE,
    )

    fun prepare(
        messages: List<MemorySourceMessage>,
        candidate: MemoryCandidate,
    ): MemoryCandidate? {
        val statement = candidate.statement.trim().replace(Regex("""\s+"""), " ")
        val subject = candidate.subject.trim()
        val predicate = candidate.predicate.trim()
        val objectText = candidate.objectText.trim()
        if (statement.isBlank() || subject.isBlank() || predicate.isBlank()) return null
        if (candidate.scope != MemoryScope.USER) return null
        if (candidate.kind == MemoryKind.SUMMARY) return null
        if (candidate.sourceKind == MemorySourceKind.COMPANION_EXPLICIT) return null
        if (isLowValueStatement(statement)) return null

        val messagesById = messages.associateBy { it.id }
        val sourceMessages = candidate.sourceMessageIds
            .distinct()
            .mapNotNull(messagesById::get)
        if (sourceMessages.isEmpty()) return null
        if (sourceMessages.none { it.role == MessageRole.USER }) return null

        val observedAt = sourceMessages.maxOfOrNull { it.createdAt } ?: candidate.observedAt
        val validFrom = candidate.validFrom ?: observedAt
        val proposedValidTo = boundedValidTo(
            kind = candidate.kind,
            observedAt = observedAt,
            proposed = candidate.validTo ?: defaultValidTo(candidate.kind, observedAt),
        )
        if (proposedValidTo != null && proposedValidTo < validFrom) return null

        val sensitivity = if (
            candidate.sensitivity == MemorySensitivity.SENSITIVE ||
            sensitiveCue.containsMatchIn(statement) ||
            sensitivePredicateCue.containsMatchIn(predicate)
        ) {
            MemorySensitivity.SENSITIVE
        } else {
            MemorySensitivity.NORMAL
        }

        return candidate.copy(
            subject = subject,
            predicate = predicate,
            objectText = objectText.ifBlank { statement },
            statement = statement,
            sourceMessageIds = sourceMessages.map { it.id },
            observedAt = observedAt.coerceAtLeast(0),
            validFrom = validFrom.coerceAtLeast(0),
            validTo = proposedValidTo?.coerceAtLeast(0),
            confidence = candidate.confidence.coerceIn(0.0, 1.0),
            importance = candidate.importance.coerceIn(1, 5),
            sensitivity = sensitivity,
        )
    }

    fun canAutoActivate(
        candidate: MemoryCandidate,
        messages: List<MemorySourceMessage>,
    ): Boolean {
        if (candidate.sourceKind !in automatedSourceKinds) return false
        if (candidate.scope != MemoryScope.USER) return false
        if (candidate.sensitivity != MemorySensitivity.NORMAL) return false
        if (candidate.confidence < MIN_AUTO_ACTIVE_CONFIDENCE) return false
        if (isLowValueStatement(candidate.statement)) return false
        val userText = messages
            .asSequence()
            .filter { it.role == MessageRole.USER }
            .joinToString("\n") { it.content }
        if (userText.isBlank()) return false
        if (!hasExplicitEvidence(candidate, userText)) return false

        return when (candidate.kind) {
            MemoryKind.BOUNDARY,
            MemoryKind.PREFERENCE,
            MemoryKind.RELATIONSHIP_PREFERENCE,
            -> true

            // A stable personal fact remains useful; a temporary state or setback
            // belongs to the short-term timeline until the user confirms it.
            MemoryKind.FACT -> !transientCue.containsMatchIn(candidate.statement)

            // A concrete plan can guide the next conversations, but it gets a
            // bounded lifetime rather than becoming a permanent personality fact.
            MemoryKind.PLAN -> candidate.confidence >= MIN_AUTO_ACTIVE_PLAN_CONFIDENCE &&
                candidate.validTo != null &&
                candidate.validTo > candidate.observedAt

            MemoryKind.EVENT,
            MemoryKind.SUMMARY,
            -> false
        }
    }

    fun isEligibleForPrompt(
        memory: LongTermMemory,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (memory.status != MemoryStatus.ACTIVE) return false
        if (memory.scope != MemoryScope.USER) return false
        if (memory.validTo != null && memory.validTo <= now) return false
        if (memory.kind == MemoryKind.EVENT && memory.validTo == null) return false
        if (isLowValueStatement(memory.statement)) return false
        if (memory.sensitivity == MemorySensitivity.SENSITIVE &&
            memory.confirmationState != MemoryConfirmationState.CONFIRMED
        ) {
            return false
        }
        if (memory.sourceKind == MemorySourceKind.COMPANION_EXPLICIT) return false
        if (memory.sourceKind != MemorySourceKind.MANUAL &&
            memory.confidence < MIN_PROMPT_CONFIDENCE
        ) {
            return false
        }
        return when (memory.sourceKind) {
            MemorySourceKind.USER_EXPLICIT,
            MemorySourceKind.USER_FEEDBACK,
            MemorySourceKind.MANUAL,
            -> true

            MemorySourceKind.INFERRED,
            MemorySourceKind.COMPANION_EXPLICIT,
            -> memory.confirmationState == MemoryConfirmationState.CONFIRMED
        }
    }

    fun isLowValueStatement(statement: String): Boolean =
        lowValueStatementPatterns.any { it.containsMatchIn(statement.trim()) }

    /**
     * Keeps old installations from injecting known junk while preserving the raw
     * transcript. A batch with no durable summary is represented as an empty string.
     */
    fun sanitizeSummary(raw: String, maxChars: Int = MAX_SUMMARY_CHARS): String {
        val ignoredBodies = setOf("无", "无。", "暂无", "暂无。", "没有", "没有。")
        val lines = raw.lineSequence()
            .map(String::trimEnd)
            .filterNot { line ->
                val body = line.trim().removePrefix("-").trim()
                body in ignoredBodies || (body.isNotBlank() && isLowValueStatement(body))
            }
            .dropWhile(String::isBlank)
            .toList()
        val kept = lines.filterIndexed { index, line ->
            val isHeading = line.trim().startsWith("【") && line.trim().endsWith("】")
            if (!isHeading) {
                line.isNotBlank()
            } else {
                val next = lines.getOrNull(index + 1)?.trim().orEmpty()
                next.startsWith("-")
            }
        }
        return kept.joinToString("\n")
            .trim()
            .take(maxChars)
    }

    private fun defaultValidTo(kind: MemoryKind, observedAt: Long): Long? = when (kind) {
        MemoryKind.EVENT -> observedAt + Duration.ofDays(EVENT_RETENTION_DAYS).toMillis()
        MemoryKind.PLAN -> observedAt + Duration.ofDays(PLAN_RETENTION_DAYS).toMillis()
        else -> null
    }

    private fun boundedValidTo(
        kind: MemoryKind,
        observedAt: Long,
        proposed: Long?,
    ): Long? {
        if (proposed == null) return null
        return when (kind) {
            MemoryKind.EVENT -> proposed.coerceIn(
                observedAt + Duration.ofDays(1).toMillis(),
                observedAt + Duration.ofDays(MAX_EVENT_RETENTION_DAYS).toMillis(),
            )

            MemoryKind.PLAN -> proposed.coerceIn(
                observedAt,
                observedAt + Duration.ofDays(MAX_PLAN_RETENTION_DAYS).toMillis(),
            )

            else -> proposed
        }
    }

    private fun hasExplicitEvidence(
        candidate: MemoryCandidate,
        userText: String,
    ): Boolean {
        val statementLooksExplicit = when (candidate.kind) {
            MemoryKind.BOUNDARY,
            MemoryKind.PREFERENCE,
            MemoryKind.RELATIONSHIP_PREFERENCE,
            -> Regex(
                """(?:喜欢|不喜欢|希望|更想|偏好|讨厌|受不了|别(?!人)|不要|不用|先说|先给|先听|直接说|叫我|喊我|称呼我)""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(userText)

            MemoryKind.FACT -> Regex(
                """(?:我是|我在|我住|我来自|我会|我通常|我总是|我习惯|我一直|我长期|我已经|我养|我做|我学|我工作|我的(?:职业|工作|习惯|家乡|名字|生日|长期目标|兴趣)是)""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(userText)

            MemoryKind.PLAN -> Regex(
                """(?:我(?:计划|打算|准备|要|会|约|报名|安排)|明天|后天|下周|周[一二三四五六日天])""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(userText)

            MemoryKind.EVENT,
            MemoryKind.SUMMARY,
            -> false
        }
        if (!statementLooksExplicit) return false
        return hasLexicalSupport(candidate, userText)
    }

    private fun hasLexicalSupport(
        candidate: MemoryCandidate,
        userText: String,
    ): Boolean {
        val objectText = candidate.objectText.trim()
        if (objectText.isNotEmpty() && userText.contains(objectText, ignoreCase = true)) {
            return true
        }
        return MemoryQueryTokenizer.terms(candidate.statement + " " + objectText)
            .asSequence()
            .filter { it.length >= 2 }
            .any { userText.contains(it, ignoreCase = true) }
    }

    private const val MIN_AUTO_ACTIVE_CONFIDENCE = 0.78
    private const val MIN_AUTO_ACTIVE_PLAN_CONFIDENCE = 0.85
    private const val MIN_PROMPT_CONFIDENCE = 0.55
    private const val MAX_SUMMARY_CHARS = 800
    private const val EVENT_RETENTION_DAYS = 14L
    private const val PLAN_RETENTION_DAYS = 30L
    private const val MAX_EVENT_RETENTION_DAYS = 30L
    private const val MAX_PLAN_RETENTION_DAYS = 30L
}
