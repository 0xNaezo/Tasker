package app.tasker.core.model

import java.time.Instant
import java.time.LocalDate

/**
 * Day plan (PLN-4…PLN-10, tech plan §7.4). The capacity snapshot is taken at acceptance and used only
 * for metrics; the live screen always recomputes capacity for "now".
 */
data class DayPlan(
    val date: LocalDate,
    val state: PlanState,
    val createdAt: Instant,
    val acceptedAt: Instant? = null,
    val workingMin: Int = 0,
    val busyMin: Int = 0,
    val capacityMin: Int = 0,
    val plannedMin: Int = 0,
    val items: List<DayPlanItem> = emptyList(),
) {
    val isAccepted: Boolean get() = state == PlanState.ACCEPTED

    /** Items still in the plan (not removed), in plan order. */
    val activeItems: List<DayPlanItem>
        get() = items.filter { it.outcome != PlanItemOutcome.REMOVED }.sortedBy { it.position }
}

data class DayPlanItem(
    val date: LocalDate,
    val taskId: TaskId,
    val position: Int,
    val minutes: Int,
    val origin: PlanItemOrigin,
    val candidateGroup: CandidateGroup? = null,
    val outcome: PlanItemOutcome = PlanItemOutcome.PENDING,
)
