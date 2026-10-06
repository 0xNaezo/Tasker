package app.tasker.core.data.ai

import app.tasker.core.data.command.TxResult
import app.tasker.core.data.command.TxRunner
import app.tasker.core.data.mapper.toModel
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.provenance.FieldProvenance
import app.tasker.core.domain.similar.SimilarTasks
import app.tasker.core.model.Actor
import app.tasker.core.model.Deadline
import app.tasker.core.model.EnrichState
import app.tasker.core.model.Estimate
import app.tasker.core.model.EventType
import app.tasker.core.model.FieldSource
import app.tasker.core.model.Reason
import app.tasker.core.model.ReasonCode
import app.tasker.core.model.Task
import app.tasker.core.model.TaskField
import app.tasker.core.model.TaskId
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** A task waiting for AI enrichment (CAP-7); [textHash] guards against applying a result to edited text (§9.6). */
data class EnrichmentJob(val task: Task, val textHash: String)

/** A completed task similar to the new one, sent as an example (§17.3); [actualMinutes] only when it was tracked. */
data class SimilarDone(val title: String, val estimate: Estimate?, val actualMinutes: Int?)

/** Values the AI proposes; null fields stay as they are. */
data class AiFill(
    val estimate: Estimate? = null,
    val estimateConfidence: Double? = null,
    val deadline: Deadline? = null,
    val planDate: LocalDate? = null,
)

enum class AiApplyResult { APPLIED, NOTHING_TO_FILL, STALE, NOT_PENDING }

/**
 * Data side of AI enrichment (R10, AI-1, AI-4, tech plan §8.3, §9.6): the result is applied as an AI event with a
 * reason, only to fields the provenance rules allow (empty, AI or DEFAULT), never touching the task.
 */
@Singleton
class AiCommands @Inject constructor(
    private val runner: TxRunner,
    private val db: TaskerDatabase,
) {
    private val taskDao = db.taskDao()

    suspend fun pendingJobs(): List<EnrichmentJob> = taskDao.pendingEnrichment().map { entity ->
        val task = taskDao.getWithTags(entity.id)?.toModel() ?: entity.toModel()
        EnrichmentJob(task, textHash(task))
    }

    suspend fun job(taskId: TaskId): EnrichmentJob? {
        val task = taskDao.getWithTags(taskId)?.toModel() ?: return null
        if (task.enrichState != EnrichState.PENDING) return null
        return EnrichmentJob(task, textHash(task))
    }

    /** Up to [limit] completed tasks lexically similar to [text], picked on the device (§17.3). */
    suspend fun similarDone(text: String, limit: Int = MAX_SIMILAR): List<SimilarDone> {
        val recent = taskDao.recentDone(RECENT_POOL).map { it.toModel() }
        return SimilarTasks.pick(text, recent, limit) { it.title }.map { scored ->
            val task = scored.item
            SimilarDone(task.title, task.estimate, actualMinutes(task))
        }
    }

    suspend fun apply(taskId: TaskId, textHash: String, fill: AiFill): TxResult<AiApplyResult> = runner.run(Actor.AI) {
        val task = taskOrNull(taskId) ?: return@run AiApplyResult.NOT_PENDING
        if (task.enrichState != EnrichState.PENDING) return@run AiApplyResult.NOT_PENDING
        if (textHash(task) != textHash) return@run AiApplyResult.STALE
        var after = task.copy(enrichState = EnrichState.DONE)
        val filled = ArrayList<TaskField>()
        if (fill.estimate != null && FieldProvenance.canWrite(task, TaskField.ESTIMATE, FieldSource.AI)) {
            after = after.copy(estimate = fill.estimate)
            filled += TaskField.ESTIMATE
        }
        if (fill.deadline != null && FieldProvenance.canWrite(task, TaskField.DEADLINE, FieldSource.AI)) {
            after = after.copy(deadline = fill.deadline)
            filled += TaskField.DEADLINE
        }
        if (fill.planDate != null && !fill.planDate.isBefore(today) &&
            FieldProvenance.canWrite(task, TaskField.PLAN_DATE, FieldSource.AI)
        ) {
            after = after.copy(planDate = fill.planDate)
            filled += TaskField.PLAN_DATE
        }
        if (filled.isNotEmpty()) after = FieldProvenance.mark(after, FieldSource.AI, *filled.toTypedArray())
        val params = buildMap {
            put("fields", filled.joinToString(",") { it.name })
            fill.estimateConfidence?.let { put("confidence", "%.2f".format(java.util.Locale.ROOT, it)) }
        }
        updateTask(after, EventType.AI_FILLED, Reason(ReasonCode.AI_ENRICHED, params))
        if (filled.isEmpty()) AiApplyResult.NOTHING_TO_FILL else AiApplyResult.APPLIED
    }

    /** The request failed for good (refusal, invalid answer): the fields simply stay empty (§17.4). */
    suspend fun markFailed(taskId: TaskId): TxResult<Unit> = runner.run(Actor.AI) {
        val task = taskOrNull(taskId) ?: return@run
        if (task.enrichState == EnrichState.PENDING) {
            updateTask(task.copy(enrichState = EnrichState.FAILED), EventType.AI_FILLED, Reason(ReasonCode.AI_ENRICHED))
        }
    }

    /** Turning AI off cancels the queue; fields already filled keep their AI mark (§17.2). */
    suspend fun cancelQueue(): TxResult<Int> = runner.run(Actor.AI) {
        val pending = taskDao.pendingEnrichment().map { it.id }
        tasks(pending).forEach { updateTask(it.copy(enrichState = EnrichState.NONE), EventType.AI_FILLED, Reason(ReasonCode.AI_ENRICHED)) }
        pending.size
    }

    companion object {
        const val MAX_SIMILAR = 5
        private const val RECENT_POOL = 300
        private val MAX_TRACKED = Duration.ofHours(12)

        /** SHA-256 of the text the AI sees. */
        fun textHash(task: Task): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(task.title.encodeToByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** Time from the first start to completion, if it looks like real work time. */
        fun actualMinutes(task: Task): Int? {
            val start = task.startedAt ?: return null
            val end = task.completedAt ?: return null
            val duration = Duration.between(start, end)
            return if (duration.isNegative || duration > MAX_TRACKED) null else duration.toMinutes().toInt()
        }
    }
}
