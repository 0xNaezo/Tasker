package app.tasker.core.domain.plan

/**
 * Result of auto-selection (PLN-5). Every auto-selectable candidate is either [selected] or in
 * [doesNotFit]; candidates in review outside the deadline groups are [skippedInReview] and can be added by hand.
 */
data class PlanProposal(
    val selected: List<Candidate>,
    val doesNotFit: List<Candidate>,
    val skippedInReview: List<Candidate>,
    val capacityMin: Int,
) {
    val plannedMin: Int get() = selected.sumOf { it.minutes }
    val freeMin: Int get() = (capacityMin - plannedMin).coerceAtLeast(0)
}

/**
 * "First fit" auto-selection (interpretation 7): candidates go in order; one that doesn't fit the remaining
 * capacity goes to "Doesn't fit" and selection continues with smaller tasks. Capacity is never exceeded.
 */
object AutoPlanner {
    fun propose(candidates: List<Candidate>, capacityMin: Int): PlanProposal {
        var remaining = capacityMin.coerceAtLeast(0)
        val selected = ArrayList<Candidate>()
        val doesNotFit = ArrayList<Candidate>()
        val skipped = ArrayList<Candidate>()
        for (candidate in candidates) {
            when {
                !candidate.autoSelectable -> skipped += candidate
                candidate.minutes <= remaining -> {
                    selected += candidate
                    remaining -= candidate.minutes
                }
                else -> doesNotFit += candidate
            }
        }
        return PlanProposal(selected, doesNotFit, skipped, capacityMin.coerceAtLeast(0))
    }
}

/** What changed between a stored draft and a freshly computed proposal (§10.5, step 1). */
data class PlanChange(
    val capacityBeforeMin: Int,
    val capacityAfterMin: Int,
    val addedTaskIds: Set<String>,
    val removedTaskIds: Set<String>,
) {
    val capacityChanged: Boolean get() = capacityBeforeMin != capacityAfterMin
    val hasChanges: Boolean get() = capacityChanged || addedTaskIds.isNotEmpty() || removedTaskIds.isNotEmpty()

    companion object {
        fun between(
            draftCapacityMin: Int,
            draftTaskIds: List<String>,
            proposal: PlanProposal,
        ): PlanChange {
            val fresh = proposal.selected.map { it.task.id }
            return PlanChange(
                capacityBeforeMin = draftCapacityMin,
                capacityAfterMin = proposal.capacityMin,
                addedTaskIds = (fresh - draftTaskIds.toSet()).toSet(),
                removedTaskIds = (draftTaskIds - fresh.toSet()).toSet(),
            )
        }
    }
}
