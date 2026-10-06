package app.tasker.core.data.repository

import app.tasker.core.data.mapper.toEpochDayLong
import app.tasker.core.data.mapper.toInstant
import app.tasker.core.data.mapper.toMillis
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.database.entity.NotificationLogEntity
import app.tasker.core.database.entity.PendingNotificationEntity
import app.tasker.core.domain.notify.NotificationType
import app.tasker.core.domain.time.DayClock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** A notification deferred by quiet hours (NTF-4). */
data class PendingNotification(
    val id: Long,
    val type: NotificationType,
    val key: String,
    val taskId: String?,
    val title: String,
    val text: String,
    val deliverAfter: Instant,
)

/** Daily budget, sent log and the quiet-hours queue of the notification gate (tech plan §12.1). */
@Singleton
class NotificationLedger @Inject constructor(
    private val clock: DayClock,
    db: TaskerDatabase,
) {
    private val dao = db.serviceDao()

    suspend fun budgetUsed(day: LocalDate = clock.today()): Int = dao.budgetUsed(day.toEpochDayLong())

    suspend fun wasSent(key: String): Boolean = dao.wasSent(key)

    suspend fun logSent(type: NotificationType, key: String, taskId: String?, at: Instant = clock.now()) {
        dao.logNotification(
            NotificationLogEntity(
                type = type.name,
                key = key,
                taskId = taskId,
                logicalDay = clock.logicalDay(at).toEpochDayLong(),
                sentAt = at.toMillis(),
                countsInBudget = type.countsInBudget,
            ),
        )
    }

    suspend fun defer(type: NotificationType, key: String, taskId: String?, title: String, text: String, until: Instant) {
        dao.enqueuePending(
            PendingNotificationEntity(
                type = type.name,
                key = key,
                taskId = taskId,
                title = title,
                text = text,
                createdAt = clock.now().toMillis(),
                deliverAfter = until.toMillis(),
            ),
        )
    }

    suspend fun due(now: Instant = clock.now()): List<PendingNotification> = dao.duePending(now.toMillis()).map {
        PendingNotification(it.id, NotificationType.valueOf(it.type), it.key, it.taskId, it.title, it.text, it.deliverAfter.toInstant())
    }

    suspend fun nextDelivery(): Instant? = dao.nextPendingDelivery()?.toInstant()

    suspend fun remove(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.deletePending(ids)
    }

    suspend fun removeForTask(taskId: String) = dao.deletePendingForTask(taskId)
}
