package app.tasker.core.testing

import app.tasker.core.model.Bucket
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.Project
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

private val counter = AtomicInteger()

/** Test data factory with sensible defaults; every call gets a unique id. */
fun aTask(
    title: String = "Task ${counter.incrementAndGet()}",
    id: String = "task-${counter.incrementAndGet()}",
    status: TaskStatus = TaskStatus.OPEN,
    bucket: Bucket? = null,
    position: Long = 0,
    deadline: Deadline? = null,
    planDate: LocalDate? = null,
    estimate: Estimate? = null,
    projectId: String? = null,
    postponeCount: Int = 0,
    lastTouchedAt: Instant = Instant.parse("2026-10-06T07:00:00Z"),
    createdAt: Instant = lastTouchedAt,
    inReview: Boolean = false,
    reviewSkipStreak: Int = 0,
    carryOverSince: LocalDate? = null,
): Task = Task(
    id = id,
    title = title,
    status = status,
    bucket = bucket,
    position = position,
    deadline = deadline,
    planDate = planDate,
    estimate = estimate,
    projectId = projectId,
    postponeCount = postponeCount,
    lastTouchedAt = lastTouchedAt,
    createdAt = createdAt,
    inReview = inReview,
    reviewSkipStreak = reviewSkipStreak,
    carryOverSince = carryOverSince,
)

fun aProject(
    name: String = "Project ${counter.incrementAndGet()}",
    id: String = "project-${counter.incrementAndGet()}",
    lastActivityAt: Instant = Instant.parse("2026-10-06T07:00:00Z"),
): Project = Project(id = id, name = name, lastActivityAt = lastActivityAt, createdAt = lastActivityAt)
