package app.tasker.core.scheduling.plan

import app.tasker.core.data.maintenance.MaintenanceRunner
import app.tasker.core.data.plan.PlanService
import app.tasker.core.data.review.ReviewService
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.domain.time.DayClock
import app.tasker.core.notifications.GateResult
import app.tasker.core.notifications.NotificationGate
import app.tasker.core.notifications.NotificationRequests
import app.tasker.core.scheduling.SchedulerState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Why no morning notification was sent. */
enum class MorningPlanSkip {
    /** The plan notification is off in the settings. */
    DISABLED,

    /** Not a working day: no morning notification (interpretation 18). */
    NOT_WORK_DAY,

    /** The plan was already accepted by hand (tech plan §12.2). */
    ALREADY_ACCEPTED,

    /** The working day is over: a late plan notification would only be noise. */
    DAY_OVER,

    /** No candidates and nothing to review. */
    NOTHING_TO_PLAN,
}

sealed interface MorningPlanResult {
    data class Submitted(val result: GateResult) : MorningPlanResult

    data class Skipped(val reason: MorningPlanSkip) : MorningPlanResult
}

/**
 * The morning ritual (PLN-4, TTL-5, tech plan §10.5, §12.3): catch up, build or refresh today's draft, then send
 * "Plan is ready: 5 tasks, 4 h of 5 h free, and 3 tasks to review" with the numbers the plan screen will show, because
 * both read the stored draft.
 */
@Singleton
class MorningPlan @Inject constructor(
    private val maintenance: MaintenanceRunner,
    private val plans: PlanService,
    private val review: ReviewService,
    private val settings: SettingsRepository,
    private val clock: DayClock,
    private val gate: NotificationGate,
    private val requests: NotificationRequests,
    private val state: SchedulerState,
) {
    suspend fun run(): MorningPlanResult {
        // Reading the settings first also moves the day clock to the day boundary setting.
        val current = settings.current()
        val day = clock.today()
        // Recorded before any work: a failed run is retried by WorkManager, never re-triggered by the alarm.
        state.lastMorningPlanDay = day
        maintenance.catchUp()
        val skip = when {
            !current.notifyPlan -> MorningPlanSkip.DISABLED
            !current.isWorkDay(day.dayOfWeek.value) -> MorningPlanSkip.NOT_WORK_DAY
            else -> null
        }
        if (skip != null) return MorningPlanResult.Skipped(skip)
        val prepared = plans.prepare(day)
        if (prepared.plan.isAccepted) return MorningPlanResult.Skipped(MorningPlanSkip.ALREADY_ACCEPTED)
        if (prepared.capacity.window == null) return MorningPlanResult.Skipped(MorningPlanSkip.DAY_OVER)
        val items = prepared.plan.activeItems
        val reviewCount = review.observeQueue().first().size
        val doesNotFit = prepared.proposal?.doesNotFit.orEmpty()
        if (items.isEmpty() && doesNotFit.isEmpty() && reviewCount == 0) return MorningPlanResult.Skipped(MorningPlanSkip.NOTHING_TO_PLAN)
        val request = requests.planReady(
            day = day,
            taskCount = items.size,
            plannedMinutes = items.sumOf { it.minutes },
            freeMinutes = prepared.capacity.capacityMin,
            reviewCount = reviewCount,
        )
        return MorningPlanResult.Submitted(gate.submit(request))
    }
}
