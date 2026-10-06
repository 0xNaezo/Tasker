package app.tasker.core.data.effects

import app.tasker.core.data.di.ApplicationScope
import app.tasker.core.model.Actor
import app.tasker.core.model.BatchId
import app.tasker.core.model.ProjectId
import app.tasker.core.model.TaskId
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** What a committed command changed; post-commit effects decide from it what to refresh (tech plan §4.3, step 5). */
data class CommitInfo(
    val batchId: BatchId,
    val actor: Actor,
    val taskIds: Set<TaskId>,
    val projectIds: Set<ProjectId>,
    val planDates: Set<LocalDate>,
    /** Tasks whose deadline, reminders or active state changed: deadline alarms need rescheduling. */
    val reminderTaskIds: Set<TaskId>,
    /** Tasks that were queued for AI enrichment by this commit. */
    val enrichTaskIds: Set<TaskId>,
    /** Tasks deleted permanently. */
    val deletedTaskIds: Set<TaskId> = emptySet(),
) {
    val isEmpty: Boolean
        get() = taskIds.isEmpty() && projectIds.isEmpty() && planDates.isEmpty() && deletedTaskIds.isEmpty()
}

/** A side effect that runs after a successful commit: reminders, widget refresh, AI queue (never inside a transaction). */
fun interface CommitListener {
    suspend fun onCommitted(info: CommitInfo)
}

/**
 * Runs post-commit listeners on the application scope, so a slow effect never delays the command and a failing
 * effect never undoes it.
 */
@Singleton
class CommitEffects @Inject constructor(
    private val listeners: Set<@JvmSuppressWildcards CommitListener>,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val commits = MutableSharedFlow<CommitInfo>(extraBufferCapacity = BUFFER)

    /** Every commit, for observers that are not listeners (e.g. tests, the widget). */
    val committed: SharedFlow<CommitInfo> = commits.asSharedFlow()

    fun dispatch(info: CommitInfo) {
        if (info.isEmpty) return
        commits.tryEmit(info)
        if (listeners.isEmpty()) return
        scope.launch {
            for (listener in listeners) {
                runCatching { listener.onCommitted(info) }
            }
        }
    }

    private companion object {
        const val BUFFER = 64
    }
}
