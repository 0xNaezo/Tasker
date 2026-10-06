package app.tasker.core.notifications

import app.tasker.core.data.repository.NotificationLedger
import app.tasker.core.data.repository.PendingNotification
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.domain.notify.GateDecision
import app.tasker.core.domain.notify.NotificationPolicy
import app.tasker.core.domain.notify.NotificationType
import app.tasker.core.domain.notify.QuietHours
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import java.time.Instant
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The only way to notify the user (tech plan §12.1, NTF-1…NTF-6):
 * 1. the type is enabled in the app settings and the user did not block its channel;
 * 2. in quiet hours a notification waits in the queue and comes after them in one group (NTF-4). Exceptions
 *    (interpretation 17): the morning plan, and anything that would otherwise arrive after its
 *    [NotificationRequest.deliverBy], e.g. a deadline reminder delivered late by the system;
 * 3. at most `dailyNotificationLimit` notifications per logical day, deadlines not counted (NTF-3); over the limit
 *    nothing is sent and the information stays in the app;
 * 4. every sent notification is written to the log, and a key is never sent twice.
 *
 * There is no "you did not do it" type (NTF-6): the list of types is closed in `NotificationType`.
 */
@Singleton
class NotificationGate @Inject constructor(
    private val settings: SettingsRepository,
    private val ledger: NotificationLedger,
    private val tasks: TaskRepository,
    private val clock: DayClock,
    private val poster: NotificationPoster,
    private val quietHoursAlarm: Optional<QuietHoursAlarm>,
) {
    private val mutex = Mutex()

    suspend fun submit(request: NotificationRequest): GateResult = mutex.withLock {
        if (ledger.wasSent(request.key)) return@withLock GateResult.Duplicate
        val current = settings.current()
        if (!NotificationPolicy.isEnabled(request.type, current) || !poster.isChannelEnabled(request.type)) {
            return@withLock GateResult.Disabled
        }
        if (!poster.canPost()) return@withLock GateResult.NotPermitted
        val now = clock.now()
        val used = ledger.budgetUsed(clock.logicalDay(now))
        when (val decision = NotificationPolicy.decide(request.type, now, current, used, clock)) {
            GateDecision.Send -> send(request, now)
            GateDecision.OverBudget -> GateResult.OverBudget
            GateDecision.Disabled -> GateResult.Disabled
            is GateDecision.Defer -> {
                val deliverBy = request.deliverBy
                when {
                    deliverBy == null || decision.until.isBefore(deliverBy) -> defer(request, decision.until)
                    // Waiting for the end of quiet hours would make the notification useless: it goes now.
                    request.type.countsInBudget && used >= current.dailyNotificationLimit -> GateResult.OverBudget
                    else -> send(request, now)
                }
            }
        }
    }

    /**
     * Delivers what quiet hours held back (NTF-4). Two or more notifications come as one group — a summary and a child
     * per notification, each with its own actions (NTF-5) — that counts as one notification in the budget and is logged
     * once as [NotificationType.QUIET_HOURS_DIGEST]; children exempt from the budget (deadlines) are logged under their
     * own type, so their keys are never sent again. A single notification comes as itself. Items whose task is no longer
     * active, whose type was switched off or that were sent meanwhile are dropped, and so are deadline reminders whose
     * deadline has passed; of several reminders of one task only the latest comes.
     */
    suspend fun flushQuietHoursQueue(): FlushResult = mutex.withLock {
        val now = clock.now()
        val current = settings.current()
        if (QuietHours.of(current).contains(now, clock)) {
            // Quiet hours were moved after the items were queued: wait for their new end.
            rescheduleQuietHoursFlush()
            return@withLock FlushResult.NOTHING
        }
        val due = ledger.due(now)
        val result = if (due.isEmpty()) FlushResult.NOTHING else deliver(due, current, now)
        ledger.remove(due.map { it.id })
        rescheduleQuietHoursFlush()
        result
    }

    /** The task is done, archived or deleted: its queued notifications are dropped and the shown one is removed. */
    suspend fun withdraw(taskId: String) = mutex.withLock {
        ledger.removeForTask(taskId)
        poster.cancelTask(taskId)
        rescheduleQuietHoursFlush()
    }

    /**
     * The deadline or the reminders of a task changed: what quiet hours held back about it is out of date and dropped.
     * The reminder scheduler then submits whatever is due for the new deadline.
     */
    suspend fun dropQueued(taskId: String) = mutex.withLock {
        ledger.removeForTask(taskId)
        rescheduleQuietHoursFlush()
    }

    /** Removes the shown notification about a task, e.g. once its "Postpone" was handled in the app. */
    fun dismiss(taskId: String) = poster.cancelTask(taskId)

    /** When the queue should be delivered: at its earliest item, but never inside quiet hours. `null` when empty. */
    suspend fun nextFlushAt(): Instant? {
        val next = ledger.nextDelivery() ?: return null
        return QuietHours.of(settings.current()).endAfter(maxOf(next, clock.now()), clock)
    }

    /** Re-arms or cancels the alarm that delivers the queue; called after every change of the queue and by the scheduler. */
    suspend fun rescheduleQuietHoursFlush() {
        val alarm = quietHoursAlarm.orElse(null) ?: return
        val at = nextFlushAt()
        if (at == null) alarm.cancel() else alarm.schedule(at)
    }

    private suspend fun send(request: NotificationRequest, now: Instant): GateResult {
        poster.show(request)
        ledger.logSent(request.type, request.key, request.taskId, now)
        return GateResult.Sent
    }

    private suspend fun defer(request: NotificationRequest, until: Instant): GateResult {
        ledger.defer(request.type, request.key, request.taskId, request.title, request.text, until)
        rescheduleQuietHoursFlush()
        return GateResult.Deferred(until)
    }

    private suspend fun deliver(due: List<PendingNotification>, current: AppSettings, now: Instant): FlushResult {
        val wanted = latestPerTask(due.filter { isStillWanted(it, current, now) })
        if (wanted.isEmpty() || !poster.canPost()) return FlushResult(delivered = 0, dropped = due.size, asGroup = false)
        val (exempt, counted) = wanted.partition { !it.type.countsInBudget }
        val withinBudget = ledger.budgetUsed(clock.logicalDay(now)) < current.dailyNotificationLimit
        val delivered = (if (withinBudget) exempt + counted else exempt).sortedBy { it.id }.map { it.toRequest() }
        when (delivered.size) {
            0 -> Unit
            1 -> send(delivered.single(), now)
            else -> {
                poster.showGroup(delivered)
                ledger.logSent(NotificationType.QUIET_HOURS_DIGEST, "$DIGEST_KEY_PREFIX${now.toEpochMilli()}", null, now)
                delivered.filterNot { it.type.countsInBudget }.forEach { ledger.logSent(it.type, it.key, it.taskId, now) }
            }
        }
        return FlushResult(delivered = delivered.size, dropped = due.size - delivered.size, asGroup = delivered.size > 1)
    }

    private suspend fun isStillWanted(item: PendingNotification, current: AppSettings, now: Instant): Boolean {
        if (ledger.wasSent(item.key)) return false
        if (!NotificationPolicy.isEnabled(item.type, current) || !poster.isChannelEnabled(item.type)) return false
        val taskId = item.taskId ?: return true
        val task = tasks.task(taskId)
        if (task == null || !task.status.isActive) return false
        // The alarm came late (phone off, Doze): a reminder of a deadline that has passed is only noise (NTF-6).
        if (item.type != NotificationType.DEADLINE) return true
        val deadline = task.deadline ?: return false
        return now.isBefore(DeadlineTimes.end(deadline, clock))
    }

    /** One notification per task: a later reminder of the same task replaces the earlier one. */
    private fun latestPerTask(items: List<PendingNotification>): List<PendingNotification> {
        val latest = items.filter { it.taskId != null }.groupBy { it.taskId }.values.mapTo(HashSet()) { group -> group.maxBy { it.id } }
        return items.filter { it.taskId == null || it in latest }
    }

    private fun PendingNotification.toRequest() = NotificationRequest(type, key, title, text, taskId)

    private companion object {
        const val DIGEST_KEY_PREFIX = "quiet-hours-digest|"
    }
}
