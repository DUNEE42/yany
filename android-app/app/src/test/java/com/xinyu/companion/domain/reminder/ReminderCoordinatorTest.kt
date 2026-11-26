package com.xinyu.companion.domain.reminder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReminderCoordinatorTest {
    @Test
    fun `creates future reminder and schedules it`() {
        val store = FakeStore()
        val scheduler = FakeScheduler()
        val coordinator = ReminderCoordinator(store, scheduler)

        val result = coordinator.create(
            message = "  去驿站取快递  ",
            triggerAtMillis = 2_000,
            nowMillis = 1_000,
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(store.rules.single().message).isEqualTo("去驿站取快递")
        assertThat(scheduler.scheduled.single()).isEqualTo(store.rules.single())
    }

    @Test
    fun `rejects blank and past reminders`() {
        val coordinator = ReminderCoordinator(FakeStore(), FakeScheduler())

        val blank = coordinator.create(" ", 2_000, 1_000)
        val past = coordinator.create("取快递", 1_000, 1_000)

        assertThat(blank.exceptionOrNull()).hasMessageThat().contains("写一句")
        assertThat(past.exceptionOrNull()).hasMessageThat().contains("未来")
    }

    @Test
    fun `scheduler failure rolls back the saved rule`() {
        val store = FakeStore()
        val coordinator = ReminderCoordinator(
            store,
            FakeScheduler(failure = IllegalStateException("alarm unavailable")),
        )

        val result = coordinator.create("取快递", 2_000, 1_000)

        assertThat(result.isFailure).isTrue()
        assertThat(store.rules).isEmpty()
    }

    @Test
    fun `cancel removes stored and scheduled rule`() {
        val store = FakeStore()
        val scheduler = FakeScheduler()
        val coordinator = ReminderCoordinator(store, scheduler)
        val rule = coordinator.create("取快递", 2_000, 1_000).getOrThrow()

        assertThat(coordinator.cancel(rule.id)).isTrue()
        assertThat(store.rules).isEmpty()
        assertThat(scheduler.cancelled).containsExactly(rule.id)
    }
}

private class FakeStore(
    var rules: MutableList<ReminderRule> = mutableListOf(),
) : ReminderRuleStore {
    override fun loadAll(): List<ReminderRule> = rules.toList()

    override fun add(rule: ReminderRule) {
        rules += rule
    }

    override fun remove(id: String): Boolean =
        rules.removeAll { it.id == id }
}

private class FakeScheduler(
    private val failure: Throwable? = null,
) : ReminderAlarmScheduler {
    val scheduled = mutableListOf<ReminderRule>()
    val cancelled = mutableListOf<String>()

    override fun schedule(rule: ReminderRule) {
        failure?.let { throw it }
        scheduled += rule
    }

    override fun cancel(id: String) {
        cancelled += id
    }
}
