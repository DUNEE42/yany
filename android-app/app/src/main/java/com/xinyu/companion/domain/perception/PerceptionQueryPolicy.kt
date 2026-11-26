package com.xinyu.companion.domain.perception

object PerceptionQueryPolicy {
    private val weatherTerms = Regex(
        """(?:天气|气温|下雨|降雨|下雪|冷不冷|热不热|带伞|穿什么)""",
        RegexOption.IGNORE_CASE,
    )
    private val notificationTerms = Regex(
        """(?:快递|包裹|取件|驿站|丰巢|通知|下课|吃饭|午饭|晚饭|饿了|提醒|日程|安排)""",
        RegexOption.IGNORE_CASE,
    )
    private val foregroundTerms = Regex(
        """(?:我在干嘛|我在做什么|当前应用|什么应用|看到我在|正在用)""",
        RegexOption.IGNORE_CASE,
    )
    private val searchTerms = Regex(
        """(?:查一下|搜一下|搜索|联网|最新|新闻|价格|汇率|股价|比赛|实时|现在几点|发生了)""",
        RegexOption.IGNORE_CASE,
    )
    private val hardSecretSearchTerms = Regex(
        """(?:\d{6,}|身份证|银行卡|密码|验证码|手机号|家庭住址)""",
        RegexOption.IGNORE_CASE,
    )
    private val personalNarrative = Regex(
        """(?:我|我们|他|她|朋友|同学|同事|老师|妈妈|爸爸|对象|男友|女友|室友).{0,40}(?:吵架|关系|工资|收入|生病|医院|诊断|住|学校|公司|难过|焦虑|喜欢|讨厌|分手|离职|面试)""",
        RegexOption.IGNORE_CASE,
    )
    private val explicitSearchCommand = Regex(
        """(?:帮我|请)?(?:联网)?(?:查一下|搜一下|搜索|联网查)(?:关于|一下)?\s*(.+)""",
        RegexOption.IGNORE_CASE,
    )

    fun wantsWeather(query: String): Boolean = weatherTerms.containsMatchIn(query)

    fun wantsNotifications(query: String): Boolean =
        notificationTerms.containsMatchIn(query)

    fun wantsForegroundApp(query: String): Boolean =
        foregroundTerms.containsMatchIn(query)

    fun wantsWebSearch(query: String): Boolean {
        return hasSearchIntent(query) && searchQuery(query) != null
    }

    fun hasSearchIntent(query: String): Boolean = searchTerms.containsMatchIn(query)

    /**
     * Returns the query that should be shown to the user before any external search.
     *
     * Unlike [searchQuery], this deliberately keeps likely names, schools and other
     * proper nouns so the user can review them. Hard secrets are always rejected.
     */
    fun suggestedSearchQuery(query: String): String? {
        val clean = query.trim()
        if (clean.isBlank() || clean.length > MAX_SEARCH_QUERY_CHARS) return null
        if (hardSecretSearchTerms.containsMatchIn(clean)) return null
        val explicit = explicitSearchCommand.find(clean)?.groupValues?.getOrNull(1)?.trim()
        val candidate = explicit?.takeIf(String::isNotBlank) ?: clean
        return candidate.takeIf {
            it.isNotBlank() &&
                it.length <= MAX_QUERY_CHARS &&
                !hardSecretSearchTerms.containsMatchIn(it)
        }
    }

    fun hasConfirmableSearchIntent(query: String): Boolean =
        hasSearchIntent(query) && suggestedSearchQuery(query) != null

    /**
     * Validates a query that the user explicitly edited and approved for this turn.
     * Hard secrets remain blocked even after confirmation.
     */
    fun normalizeConfirmedSearchQuery(query: String): String? {
        val clean = query.trim()
        return clean.takeIf {
            it.isNotBlank() &&
                it.length <= MAX_QUERY_CHARS &&
                !hardSecretSearchTerms.containsMatchIn(it)
        }
    }

    fun searchQuery(query: String): String? {
        val candidate = suggestedSearchQuery(query) ?: return null
        if (personalNarrative.containsMatchIn(candidate)) return null
        return candidate
    }

    private const val MAX_SEARCH_QUERY_CHARS = 300
    private const val MAX_QUERY_CHARS = 120
}
