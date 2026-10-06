package app.tasker.core.domain.review

import app.tasker.core.domain.rules.Rules
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.Bucket
import app.tasker.core.model.Project
import app.tasker.core.model.Task
import java.time.LocalDate

/** Explanation on a review card (TTL-4: every card shows its reason). */
sealed interface ReviewReason {
    data class NotTouched(val days: Long, val bucket: Bucket?, val inbox: Boolean) : ReviewReason

    data class ProjectInactive(val days: Long) : ReviewReason

    data object NoNextStep : ReviewReason
}

sealed interface ReviewItem {
    val key: String

    data class TaskCard(val task: Task, val reason: ReviewReason) : ReviewItem {
        override val key: String get() = "task:${task.id}"
    }

    data class ProjectCard(val project: Project, val reason: ReviewReason) : ReviewItem {
        override val key: String get() = "project:${project.id}"
    }
}

/**
 * Review queue (tech plan §11.3): tasks in review, projects in review and projects without a next step.
 * Oldest first, so the queue drains from the longest neglected items.
 */
object ReviewQueueBuilder {
    fun build(
        tasks: Collection<Task>,
        projects: Collection<Project>,
        tasksByProject: Map<String, List<Task>>,
        today: LocalDate,
        clock: DayClock,
    ): List<ReviewItem> {
        val taskCards = tasks.filter { it.inReview }.sortedBy { it.reviewSince ?: it.lastTouchedAt }.map { task ->
            ReviewItem.TaskCard(
                task = task,
                reason = ReviewReason.NotTouched(
                    days = clock.daysSince(task.lastTouchedAt, today),
                    bucket = task.bucket,
                    inbox = task.bucket == null && task.projectId == null,
                ),
            )
        }
        val projectCards = projects.mapNotNull { project ->
            val inactiveDays = clock.daysSince(project.lastActivityAt, today)
            when {
                project.inReview -> ReviewItem.ProjectCard(project, ReviewReason.ProjectInactive(inactiveDays))
                Rules.hasNoNextStep(project, tasksByProject[project.id].orEmpty()) ->
                    ReviewItem.ProjectCard(project, ReviewReason.NoNextStep)
                else -> null
            }
        }.sortedBy { it.project.lastActivityAt }
        return taskCards + projectCards
    }
}
