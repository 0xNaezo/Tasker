package app.tasker.core.notifications.action

import android.util.Log
import app.tasker.core.data.command.CommandException
import app.tasker.core.data.command.TaskCommands
import app.tasker.core.data.di.ApplicationScope
import app.tasker.core.domain.status.InvalidTransitionException
import app.tasker.core.notifications.NotificationGate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/** Handlers of notification actions that need no UI (NTF-5). */
@Singleton
class NotificationActions @Inject constructor(
    private val tasks: TaskCommands,
    private val gate: NotificationGate,
    @param:ApplicationScope val scope: CoroutineScope,
) {
    /**
     * "Done": completes the task like a swipe in the app (one command, one event) and removes its notification.
     * A task that was completed, archived or deleted meanwhile only loses its notification.
     */
    suspend fun complete(taskId: String): Boolean {
        gate.dismiss(taskId)
        return try {
            tasks.complete(taskId)
            true
        } catch (e: CommandException) {
            Log.i(TAG, "Task $taskId cannot be completed from the notification", e)
            false
        } catch (e: InvalidTransitionException) {
            Log.i(TAG, "Task $taskId is not active any more", e)
            false
        }
    }

    private companion object {
        const val TAG = "NotificationActions"
    }
}
