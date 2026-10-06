package app.tasker.core.data.maintenance

import app.tasker.core.data.command.Tx
import app.tasker.core.data.command.TxRunner
import app.tasker.core.data.mapper.toEpochDayLong
import app.tasker.core.data.mapper.toMillis
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.database.entity.ActivityDayEntity
import app.tasker.core.database.entity.MaintenanceStateEntity
import app.tasker.core.domain.rules.RuleOutcome
import app.tasker.core.domain.rules.Rules
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.Actor
import app.tasker.core.model.BatchId
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What one catch-up pass did. */
data class CatchUpReport(
    val today: LocalDate,
    val daysClosed: List<LocalDate>,
    val changedTasks: Int,
    val changedProjects: Int,
    val batches: List<BatchId>,
) {
    val changedAnything: Boolean get() = changedTasks > 0 || changedProjects > 0
}

/**
 * Catch-up automation (tech plan §11.1): brings the state to "today" by applying rules R1–R5 for every day that was
 * not processed yet. Safe to repeat: a second pass in a row changes nothing. Runs daily from WorkManager, on app start,
 * after boot, time and zone changes and before the morning plan is built.
 *
 * Every rule pass is one transaction and one batch, so the automation journal shows one row per pass ("12 tasks went
 * to review · not touched longer than their TTL") that can be undone as a whole.
 */
@Singleton
class MaintenanceRunner @Inject constructor(
    private val runner: TxRunner,
    private val clock: DayClock,
    private val settings: SettingsRepository,
    private val db: TaskerDatabase,
) {
    private val mutex = Mutex()
    private val taskDao = db.taskDao()
    private val serviceDao = db.serviceDao()
    private val planDao = db.planDao()

    suspend fun catchUp(): CatchUpReport = mutex.withLock {
        val current = settings.current()
        val today = clock.today()
        val batches = ArrayList<BatchId>()
        var tasks = 0
        var projects = 0
        fun count(result: app.tasker.core.data.command.TxResult<*>) {
            result.batchId?.let { batches += it }
            tasks += result.info.taskIds.size
            projects += result.info.projectIds.size
        }

        // R1 and R4 (skips) close every finished logical day exactly once; each keeps its own mark.
        val closed = pendingDays(DAY_END_MARK, today)
        for (day in closed) count(runner.run(Actor.RULE) { closeDay(this, day) })
        for (day in pendingDays(SKIPS_MARK, today)) count(runner.run(Actor.RULE) { countReviewSkips(this, day) })

        // R2: plan date passed, once per plan date.
        count(
            runner.run(Actor.RULE) {
                val due = taskDao.planDatePassed(today.toEpochDayLong()).map { it.id }
                tasks(due).mapNotNull { Rules.planDatePassed(it, today) }.forEach { apply(it) }
            },
        )
        // R4: two skips in a row archive the task, unless its deadline is still ahead (TTL-6, TTL-7).
        count(
            runner.run(Actor.RULE) {
                val due = taskDao.autoArchiveCandidates(Rules.SKIPS_TO_ARCHIVE).map { it.id }
                tasks(due).mapNotNull { Rules.autoArchive(it, now, today, clock) }.forEach { apply(it) }
            },
        )
        // R3: TTL expired → review queue, never the archive (TTL-1…TTL-3).
        count(
            runner.run(Actor.RULE) {
                val minTtl = minOf(current.ttl.inbox, current.ttl.todayWeek, current.ttl.someday).coerceAtLeast(1)
                val touchedBefore = clock.dayStart(today.minusDays((minTtl - 1).toLong()))
                val due = taskDao.ttlCandidates(touchedBefore.toMillis()).map { it.id }
                tasks(due).mapNotNull { Rules.ttlExpired(it, today, now, current, clock) }.forEach { apply(it) }
            },
        )
        // R5: active project without activity → review; projects are never archived automatically.
        count(
            runner.run(Actor.RULE) {
                db.projectDao().active().mapNotNull { entity ->
                    projectOrNull(entity.id)?.let { Rules.projectInactive(it, today, now, current, clock) }
                }.forEach { outcome -> updateProject(outcome.after, outcome.eventType, outcome.reason) }
            },
        )
        setMark(LAST_RUN_MARK, clock.now().toString())
        CatchUpReport(today, closed, tasks, projects, batches)
    }

    /** R1 for the logical day [day] (PLN-10, §10.6). */
    private suspend fun closeDay(tx: Tx, day: LocalDate) = with(tx) {
        val plan = plan(day)
        val planTasks = tasks(plan?.items.orEmpty().map { it.taskId }).associateBy { it.id }
        val todayBucket = tasks(taskDao.todayBucket().map { it.id })
        val result = Rules.endOfDay(
            day = day,
            plan = plan,
            tasksById = planTasks,
            todayBucketTasks = todayBucket,
            userWasActive = serviceDao.wasActive(day.toEpochDayLong()),
            isWorkDay = tx.settings.isWorkDay(day.dayOfWeek.value),
        )
        if (plan != null && result.itemOutcomes.isNotEmpty()) {
            savePlan(
                plan.copy(
                    items = plan.items.map { item ->
                        result.itemOutcomes[item.taskId]?.let { item.copy(outcome = it) } ?: item
                    },
                ),
            )
        }
        result.taskOutcomes.forEach { apply(it) }
        setMarkIn(DAY_END_MARK, day)
    }

    /** R4, part 1: a card shown on [day] without a decision adds one skip (interpretation 10). */
    private suspend fun countReviewSkips(tx: Tx, day: LocalDate) = with(tx) {
        val key = day.toEpochDayLong()
        val shown = serviceDao.shownTaskIds(key)
        val decided = serviceDao.decidedTaskIds(key).toSet()
        tasks(shown).mapNotNull { Rules.reviewSkipped(it, day, shownOnDay = true, decidedOnDay = it.id in decided) }
            .forEach { apply(it) }
        setMarkIn(SKIPS_MARK, day)
    }

    /**
     * Finished days after the mark. Without a mark (first run, or data restored without marks) processing starts from
     * the first day the app was used, so nothing before the user's own history is touched.
     */
    private suspend fun pendingDays(key: String, today: LocalDate): List<LocalDate> {
        val last = mark(key)?.let(LocalDate::parse) ?: run {
            val firstActive = serviceDao.firstActivityDay()?.let { LocalDate.ofEpochDay(it) }
            minOf(today.minusDays(1), firstActive?.minusDays(1) ?: today).also { setMark(key, it) }
        }
        // Days without a plan and without opening the app change nothing, so a very long absence is capped.
        val first = maxOf(last.plusDays(1), today.minusDays(MAX_CATCH_UP_DAYS))
        return generateSequence(first) { it.plusDays(1) }.takeWhile { it.isBefore(today) }.toList()
    }

    private suspend fun Tx.apply(outcome: RuleOutcome) {
        updateTask(outcome.after, outcome.eventType, outcome.reason)
    }

    /** The user opened the app today: the end-of-day rule counts this day (§10.6, `activity_day`). */
    suspend fun recordActivity() {
        val now = clock.now()
        serviceDao.insertActivityDay(ActivityDayEntity(clock.logicalDay(now).toEpochDayLong(), now.toMillis()))
    }

    private suspend fun mark(key: String): String? = serviceDao.mark(key)

    private suspend fun setMark(key: String, day: LocalDate) = serviceDao.setMark(MaintenanceStateEntity(key, day.toString()))

    private suspend fun setMark(key: String, value: String) = serviceDao.setMark(MaintenanceStateEntity(key, value))

    private suspend fun setMarkIn(key: String, day: LocalDate) = setMark(key, day)

    companion object {
        /** Last logical day closed by R1 and the skip count of R4. */
        const val DAY_END_MARK = "day_end_processed"
        const val SKIPS_MARK = "review_skips_processed"
        const val LAST_RUN_MARK = "last_catch_up"
        private const val MAX_CATCH_UP_DAYS = 366L
    }
}
