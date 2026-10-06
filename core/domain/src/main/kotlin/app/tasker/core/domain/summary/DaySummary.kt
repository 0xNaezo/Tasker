package app.tasker.core.domain.summary

import java.time.LocalDate

/**
 * Day summary for the done log (LOG-2, interpretation 11): done, of which planned and off-plan, and postponed.
 * No streaks, ratings or comparisons with previous periods (principle 6).
 */
data class DaySummary(
    val day: LocalDate,
    val done: Int,
    val donePlanned: Int,
    val postponed: Int,
) {
    val doneOffPlan: Int get() = done - donePlanned

    companion object {
        /**
         * @param doneTaskIds tasks completed during the logical [day]
         * @param acceptedPlanTaskIds tasks of the accepted plan of that day (empty if there was none)
         * @param offPlanTaskIds tasks captured as already done (CAP-8), always off-plan
         */
        fun of(
            day: LocalDate,
            doneTaskIds: Collection<String>,
            acceptedPlanTaskIds: Collection<String>,
            offPlanTaskIds: Collection<String>,
            postponedTaskCount: Int,
        ): DaySummary {
            val planned = doneTaskIds.count { it in acceptedPlanTaskIds && it !in offPlanTaskIds }
            return DaySummary(day, doneTaskIds.size, planned, postponedTaskCount)
        }
    }
}
