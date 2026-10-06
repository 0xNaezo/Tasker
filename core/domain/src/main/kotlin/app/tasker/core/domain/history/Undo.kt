package app.tasker.core.domain.history

import app.tasker.core.model.FieldChange

/** Result of reverting one entity: the new state and fields that could not be reverted. */
data class Reverted<E>(
    val entity: E,
    val revertedFields: List<String>,
    val conflictingFields: List<String>,
)

/**
 * Builds the compensating state for undo (tech plan §8.4).
 * - VALUE columns go back to `before` only if their current value still equals `after`; otherwise the user
 *   changed them since and they are reported as conflicts (the app offers to open the task).
 * - COUNTER columns are reverted by the inverse delta and never go below zero, so later increments by the
 *   same automation do not block the undo.
 * - MARKER columns are kept for automation undo, otherwise the next catch-up would repeat the action.
 */
object UndoPlanner {
    fun <E> revert(
        current: E,
        changes: List<FieldChange>,
        columns: ColumnSet<E>,
        revertMarkers: Boolean,
    ): Reverted<E> {
        var entity = current
        val reverted = ArrayList<String>()
        val conflicts = ArrayList<String>()
        for (change in changes.asReversed()) {
            val column = columns.byKey(change.field) ?: continue
            when (column.kind) {
                ColumnKind.MARKER -> if (revertMarkers && change.before != null) {
                    entity = column.apply(entity, change.before!!)
                }
                ColumnKind.COUNTER -> {
                    val delta = column.counterValue(change.after) - column.counterValue(change.before)
                    val now = column.counterValue(column.encode(entity))
                    entity = column.apply(entity, kotlinx.serialization.json.JsonPrimitive((now - delta).coerceAtLeast(0)))
                    reverted += change.field
                }
                ColumnKind.VALUE -> {
                    val before = change.before ?: continue
                    if (column.encode(entity) == change.after) {
                        entity = column.apply(entity, before)
                        reverted += change.field
                    } else {
                        conflicts += change.field
                    }
                }
            }
        }
        return Reverted(entity, reverted.distinct(), conflicts.distinct() - reverted.toSet())
    }
}
