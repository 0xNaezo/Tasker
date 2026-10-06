package app.tasker.core.domain.split

import app.tasker.core.model.Bucket
import app.tasker.core.model.Deadline
import app.tasker.core.model.Task
import java.time.LocalDate

/** Fields one step of a split inherits from the original task (tech plan §8.5). */
data class StepSeed(
    val text: String,
    val bucket: Bucket?,
    val planDate: LocalDate?,
    val deadline: Deadline?,
    val note: String?,
    val copySources: Boolean,
    val takesPlanSlot: Boolean,
)

/**
 * Split (DAT-3, PLN-8, EXE-3): the task becomes a project named after it, each line becomes a task of that project.
 * The first step inherits the bucket, the plan date and the place in today's plan; the deadline moves to the last
 * step; the note and sources are copied to the first step. The original task is archived with reason SPLIT.
 */
object SplitPlanner {
    const val MIN_STEPS = 2
    const val MAX_STEPS = 5

    fun normalizeSteps(lines: List<String>): List<String> =
        lines.map { it.trim().trimStart('-', '•', '*', ' ') }.filter { it.isNotBlank() }

    fun isValid(lines: List<String>): Boolean = normalizeSteps(lines).size in MIN_STEPS..MAX_STEPS

    fun seeds(task: Task, lines: List<String>): List<StepSeed> {
        val steps = normalizeSteps(lines)
        require(steps.size in MIN_STEPS..MAX_STEPS) { "A split needs $MIN_STEPS..$MAX_STEPS steps" }
        return steps.mapIndexed { index, text ->
            val first = index == 0
            val last = index == steps.lastIndex
            StepSeed(
                text = text,
                bucket = if (first) task.bucket else laterStepBucket(task.bucket),
                planDate = if (first) task.planDate else null,
                deadline = if (last) task.deadline else null,
                note = if (first) task.note else null,
                copySources = first,
                takesPlanSlot = first,
            )
        }
    }

    /** Later steps stay on the same horizon, but only the first step remains "for today". */
    private fun laterStepBucket(original: Bucket?): Bucket? = when (original) {
        Bucket.TODAY -> Bucket.WEEK
        else -> original
    }
}
