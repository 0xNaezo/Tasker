package app.tasker.core.data.command

import app.tasker.core.data.mapper.toMillis
import app.tasker.core.data.mapper.toModel
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.history.ProjectColumns
import app.tasker.core.domain.history.TaskColumns
import app.tasker.core.domain.history.UndoPlanner
import app.tasker.core.model.Actor
import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.BatchId
import app.tasker.core.model.EntityType
import app.tasker.core.model.Event
import app.tasker.core.model.EventType
import app.tasker.core.model.FieldSource
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.ReasonCode
import app.tasker.core.model.TaskField
import app.tasker.core.model.TaskId
import app.tasker.core.model.TaskStatus
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonNull

/** Fields of an entity that were changed by the user after the undone action and therefore stayed (interpretation 16). */
data class UndoConflict(val entityType: EntityType, val entityId: String, val fields: List<String>)

data class UndoOutcome(val undoneEntities: Int, val conflicts: List<UndoConflict>) {
    /** Nothing could be undone: the window passed or everything was undone already. */
    val nothingToUndo: Boolean get() = undoneEntities == 0
}

/**
 * Undo (EXC-7, AUT-1, AI-4, tech plan §8.4): a compensating user command built from the events of a batch.
 * - snackbar undo of a user command restores every field it changed, idempotency markers included;
 * - automation undo restores values the user has not changed since, reverts counters by their delta and keeps rule
 *   markers, so the next catch-up does not repeat the action; leaving review always resets the review flag and streak;
 * - plans of past days are never changed (they feed the metrics).
 */
@Singleton
class UndoService @Inject constructor(
    private val runner: TxRunner,
    private val db: TaskerDatabase,
) {
    private val eventDao = db.eventDao()

    /** Undo a whole batch: a snackbar action or a journal row. */
    suspend fun undoBatch(batchId: BatchId): TxResult<UndoOutcome> {
        val events = eventDao.batch(batchId).map { it.toModel() }
        return undo(events)
    }

    /** Undo one entity of an automation batch from the expanded journal row. */
    suspend fun undoEntity(batchId: BatchId, entityId: String): TxResult<UndoOutcome> {
        val events = eventDao.batch(batchId).map { it.toModel() }.filter { it.entityId == entityId }
        return undo(events)
    }

    /** "Undo AI" on a task (AI-4): fields filled by the latest AI event go back to their previous values. */
    suspend fun undoAiFill(taskId: TaskId, fields: Set<TaskField>? = null): TxResult<UndoOutcome> {
        val event = eventDao.latestAiFill(taskId)?.toModel()
        return runner.user {
            if (event == null || event.isUndone) return@user UndoOutcome(0, emptyList())
            val current = taskOrNull(taskId) ?: return@user UndoOutcome(0, emptyList())
            val columns = fields?.map(::columnKeyOf)?.toSet()
            val changes = event.changes.filter { columns == null || it.field in columns }
            val reverted = UndoPlanner.revert(current, changes, TaskColumns, revertMarkers = false)
            val aiFields = (fields ?: current.fieldSources.filterValues { it == FieldSource.AI }.keys)
            val sources = current.fieldSources.filterNot { (field, source) -> field in aiFields && source == FieldSource.AI }
            updateTask(reverted.entity.copy(fieldSources = sources), EventType.UNDONE, keepReview = true)
            if (fields == null) eventDao.markEntityUndone(event.batchId, taskId, now.toMillis(), batchId)
            UndoOutcome(1, conflictsOf(EntityType.TASK, taskId, reverted.conflictingFields))
        }
    }

    private suspend fun undo(events: List<Event>): TxResult<UndoOutcome> = runner.user {
        val pending = events.filter { it.canUndo(now) }
            .sortedWith(compareByDescending<Event> { it.createdAt }.thenByDescending { it.id })
        if (pending.isEmpty()) return@user UndoOutcome(0, emptyList())
        val conflicts = ArrayList<UndoConflict>()
        var undone = 0
        for (event in pending) {
            val handled = when (event.entityType) {
                EntityType.TASK -> undoTask(this, event, conflicts)
                EntityType.PROJECT -> undoProject(this, event, conflicts)
                EntityType.PLAN -> undoPlan(this, event)
                EntityType.SETTINGS -> false
            }
            if (handled) undone++
            eventDao.markEntityUndone(event.batchId, event.entityId, now.toMillis(), batchId)
        }
        UndoOutcome(undone, conflicts)
    }

    private suspend fun undoTask(tx: Tx, event: Event, conflicts: MutableList<UndoConflict>): Boolean = with(tx) {
        val current = taskOrNull(event.entityId) ?: return false
        if (event.type == EventType.CREATED) {
            // Undoing a capture archives the task instead of deleting it: nothing is lost.
            if (current.status == TaskStatus.ARCHIVED) return false
            updateTask(
                current.copy(status = TaskStatus.ARCHIVED, archivedAt = now, archiveReason = ArchiveReason.USER),
                EventType.UNDONE,
            )
            return true
        }
        val automatic = event.actor != Actor.USER
        val reverted = UndoPlanner.revert(current, event.changes, TaskColumns, revertMarkers = !automatic)
        var entity = reverted.entity
        if (automatic && leavesReview(event)) {
            entity = entity.copy(inReview = false, reviewSince = null, reviewSkipStreak = 0)
        }
        updateTask(entity, EventType.UNDONE, keepReview = !automatic)
        conflicts += conflictsOf(EntityType.TASK, event.entityId, reverted.conflictingFields)
        return true
    }

    private suspend fun undoProject(tx: Tx, event: Event, conflicts: MutableList<UndoConflict>): Boolean = with(tx) {
        val current = projectOrNull(event.entityId) ?: return false
        if (event.type == EventType.CREATED) {
            val hasOtherTasks = db.taskDao().projectTasks(current.id).any { it.status.isActive }
            if (hasOtherTasks || current.status == ProjectStatus.ARCHIVED) return false
            updateProject(current.copy(status = ProjectStatus.ARCHIVED, archivedAt = now), EventType.UNDONE)
            return true
        }
        val automatic = event.actor != Actor.USER
        val reverted = UndoPlanner.revert(current, event.changes, ProjectColumns, revertMarkers = !automatic)
        var entity = reverted.entity
        if (automatic && event.reason?.code == ReasonCode.PROJECT_INACTIVE) entity = entity.copy(inReview = false, reviewSince = null)
        updateProject(entity, EventType.UNDONE, keepReview = !automatic)
        conflicts += conflictsOf(EntityType.PROJECT, event.entityId, reverted.conflictingFields)
        return true
    }

    /** Only today's and future plans follow undo; past plans keep their items for the metrics. */
    private suspend fun undoPlan(tx: Tx, event: Event): Boolean = with(tx) {
        val date = runCatching { LocalDate.parse(event.entityId) }.getOrNull() ?: return false
        if (date.isBefore(today)) return false
        var plan = plan(date) ?: return false
        var changed = false
        for (change in event.changes.asReversed()) {
            val taskId = PlanFields.taskIdOf(change.field) ?: continue
            val currentItem = plan.item(taskId)
            if (PlanFields.encode(currentItem) != (change.after ?: JsonNull)) continue
            val before = change.before?.let { PlanFields.decode(plan, taskId, it) }
            plan = if (before == null) plan.withoutItem(taskId) else plan.withItem(before)
            changed = true
        }
        if (changed) savePlan(plan)
        return changed
    }

    private fun leavesReview(event: Event): Boolean = when (event.reason?.code) {
        ReasonCode.TTL_EXPIRED, ReasonCode.REVIEW_SKIPPED, ReasonCode.TTL_SKIPS -> true
        else -> false
    }

    private fun conflictsOf(type: EntityType, id: String, fields: List<String>): List<UndoConflict> =
        if (fields.isEmpty()) emptyList() else listOf(UndoConflict(type, id, fields))

    private fun columnKeyOf(field: TaskField): String = when (field) {
        TaskField.TITLE -> TaskColumns.TITLE.key
        TaskField.NOTE -> TaskColumns.NOTE.key
        TaskField.BUCKET -> TaskColumns.BUCKET.key
        TaskField.DEADLINE -> TaskColumns.DEADLINE.key
        TaskField.PLAN_DATE -> TaskColumns.PLAN_DATE.key
        TaskField.ESTIMATE -> TaskColumns.ESTIMATE.key
        TaskField.PROJECT -> TaskColumns.PROJECT.key
        TaskField.TAGS -> TaskColumns.TAGS.key
    }
}
