package app.tasker.core.data.repository

import app.tasker.core.database.TaskerDatabase
import app.tasker.core.database.entity.MaintenanceStateEntity
import app.tasker.core.domain.time.DayClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Offers that come at most once a day (TTL-5, TTL-9, principle 2). */
enum class DailyPrompt(val key: String) {
    REVIEW("prompt.review"),
    INBOX_TRIAGE("prompt.inbox_triage"),
}

@Singleton
class DailyPrompts @Inject constructor(
    private val clock: DayClock,
    db: TaskerDatabase,
) {
    private val dao = db.serviceDao()

    /** True when the prompt was already shown and dismissed today. */
    fun observeDismissed(prompt: DailyPrompt): Flow<Boolean> =
        dao.observeMark(prompt.key).map { it == clock.today().toString() }

    suspend fun dismiss(prompt: DailyPrompt) {
        dao.setMark(MaintenanceStateEntity(prompt.key, clock.today().toString()))
    }
}
