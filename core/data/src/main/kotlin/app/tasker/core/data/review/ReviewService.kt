package app.tasker.core.data.review

import app.tasker.core.data.command.InvalidCommandException
import app.tasker.core.data.command.ProjectCommands
import app.tasker.core.data.command.TaskCommands
import app.tasker.core.data.command.TxResult
import app.tasker.core.data.mapper.toEpochDayLong
import app.tasker.core.data.mapper.toMillis
import app.tasker.core.data.mapper.toModel
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.database.entity.ReviewCardEntity
import app.tasker.core.database.entity.ReviewSessionEntity
import app.tasker.core.domain.review.ReviewItem
import app.tasker.core.domain.review.ReviewQueueBuilder
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.Bucket
import app.tasker.core.model.EntityType
import app.tasker.core.model.ProjectId
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.ReviewDecision
import app.tasker.core.model.ReviewKind
import app.tasker.core.model.TaskId
import app.tasker.core.model.UuidV7
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Relevance review and quick Inbox triage (TTL-3…TTL-9, tech plan §11.3). A card is recorded in `review_card` the
 * moment it is shown, so skips are counted by rule R4 at the end of the logical day without tracking session ends.
 */
@Singleton
class ReviewService @Inject constructor(
    private val tasks: TaskCommands,
    private val projects: ProjectCommands,
    private val clock: DayClock,
    private val db: TaskerDatabase,
) {
    private val serviceDao = db.serviceDao()

    /** Tasks and projects in review and projects without a next step, oldest first. */
    fun observeQueue(): Flow<List<ReviewItem>> = combine(
        db.taskDao().observeInReview(),
        db.projectDao().observeAll(),
        db.taskDao().observeActiveProjectTasks(),
    ) { inReview, allProjects, projectTasks ->
        val today = clock.today()
        val decidedToday = serviceDao.decidedEntityIds(today.toEpochDayLong()).toSet()
        val tasksByProject = projectTasks.map { it.toModel() }.groupBy { it.projectId.orEmpty() }
        val active = allProjects.map { it.toModel() }.filter { it.status == ProjectStatus.ACTIVE }
        ReviewQueueBuilder.build(inReview.map { it.toModel() }, active, tasksByProject, today, clock)
            .filterNot { it is ReviewItem.ProjectCard && !it.project.inReview && it.project.id in decidedToday }
    }

    suspend fun startSession(kind: ReviewKind): String {
        val now = clock.now()
        val id = UuidV7.generate(now.toEpochMilli())
        serviceDao.upsertSession(ReviewSessionEntity(id, kind, clock.today().toEpochDayLong(), now.toMillis(), null))
        return id
    }

    suspend fun endSession(sessionId: String) {
        serviceDao.endSession(sessionId, clock.now().toMillis())
    }

    /** Records that a card was shown (at most one record per session, entity and day). */
    suspend fun cardShown(sessionId: String, entityType: EntityType, entityId: String) {
        val session = serviceDao.session(sessionId) ?: throw InvalidCommandException("Unknown review session")
        val day = clock.today().toEpochDayLong()
        if (serviceDao.card(sessionId, entityId, day) != null) return
        serviceDao.insertCard(
            ReviewCardEntity(
                sessionId = sessionId,
                kind = session.kind,
                entityType = entityType,
                entityId = entityId,
                logicalDay = day,
                shownAt = clock.now().toMillis(),
                decision = null,
                decidedAt = null,
            ),
        )
    }

    /** Task card decisions (TTL-4): relevant, Someday, archive. One card — one movement. */
    suspend fun decideTask(taskId: TaskId, decision: ReviewDecision): TxResult<*> {
        val result = when (decision) {
            ReviewDecision.RELEVANT -> tasks.confirmRelevant(taskId)
            ReviewDecision.SOMEDAY -> tasks.move(taskId, Bucket.SOMEDAY)
            ReviewDecision.ARCHIVE -> tasks.archive(taskId)
            ReviewDecision.TODAY -> tasks.move(taskId, Bucket.TODAY)
            ReviewDecision.WEEK -> tasks.move(taskId, Bucket.WEEK)
            else -> throw InvalidCommandException("$decision is not a task decision")
        }
        markDecided(taskId, decision)
        return result
    }

    /** Quick Inbox triage (TTL-9): Today, Week, Someday, a project or the archive; skips are not counted here. */
    suspend fun triage(taskId: TaskId, decision: ReviewDecision, projectId: ProjectId? = null): TxResult<*> {
        val result = when (decision) {
            ReviewDecision.PROJECT -> tasks.setProject(taskId, projectId ?: throw InvalidCommandException("Choose a project"))
            else -> return decideTask(taskId, decision)
        }
        markDecided(taskId, decision)
        return result
    }

    /** Project card decisions: relevant, complete, archive (add step goes through [ProjectCommands.addStep]). */
    suspend fun decideProject(projectId: ProjectId, decision: ReviewDecision): TxResult<*> {
        val result = when (decision) {
            ReviewDecision.RELEVANT -> projects.confirmRelevant(projectId)
            ReviewDecision.COMPLETE_PROJECT -> projects.complete(projectId)
            ReviewDecision.ARCHIVE -> projects.archive(projectId)
            else -> throw InvalidCommandException("$decision is not a project decision")
        }
        markDecided(projectId, decision)
        return result
    }

    suspend fun addProjectStep(projectId: ProjectId, text: String): TxResult<*> {
        val result = projects.addStep(projectId, text)
        markDecided(projectId, ReviewDecision.ADD_STEP)
        return result
    }

    private suspend fun markDecided(entityId: String, decision: ReviewDecision) {
        serviceDao.decideCards(entityId, clock.today().toEpochDayLong(), decision.name, clock.now().toMillis())
    }
}
