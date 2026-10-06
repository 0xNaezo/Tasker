package app.tasker.core.notifications

import app.tasker.core.domain.notify.NotificationType
import java.time.Instant

/** A notification the app wants to show; every one goes through [NotificationGate] (tech plan §12.1). */
data class NotificationRequest(
    val type: NotificationType,
    /** Identity: a key is never sent twice (one reminder slot, one morning plan per day). */
    val key: String,
    val title: String,
    val text: String,
    /** The task it is about: such notifications get the task actions (NTF-5) and go away when the task is done. */
    val taskId: String? = null,
    /** Latest useful delivery, e.g. the deadline: quiet hours never hold a notification past it (interpretation 17). */
    val deliverBy: Instant? = null,
)

/** What the gate did with a request. */
sealed interface GateResult {
    data object Sent : GateResult

    /** Quiet hours: queued and delivered with the group after [until] (NTF-4). */
    data class Deferred(val until: Instant) : GateResult

    /** Over the daily limit: nothing is sent, the information stays in the app (NTF-3). */
    data object OverBudget : GateResult

    /** The type is off in the app settings or the user blocked its channel. */
    data object Disabled : GateResult

    /** Notifications are off for the app or POST_NOTIFICATIONS is not granted; the app shows a banner instead. */
    data object NotPermitted : GateResult

    /** A notification with this key was already sent. */
    data object Duplicate : GateResult
}

/** Result of delivering the quiet-hours queue: [delivered] notifications, [asGroup] when they came as one group. */
data class FlushResult(val delivered: Int, val dropped: Int, val asGroup: Boolean) {
    companion object {
        val NOTHING = FlushResult(delivered = 0, dropped = 0, asGroup = false)
    }
}
