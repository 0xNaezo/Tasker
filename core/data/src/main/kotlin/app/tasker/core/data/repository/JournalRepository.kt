package app.tasker.core.data.repository

import app.tasker.core.data.mapper.toMillis
import app.tasker.core.data.mapper.toModel
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.Actor
import app.tasker.core.model.BatchId
import app.tasker.core.model.EntityType
import app.tasker.core.model.Event
import app.tasker.core.model.ReasonCode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One automatic change of one task or project inside a batch. */
data class JournalEntry(val event: Event, val title: String) {
    fun canUndo(now: Instant): Boolean = event.canUndo(now)
}

/**
 * One pass of a rule (one batch, AUT-1): what, with which tasks, when and why. Undo works for the whole batch and for a
 * single entry for 30 days; older batches stay visible without the undo button.
 */
data class JournalBatch(
    val batchId: BatchId,
    val actor: Actor,
    val createdAt: Instant,
    val day: LocalDate,
    val entries: List<JournalEntry>,
) {
    val reasons: List<ReasonCode> get() = entries.mapNotNull { it.event.reason?.code }.distinct()

    /** AI fills are a separate category, collapsed by default (§11.4). */
    val isAi: Boolean get() = actor == Actor.AI

    val undone: Boolean get() = entries.all { it.event.isUndone }

    fun canUndo(now: Instant): Boolean = entries.any { it.canUndo(now) }
}

@Singleton
class JournalRepository @Inject constructor(
    private val clock: DayClock,
    private val db: TaskerDatabase,
) {
    fun observeJournal(history: Duration = DEFAULT_HISTORY): Flow<List<JournalBatch>> {
        val since = clock.now().minus(history)
        return db.eventDao().observeAutomation(since.toMillis()).map { rows -> build(rows.map { it.toModel() }) }
    }

    private suspend fun build(events: List<Event>): List<JournalBatch> {
        val taskTitles = db.taskDao().getWithTags(events.filter { it.entityType == EntityType.TASK }.map { it.entityId }.distinct())
            .associate { it.task.id to it.task.title }
        val projectTitles = db.projectDao().all().associate { it.id to it.name }
        return events.groupBy { it.batchId }.map { (batchId, list) ->
            val first = list.first()
            JournalBatch(
                batchId = batchId,
                actor = first.actor,
                createdAt = first.createdAt,
                day = clock.logicalDay(first.createdAt),
                entries = list.map { event ->
                    val title = when (event.entityType) {
                        EntityType.TASK -> taskTitles[event.entityId]
                        EntityType.PROJECT -> projectTitles[event.entityId]
                        else -> null
                    }
                    JournalEntry(event, title.orEmpty())
                },
            )
        }.sortedByDescending { it.createdAt }
    }

    companion object {
        /** The journal shows the undo window (30 days) and a while beyond it. */
        val DEFAULT_HISTORY: Duration = Duration.ofDays(90)
    }
}
