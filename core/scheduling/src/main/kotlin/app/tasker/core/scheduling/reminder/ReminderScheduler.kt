package app.tasker.core.scheduling.reminder

import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.domain.notify.QuietHours
import app.tasker.core.domain.notify.ReminderPlanner
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import app.tasker.core.notifications.DeadlineTimes
import app.tasker.core.notifications.GateResult
import app.tasker.core.notifications.NotificationGate
import app.tasker.core.notifications.NotificationRequests
import app.tasker.core.scheduling.AlarmKind
import app.tasker.core.scheduling.Alarms
import app.tasker.core.scheduling.ReminderTiming
import app.tasker.core.scheduling.SchedulerState
import app.tasker.core.scheduling.TaskReminders
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What one pass of the reminder scheduler did: gate results of the reminders it sent and the next alarm. */
data class ReminderPass(val results: List<GateResult>, val nextAlarmAt: Instant?)

/**
 * Deadline reminders (NTF-2, tech plan §12.2): a date-only deadline is reminded the day before at the plan time, a
 * timed one 24 h and 2 h before, or as the task's own offsets say. Due reminders go through the notification gate;
 * then a single alarm is kept for the earliest coming reminder within 48 hours. Runs on that alarm, after task changes,
 * daily and after boot or a time change.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val clock: DayClock,
    private val gate: NotificationGate,
    private val requests: NotificationRequests,
    private val alarms: Alarms,
    private val state: SchedulerState,
) {
    private val mutex = Mutex()

    suspend fun run(): ReminderPass = mutex.withLock { runLocked() }

    /**
     * The deadline, reminders or status of [changed] tasks changed and [deleted] tasks are gone: slots that are already
     * past will not fire for them and reminders held back by quiet hours are out of date; finished tasks also lose the
     * reminder that is shown. Then the alarm is re-armed.
     */
    suspend fun onTasksChanged(changed: Set<String>, deleted: Set<String>): ReminderPass = mutex.withLock {
        state.markReminderChange(changed, clock.now())
        val (active, finished) = changed.partition { tasks.task(it)?.status?.isActive == true }
        active.forEach { gate.dropQueued(it) }
        (finished + deleted).forEach { gate.withdraw(it) }
        runLocked()
    }

    private suspend fun runLocked(): ReminderPass {
        val current = settings.current()
        if (!current.notifyDeadlines) {
            alarms.cancel(AlarmKind.DEADLINE_REMINDERS)
            return ReminderPass(emptyList(), null)
        }
        val now = clock.now()
        val quiet = QuietHours.of(current)
        val reminders = load(current)
        val results = ReminderTiming.due(reminders, now, state.reminderChanges()).map { due ->
            val task = due.reminders.task
            val deliverAt = ReminderTiming.deliveryTime(now, due.reminders.deadlineEnd, quiet, clock)
            gate.submit(requests.deadline(task.id, task.title, checkNotNull(task.deadline), due.slot.key, deliverAt))
        }
        val next = ReminderTiming.next(reminders, now)
        if (next == null) {
            alarms.cancel(AlarmKind.DEADLINE_REMINDERS)
        } else {
            alarms.set(AlarmKind.DEADLINE_REMINDERS, ReminderTiming.window(next.slot.at, next.reminders.deadlineEnd, quiet, clock, now))
        }
        state.retainReminderChanges(reminders.mapTo(HashSet()) { it.task.id })
        return ReminderPass(results, next?.slot?.at)
    }

    private suspend fun load(settings: AppSettings): List<TaskReminders> = tasks.observeActive().first().mapNotNull { task ->
        val deadline = task.deadline ?: return@mapNotNull null
        val offsets = task.reminderOffsets?.minutesBefore ?: settings.deadlineReminderMinutes
        TaskReminders(task, ReminderPlanner.slots(task.id, deadline, offsets, settings, clock), DeadlineTimes.end(deadline, clock))
    }
}
