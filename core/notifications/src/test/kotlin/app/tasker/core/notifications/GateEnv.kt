package app.tasker.core.notifications

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tasker.core.data.command.TxRunner
import app.tasker.core.data.effects.CommitEffects
import app.tasker.core.data.repository.NotificationLedger
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.notify.NotificationType
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import app.tasker.core.model.Deadline
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import app.tasker.core.testing.TestTimeSource
import app.tasker.core.testing.aTask
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class TestSettingsStore(initial: AppSettings) : DataStore<AppSettings> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<AppSettings> = state

    override suspend fun updateData(transform: suspend (t: AppSettings) -> AppSettings): AppSettings =
        transform(state.value).also { state.value = it }
}

/** Records what the gate shows instead of talking to NotificationManager. */
class FakePoster : NotificationPoster {
    var permitted = true
    val blocked = mutableSetOf<NotificationType>()
    val shown = mutableListOf<NotificationRequest>()
    val groups = mutableListOf<List<NotificationRequest>>()
    val cancelled = mutableListOf<String>()

    override fun canPost() = permitted

    override fun isChannelEnabled(type: NotificationType) = type !in blocked

    override fun show(request: NotificationRequest) {
        shown += request
    }

    override fun showGroup(requests: List<NotificationRequest>) {
        groups += requests
    }

    override fun cancelTask(taskId: String) {
        cancelled += taskId
    }
}

class FakeQuietHoursAlarm : QuietHoursAlarm {
    var scheduledAt: Instant? = null

    override fun schedule(at: Instant) {
        scheduledAt = at
    }

    override fun cancel() {
        scheduledAt = null
    }
}

/** The gate over an in-memory database and a controllable clock (Tuesday 2026-10-06 10:00, Kyiv). */
class GateEnv(start: LocalDateTime = TestTimeSource.REFERENCE, settings: AppSettings = AppSettings()) {
    val db: TaskerDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TaskerDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val time = TestTimeSource.at(start)
    val clock = DayClock(time)
    val store = TestSettingsStore(settings)
    val settings = SettingsRepository(store, clock)
    val ledger = NotificationLedger(clock, db)
    val tasks = TaskRepository(db)
    val runner = TxRunner(db, clock, this.settings, CommitEffects(emptySet(), CoroutineScope(Dispatchers.Unconfined)))
    val poster = FakePoster()
    val alarm = FakeQuietHoursAlarm()
    val gate = NotificationGate(this.settings, ledger, tasks, clock, poster, Optional.of(alarm))

    fun at(text: String): Instant = LocalDateTime.parse(text).atZone(clock.zone()).toInstant()

    fun setNow(text: String) = time.set(LocalDateTime.parse(text))

    /** A task with a deadline later in the week, so its reminders stay wanted through the tests. */
    suspend fun addTask(title: String = "Task", deadline: Deadline? = Deadline(LocalDate.parse("2026-10-09"))): Task =
        runner.user { insertTask(aTask(title = title, deadline = deadline)) }.value

    suspend fun complete(taskId: String) {
        runner.user { updateTask(task(taskId).copy(status = TaskStatus.DONE, completedAt = now)) }
    }

    fun close() = db.close()
}

fun deadline(taskId: String, key: String = "deadline|$taskId", deliverBy: Instant? = null) =
    NotificationRequest(NotificationType.DEADLINE, key, "Task $taskId", "Deadline tomorrow", taskId, deliverBy)

fun review(key: String) = NotificationRequest(NotificationType.WEEKLY_REVIEW, key, "Weekly review", "Time to look back")

fun plan(key: String) = NotificationRequest(NotificationType.PLAN_READY, key, "Day plan", "Plan is ready")
