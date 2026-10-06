package app.tasker.core.notifications

import app.tasker.core.domain.notify.NotificationType
import app.tasker.core.model.AppSettings
import app.tasker.core.model.Deadline
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationGateTest {
    private val env = GateEnv()
    private val gate = env.gate
    private val poster = env.poster

    @After
    fun tearDown() = env.close()

    @Test
    fun `the list of types is closed - there is no "you did not do it" notification (NTF-6)`() {
        assertThat(NotificationType.entries.map { it.name })
            .containsExactly("PLAN_READY", "DEADLINE", "WEEKLY_REVIEW", "INTEGRATION", "QUIET_HOURS_DIGEST")
        NotificationType.entries.forEach { assertThat(NotificationChannels.forType(it)).isNotEmpty() }
    }

    @Test
    fun `at most three notifications a day, deadlines not counted (NTF-3)`() = runTest {
        assertThat(gate.submit(plan("plan|1"))).isEqualTo(GateResult.Sent)
        assertThat(gate.submit(review("review|1"))).isEqualTo(GateResult.Sent)
        assertThat(gate.submit(review("review|2"))).isEqualTo(GateResult.Sent)
        assertThat(gate.submit(review("review|3"))).isEqualTo(GateResult.OverBudget)
        assertThat(gate.submit(deadline("a"))).isEqualTo(GateResult.Sent)
        assertThat(gate.submit(deadline("b"))).isEqualTo(GateResult.Sent)
        assertThat(poster.shown.map { it.key }).containsExactly("plan|1", "review|1", "review|2", "deadline|a", "deadline|b").inOrder()
        assertThat(env.ledger.budgetUsed()).isEqualTo(3)

        // The budget belongs to the logical day: the next day starts from zero.
        env.setNow("2026-10-07T10:00")
        assertThat(gate.submit(review("review|3"))).isEqualTo(GateResult.Sent)
    }

    @Test
    fun `the limit is a setting`() = runTest {
        env.store.updateData { it.copy(dailyNotificationLimit = 1) }
        assertThat(gate.submit(plan("plan|1"))).isEqualTo(GateResult.Sent)
        assertThat(gate.submit(review("review|1"))).isEqualTo(GateResult.OverBudget)
    }

    @Test
    fun `a key is never sent twice`() = runTest {
        assertThat(gate.submit(deadline("a"))).isEqualTo(GateResult.Sent)
        assertThat(gate.submit(deadline("a"))).isEqualTo(GateResult.Duplicate)
        assertThat(poster.shown).hasSize(1)
    }

    @Test
    fun `switched off types, blocked channels and missing permission send nothing`() = runTest {
        env.store.updateData { it.copy(notifyDeadlines = false) }
        assertThat(gate.submit(deadline("a"))).isEqualTo(GateResult.Disabled)
        assertThat(
            gate.submit(NotificationRequest(NotificationType.INTEGRATION, "github|1", "PR merged", "…")),
        ).isEqualTo(GateResult.Disabled)

        poster.blocked += NotificationType.PLAN_READY
        assertThat(gate.submit(plan("plan|1"))).isEqualTo(GateResult.Disabled)

        poster.permitted = false
        assertThat(gate.submit(review("review|1"))).isEqualTo(GateResult.NotPermitted)
        assertThat(poster.shown).isEmpty()
        assertThat(env.ledger.budgetUsed()).isEqualTo(0)
    }

    @Test
    fun `quiet hours queue notifications and deliver them as one group (NTF-4)`() = runTest {
        val first = env.addTask("Отчёт")
        val second = env.addTask("Билеты")
        env.setNow("2026-10-06T23:00")

        val result = gate.submit(deadline(first.id))
        assertThat(result).isEqualTo(GateResult.Deferred(env.at("2026-10-07T08:00")))
        assertThat(gate.submit(deadline(second.id))).isEqualTo(GateResult.Deferred(env.at("2026-10-07T08:00")))
        assertThat(poster.shown).isEmpty()
        assertThat(env.alarm.scheduledAt).isEqualTo(env.at("2026-10-07T08:00"))

        env.setNow("2026-10-07T08:05")
        val flush = gate.flushQuietHoursQueue()

        assertThat(flush).isEqualTo(FlushResult(delivered = 2, dropped = 0, asGroup = true))
        val group = poster.groups.single()
        assertThat(group.map { it.taskId }).containsExactly(first.id, second.id).inOrder()
        assertThat(group.map { it.type }).containsExactly(NotificationType.DEADLINE, NotificationType.DEADLINE)
        // The group counts as one notification; its children are never sent again.
        assertThat(env.ledger.budgetUsed()).isEqualTo(1)
        assertThat(gate.submit(deadline(first.id))).isEqualTo(GateResult.Duplicate)
        assertThat(env.ledger.due(env.at("2026-10-08T00:00"))).isEmpty()
        assertThat(env.alarm.scheduledAt).isNull()
    }

    @Test
    fun `the morning plan comes at its time even inside quiet hours (interpretation 17)`() = runTest {
        env.setNow("2026-10-07T07:30")
        assertThat(gate.submit(plan("plan|2026-10-07"))).isEqualTo(GateResult.Sent)
        assertThat(gate.submit(review("review|1"))).isEqualTo(GateResult.Deferred(env.at("2026-10-07T08:00")))
    }

    @Test
    fun `a reminder that would wait past its deadline goes at once (interpretation 17)`() = runTest {
        val task = env.addTask()
        env.setNow("2026-10-06T22:05")

        assertThat(gate.submit(deadline(task.id, deliverBy = env.at("2026-10-07T07:00")))).isEqualTo(GateResult.Sent)
        assertThat(gate.submit(deadline(task.id, key = "later", deliverBy = env.at("2026-10-07T12:00"))))
            .isEqualTo(GateResult.Deferred(env.at("2026-10-07T08:00")))
    }

    @Test
    fun `a single queued notification comes as itself, only the latest reminder per task, finished tasks dropped`() = runTest {
        val report = env.addTask("Отчёт")
        val done = env.addTask("Сделано")
        env.setNow("2026-10-06T23:00")
        gate.submit(deadline(report.id, key = "report|24h"))
        gate.submit(deadline(done.id))
        env.setNow("2026-10-07T06:00")
        gate.submit(deadline(report.id, key = "report|2h"))
        env.complete(done.id)

        env.setNow("2026-10-07T08:01")
        val flush = gate.flushQuietHoursQueue()

        assertThat(flush).isEqualTo(FlushResult(delivered = 1, dropped = 2, asGroup = false))
        assertThat(poster.groups).isEmpty()
        assertThat(poster.shown.map { it.key }).containsExactly("report|2h")
        assertThat(env.ledger.budgetUsed()).isEqualTo(0)
    }

    @Test
    fun `over the budget the group still brings deadline reminders`() = runTest {
        val task = env.addTask()
        env.setNow("2026-10-06T23:00")
        gate.submit(deadline(task.id))
        gate.submit(review("review|1"))
        env.setNow("2026-10-07T07:00")
        repeat(3) { gate.submit(plan("plan|$it")) }

        env.setNow("2026-10-07T08:00")
        val flush = gate.flushQuietHoursQueue()

        assertThat(flush.delivered).isEqualTo(1)
        assertThat(poster.shown.last().key).isEqualTo("deadline|${task.id}")
    }

    @Test
    fun `the queue waits while moved quiet hours still last`() = runTest {
        val task = env.addTask()
        env.setNow("2026-10-06T23:00")
        gate.submit(deadline(task.id))
        env.store.updateData { it.copy(quietEndMinutes = 9 * 60) }

        env.setNow("2026-10-07T08:30")
        assertThat(gate.flushQuietHoursQueue()).isEqualTo(FlushResult.NOTHING)
        assertThat(env.alarm.scheduledAt).isEqualTo(env.at("2026-10-07T09:00"))
        assertThat(gate.nextFlushAt()).isEqualTo(env.at("2026-10-07T09:00"))
    }

    @Test
    fun `withdrawing a task drops its queued and shown notifications`() = runTest {
        val task = env.addTask()
        env.setNow("2026-10-06T23:00")
        gate.submit(deadline(task.id))

        gate.withdraw(task.id)

        assertThat(poster.cancelled).containsExactly(task.id)
        assertThat(gate.nextFlushAt()).isNull()
        assertThat(env.alarm.scheduledAt).isNull()
    }

    @Test
    fun `a queued reminder whose deadline passed before delivery is dropped (NTF-6)`() = runTest {
        val task = env.addTask(deadline = Deadline(date("2026-10-07"), LocalTime.of(9, 0), env.clock.zone()))
        env.setNow("2026-10-06T23:00")
        assertThat(gate.submit(deadline(task.id, deliverBy = env.at("2026-10-07T09:00"))))
            .isEqualTo(GateResult.Deferred(env.at("2026-10-07T08:00")))

        // The phone was off until after the deadline.
        env.setNow("2026-10-07T09:30")
        assertThat(gate.flushQuietHoursQueue()).isEqualTo(FlushResult(delivered = 0, dropped = 1, asGroup = false))
        assertThat(poster.shown).isEmpty()
    }

    @Test
    fun `a changed deadline drops the queued reminder but keeps the shown one`() = runTest {
        val shown = env.addTask()
        val queued = env.addTask()
        assertThat(gate.submit(deadline(shown.id))).isEqualTo(GateResult.Sent)
        env.setNow("2026-10-06T23:00")
        gate.submit(deadline(queued.id))

        gate.dropQueued(queued.id)
        gate.dropQueued(shown.id)

        assertThat(gate.nextFlushAt()).isNull()
        assertThat(env.alarm.scheduledAt).isNull()
        assertThat(poster.cancelled).isEmpty()
    }

    @Test
    fun `without notification permission the queue is dropped, nothing is logged`() = runTest {
        val task = env.addTask()
        env.setNow("2026-10-06T23:00")
        gate.submit(deadline(task.id))
        poster.permitted = false

        env.setNow("2026-10-07T08:00")
        assertThat(gate.flushQuietHoursQueue()).isEqualTo(FlushResult(delivered = 0, dropped = 1, asGroup = false))
        assertThat(env.ledger.wasSent("deadline|${task.id}")).isFalse()
        assertThat(gate.nextFlushAt()).isNull()
    }

    @Test
    fun `quiet hours can be switched off`() = runTest {
        env.store.updateData { AppSettings(quietStartMinutes = 0, quietEndMinutes = 0) }
        env.setNow("2026-10-06T23:30")
        assertThat(gate.submit(review("review|1"))).isEqualTo(GateResult.Sent)
    }
}
