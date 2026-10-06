package app.tasker.core.scheduling.reminder

import app.tasker.core.data.effects.CommitInfo
import app.tasker.core.data.effects.CommitListener
import javax.inject.Inject

/**
 * Post-commit effect (tech plan §4.3, step 5): when a command changes a deadline, its reminders or whether a task is
 * active, or deletes a task, the reminder alarm is recomputed. Never runs inside the transaction.
 */
class ReminderCommitListener @Inject constructor(private val reminders: ReminderScheduler) : CommitListener {
    override suspend fun onCommitted(info: CommitInfo) {
        if (info.reminderTaskIds.isEmpty() && info.deletedTaskIds.isEmpty()) return
        reminders.onTasksChanged(info.reminderTaskIds, info.deletedTaskIds)
    }
}
