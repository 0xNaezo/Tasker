package app.tasker.core.domain.status

import app.tasker.core.model.TaskStatus

/** User actions that change a task status (EXC-1, tech plan §8.1). */
enum class TaskAction { START, PAUSE, RESUME, COMPLETE, REOPEN, ARCHIVE, RESTORE }

class InvalidTransitionException(
    val from: TaskStatus,
    val action: TaskAction,
) : IllegalStateException("Action $action is not allowed for a task in status $from")

/**
 * Pure status machine. An invalid transition is a command error, never silently ignored (§8.1).
 * - Open → In progress → Done; In progress ↔ Paused; Open → Done directly;
 * - Paused → Done directly (interpretation 3, so the "done" swipe works on paused tasks);
 * - Done → Open (undo from the done log; snackbar undo restores the exact previous status from events);
 * - any status except Archived → Archived; Archived → Open (TTL-8).
 */
object TaskStateMachine {
    fun target(from: TaskStatus, action: TaskAction): TaskStatus? = when (action) {
        TaskAction.START -> TaskStatus.IN_PROGRESS.takeIf { from == TaskStatus.OPEN }
        TaskAction.PAUSE -> TaskStatus.PAUSED.takeIf { from == TaskStatus.IN_PROGRESS }
        TaskAction.RESUME -> TaskStatus.IN_PROGRESS.takeIf { from == TaskStatus.PAUSED }
        TaskAction.COMPLETE -> TaskStatus.DONE.takeIf { from.isActive }
        TaskAction.REOPEN -> TaskStatus.OPEN.takeIf { from == TaskStatus.DONE }
        TaskAction.ARCHIVE -> TaskStatus.ARCHIVED.takeIf { from != TaskStatus.ARCHIVED }
        TaskAction.RESTORE -> TaskStatus.OPEN.takeIf { from == TaskStatus.ARCHIVED }
    }

    fun isAllowed(from: TaskStatus, action: TaskAction): Boolean = target(from, action) != null

    fun transition(from: TaskStatus, action: TaskAction): TaskStatus =
        target(from, action) ?: throw InvalidTransitionException(from, action)
}
