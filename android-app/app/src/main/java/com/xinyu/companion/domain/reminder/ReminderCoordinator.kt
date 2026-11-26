package com.xinyu.companion.domain.reminder

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderCoordinator @Inject constructor(
    private val store: ReminderRuleStore,
    private val scheduler: ReminderAlarmScheduler,
) {
    fun loadAll(): List<ReminderRule> = store.loadAll()

    fun create(
        message: String,
        triggerAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Result<ReminderRule> = runCatching {
        val existing = store.loadAll()
        ReminderPolicy.validate(
            message = message,
            triggerAtMillis = triggerAtMillis,
            nowMillis = nowMillis,
            existingCount = existing.size,
        )?.let { throw IllegalArgumentException(it) }

        val rule = ReminderRule(
            id = UUID.randomUUID().toString(),
            message = message.trim(),
            triggerAtMillis = triggerAtMillis,
            createdAtMillis = nowMillis,
        )
        store.add(rule)
        runCatching { scheduler.schedule(rule) }
            .onFailure {
                store.remove(rule.id)
                throw it
            }
        rule
    }

    fun cancel(id: String): Boolean {
        scheduler.cancel(id)
        return store.remove(id)
    }

    fun reschedulePending(nowMillis: Long = System.currentTimeMillis()) {
        store.loadAll()
            .filter { it.triggerAtMillis > nowMillis }
            .forEach(scheduler::schedule)
    }
}
