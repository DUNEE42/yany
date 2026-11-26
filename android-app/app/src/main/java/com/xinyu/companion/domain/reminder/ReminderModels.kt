package com.xinyu.companion.domain.reminder

data class ReminderRule(
    val id: String,
    val message: String,
    val triggerAtMillis: Long,
    val createdAtMillis: Long,
)

interface ReminderRuleStore {
    fun loadAll(): List<ReminderRule>

    fun add(rule: ReminderRule)

    fun remove(id: String): Boolean
}

interface ReminderAlarmScheduler {
    fun schedule(rule: ReminderRule)

    fun cancel(id: String)
}

interface ReminderPermissionChecker {
    fun areNotificationsEnabled(): Boolean
}

object ReminderPolicy {
    const val MAX_MESSAGE_LENGTH = 120
    const val MAX_REMINDERS = 30

    fun validate(
        message: String,
        triggerAtMillis: Long,
        nowMillis: Long,
        existingCount: Int,
    ): String? {
        val clean = message.trim()
        return when {
            clean.isBlank() -> "写一句要提醒自己的话"
            clean.length > MAX_MESSAGE_LENGTH ->
                "提醒文字不能超过 $MAX_MESSAGE_LENGTH 个字"
            triggerAtMillis <= nowMillis -> "请选择未来的时间"
            existingCount >= MAX_REMINDERS -> "最多保留 $MAX_REMINDERS 个提醒，请先删掉一些"
            else -> null
        }
    }

    fun pending(rules: List<ReminderRule>, nowMillis: Long): List<ReminderRule> =
        rules.filter { it.triggerAtMillis > nowMillis }
            .sortedBy(ReminderRule::triggerAtMillis)
}
