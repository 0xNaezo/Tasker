package app.tasker.core.domain.provenance

import app.tasker.core.model.FieldSource
import app.tasker.core.model.Task
import app.tasker.core.model.TaskField

/**
 * Field provenance (tech plan §8.3, AI-4, GH-5). A writer may fill an empty field, or overwrite a field whose
 * source has no higher priority than its own: USER > PARSER > INTEGRATION > AI > DEFAULT. So AI writes only
 * empty, AI or DEFAULT fields; an integration keeps a field in sync until the user edits it.
 */
object FieldProvenance {
    fun canWrite(current: FieldSource?, valuePresent: Boolean, writer: FieldSource): Boolean =
        !valuePresent || current == null || writer.priority >= current.priority

    fun canWrite(task: Task, field: TaskField, writer: FieldSource): Boolean =
        canWrite(task.fieldSources[field], hasValue(task, field), writer)

    fun hasValue(task: Task, field: TaskField): Boolean = when (field) {
        TaskField.TITLE -> task.title.isNotBlank()
        TaskField.NOTE -> !task.note.isNullOrBlank()
        TaskField.BUCKET -> task.bucket != null
        TaskField.DEADLINE -> task.deadline != null
        TaskField.PLAN_DATE -> task.planDate != null
        TaskField.ESTIMATE -> task.estimate != null
        TaskField.PROJECT -> task.projectId != null
        TaskField.TAGS -> task.tags.isNotEmpty()
    }

    /** Marks [fields] as written by [source]; a field cleared by the user loses its provenance. */
    fun mark(task: Task, source: FieldSource, vararg fields: TaskField): Task {
        val updated = task.fieldSources.toMutableMap()
        for (field in fields) {
            if (hasValue(task, field)) updated[field] = source else updated.remove(field)
        }
        return task.copy(fieldSources = updated)
    }

    /** Fields currently filled by AI (shown with an AI badge and undoable in one tap). */
    fun aiFields(task: Task): Set<TaskField> =
        task.fieldSources.filterValues { it == FieldSource.AI }.keys.filterTo(LinkedHashSet()) { hasValue(task, it) }
}
