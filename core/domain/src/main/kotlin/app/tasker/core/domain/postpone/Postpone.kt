package app.tasker.core.domain.postpone

import app.tasker.core.model.Bucket
import app.tasker.core.model.Task
import java.time.LocalDate

/** Postpone menu options (EXC-2): tomorrow, week, someday, a date. */
sealed interface PostponeOption {
    data object Tomorrow : PostponeOption

    data object Week : PostponeOption

    data object Someday : PostponeOption

    data class OnDate(val date: LocalDate) : PostponeOption
}

/** New plan date and bucket after a postpone, and whether the postpone counter grows. */
data class PostponeResult(
    val planDate: LocalDate?,
    val bucket: Bucket?,
    val countsAsPostpone: Boolean,
)

/**
 * Postpone variants (tech plan §10.6, interpretation 25). The deadline never changes.
 * - Tomorrow: plan date = tomorrow; Today bucket becomes Week.
 * - Week: Week bucket; a past or today's plan date is cleared.
 * - Someday: Someday bucket; plan date cleared.
 * - Date: plan date = chosen date; Today bucket becomes Week.
 * The counter grows when a task that was "for today" (plan date ≤ today, Today bucket or today's plan item)
 * is moved to a later date — at most once per logical day (checked by [PostponeCounter]).
 */
object PostponePolicy {
    fun apply(task: Task, option: PostponeOption, today: LocalDate, inTodaysPlan: Boolean): PostponeResult {
        val wasForToday = isForToday(task, today, inTodaysPlan)
        return when (option) {
            PostponeOption.Tomorrow -> PostponeResult(
                planDate = today.plusDays(1),
                bucket = task.bucket.leavingToday(),
                countsAsPostpone = wasForToday,
            )
            PostponeOption.Week -> PostponeResult(
                planDate = task.planDate?.takeIf { it.isAfter(today) },
                bucket = Bucket.WEEK,
                countsAsPostpone = wasForToday,
            )
            PostponeOption.Someday -> PostponeResult(
                planDate = null,
                bucket = Bucket.SOMEDAY,
                countsAsPostpone = wasForToday,
            )
            is PostponeOption.OnDate -> PostponeResult(
                planDate = option.date,
                bucket = if (option.date.isAfter(today)) task.bucket.leavingToday() else task.bucket,
                countsAsPostpone = wasForToday && option.date.isAfter(today),
            )
        }
    }

    fun isForToday(task: Task, today: LocalDate, inTodaysPlan: Boolean): Boolean {
        val planDate = task.planDate
        return inTodaysPlan || task.bucket == Bucket.TODAY || (planDate != null && !planDate.isAfter(today))
    }

    private fun Bucket?.leavingToday(): Bucket? = if (this == Bucket.TODAY) Bucket.WEEK else this
}

/** Not more than +1 to the postpone counter per task and logical day (interpretation 8, §10.6). */
object PostponeCounter {
    fun canCount(task: Task, day: LocalDate): Boolean = task.lastPostponeDay != day

    fun increment(task: Task, day: LocalDate): Task =
        if (canCount(task, day)) task.copy(postponeCount = task.postponeCount + 1, lastPostponeDay = day) else task

    /**
     * DAT-3 question: the counter reached the threshold and the question was not asked at this level yet.
     * "Keep" remembers the level, so the next question comes after another [threshold] postpones.
     */
    fun needsQuestion(task: Task, threshold: Int): Boolean {
        if (!task.status.isActive || threshold <= 0) return false
        val prompted = task.postponePromptedAt
        return task.postponeCount >= threshold && (prompted == null || task.postponeCount >= prompted + threshold)
    }
}
