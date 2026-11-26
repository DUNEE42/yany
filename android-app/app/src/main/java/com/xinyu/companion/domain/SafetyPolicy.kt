package com.xinyu.companion.domain

data class SafetyAssessment(
    val level: String,
    val action: String,
    val instruction: String,
)

class SafetyPolicy {
    fun assess(text: String): SafetyAssessment {
        val normalized = normalize(text)
        return when {
            CRISIS_INTENT_MARKERS.any { containsUnnegated(normalized, it) } -> {
                SafetyAssessment(
                    level = "crisis",
                    action = "show_support_resources",
                    instruction = "暂停普通伴侣角色化表达，直接关心用户当前是否安全，明确建议联系可信任的人和当地紧急服务。不要诊断、说教或继续轻佻互动。",
                )
            }
            DISTRESS_MARKERS.any { normalized.contains(normalize(it)) } -> {
                SafetyAssessment(
                    level = "distress",
                    action = "support_first",
                    instruction = "用户正在经历强烈痛苦。先具体回应感受，不继续轻佻角色扮演，也不急着给成套建议；在不制造压力的前提下确认对方当前是否安全。",
                )
            }
            else -> {
                SafetyAssessment(
                    level = "normal",
                    action = "none",
                    instruction = "",
                )
            }
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)

    private fun containsUnnegated(
        normalizedText: String,
        marker: String,
    ): Boolean {
        val normalizedMarker = normalize(marker)
        var index = normalizedText.indexOf(normalizedMarker)
        while (index >= 0) {
            val prefix = normalizedText.substring(
                startIndex = (index - MAX_NEGATION_PREFIX_LENGTH).coerceAtLeast(0),
                endIndex = index,
            )
            if (NEGATION_SUFFIXES.none(prefix::endsWith)) return true
            index = normalizedText.indexOf(normalizedMarker, startIndex = index + 1)
        }
        return false
    }

    private companion object {
        const val MAX_NEGATION_PREFIX_LENGTH = 8

        val NEGATION_SUFFIXES = listOf(
            "不",
            "没",
            "没有",
            "别",
            "不会",
            "不想",
            "不要",
            "not",
            "dont",
            "never",
            "wont",
        )

        val CRISIS_INTENT_MARKERS = listOf(
            "想自杀",
            "要自杀",
            "想死",
            "想去死",
            "不想活",
            "不想活下去",
            "活不下去",
            "结束自己的生命",
            "结束生命",
            "伤害自己",
            "割腕",
            "跳楼",
            "吞药",
            "轻生",
            "了结自己",
            "离开这个世界",
            "wanttodie",
            "wanttokillmyself",
            "goingtokillmyself",
            "endmylife",
            "suicidal",
        )

        val DISTRESS_MARKERS = listOf(
            "撑不住",
            "撑不下去",
            "快撑不住",
            "活着没意思",
            "想消失",
            "不想存在",
            "很绝望",
            "好绝望",
            "顶不住了",
            "彻底崩溃",
            "很孤独",
            "i want to disappear",
            "i cant go on",
        )
    }
}
