package app.tasker.core.ui.action

import android.util.Log
import app.tasker.core.data.command.SnapshotInput
import app.tasker.core.data.command.StartOutcome
import app.tasker.core.data.command.TaskCommands
import app.tasker.core.data.command.TxResult
import app.tasker.core.data.plan.PlanService
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.model.Bucket
import app.tasker.core.model.SnapshotInputKind
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import app.tasker.core.ui.R
import app.tasker.core.ui.message.Messenger
import app.tasker.core.ui.text.UiText
import app.tasker.core.ui.util.attempt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task commands as screens use them: every change ends with the app snackbar offering Undo (EXC-7, §14.3), failures
 * are reported instead of crashing. Shared by all lists, the plan and the task card.
 */
@Singleton
class TaskActions @Inject constructor(
    private val commands: TaskCommands,
    private val plans: PlanService,
    private val messenger: Messenger,
) {
    /** The check mark: completes an unfinished task, reopens a done one. */
    suspend fun toggleDone(task: Task) = if (task.status == TaskStatus.DONE) reopen(task) else complete(task)

    suspend fun complete(task: Task) = run(R.string.undo_done, task) { commands.complete(task.id) }

    suspend fun reopen(task: Task) = run(R.string.undo_reopened, task) { commands.reopen(task.id) }

    suspend fun postpone(task: Task, option: PostponeOption) = run(R.string.undo_postponed, task) { commands.postpone(task.id, option) }

    suspend fun move(task: Task, bucket: Bucket?) = run(R.string.undo_moved, task) { commands.move(task.id, bucket) }

    suspend fun archive(task: Task) = run(R.string.undo_archived, task) { commands.archive(task.id) }

    suspend fun restore(task: Task) = run(R.string.undo_restored, task) { commands.restore(task.id) }

    /** Start or resume; the result tells the screen whether to offer pausing another task (DAT-7). */
    suspend fun start(task: Task): StartOutcome? = attempt { commands.start(task.id) }
        .onFailure(::report)
        .getOrNull()
        ?.also { messenger.undoable(UiText.Res(R.string.undo_started, task.title), it.batchId) }
        ?.value

    /** Pause with an optional "where I stopped" note (EXC-3). */
    suspend fun pause(task: Task, note: String?, kind: SnapshotInputKind = SnapshotInputKind.TEXT) =
        run(R.string.undo_paused, task) { commands.pause(task.id, note?.takeIf { it.isNotBlank() }?.let { SnapshotInput(it, kind) }) }

    suspend fun addToPlan(task: Task) = run(R.string.undo_added_to_plan, task) { plans.add(task.id) }

    suspend fun removeFromPlan(task: Task) = run(R.string.undo_removed_from_plan, task) { plans.remove(task.id) }

    /** DAT-3 "keep": the question comes back only after another series of postpones. */
    suspend fun keepAfterQuestion(task: Task) = run(R.string.undo_kept, task) { commands.keepAfterPostponeQuestion(task.id) }

    /** Split into a project with steps (DAT-3, PLN-8, EXE-3). */
    suspend fun split(task: Task, steps: List<String>) = run(R.string.undo_split, task) { commands.split(task.id, steps) }

    private suspend fun run(message: Int, task: Task, block: suspend () -> TxResult<*>): Boolean =
        attempt(block)
            .onSuccess { messenger.undoable(UiText.Res(message, task.title), it.batchId) }
            .onFailure(::report)
            .isSuccess

    private fun report(error: Throwable) {
        Log.w(TAG, "Command failed", error)
        messenger.info(UiText.Res(R.string.error_generic))
    }

    private companion object {
        const val TAG = "TaskActions"
    }
}
