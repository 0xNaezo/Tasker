package app.tasker.core.scheduling

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.tasker.core.data.command.CaptureService
import app.tasker.core.data.command.ParserProvider
import app.tasker.core.data.command.ProjectCommands
import app.tasker.core.data.command.TaskCommands
import app.tasker.core.data.command.TxResult
import app.tasker.core.data.command.TxRunner
import app.tasker.core.data.effects.CommitEffects
import app.tasker.core.data.maintenance.MaintenanceRunner
import app.tasker.core.data.plan.PlanService
import app.tasker.core.data.repository.BusyTimeRepository
import app.tasker.core.data.repository.NotificationLedger
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.review.ReviewService
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.capacity.CapacityCalculator
import app.tasker.core.domain.notify.NotificationType
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import app.tasker.core.model.Bucket
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.ReminderOffsets
import app.tasker.core.model.Task
import app.tasker.core.notifications.NotificationGate
import app.tasker.core.notifications.NotificationPoster
import app.tasker.core.notifications.NotificationRequest
import app.tasker.core.notifications.NotificationRequests
import app.tasker.core.scheduling.plan.MorningPlan
import app.tasker.core.scheduling.reminder.ReminderCommitListener
import app.tasker.core.scheduling.reminder.ReminderScheduler
import app.tasker.core.testing.TestTimeSource
import app.tasker.core.testing.aTask
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class TestSettingsStore(initial: AppSettings) : DataStore<AppSettings> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<AppSettings> = state

    override suspend fun updateData(transform: suspend (t: AppSettings) -> AppSettings): AppSettings =
        transform(state.value).also { state.value = it }
}

/** Records what would be shown instead of talking to NotificationManager. */
class FakePoster : NotificationPoster {
    val shown = mutableListOf<NotificationRequest>()
    val groups = mutableListOf<List<NotificationRequest>>()
    val cancelled = mutableListOf<String>()

    override fun canPost() = true

    override fun isChannelEnabled(type: NotificationType) = true

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

class FakeAlarms : Alarms {
    /** Written from the scheduler's coroutines and read by the test thread. */
    val windows: MutableMap<AlarmKind, AlarmWindow> = ConcurrentHashMap()

    override fun set(kind: AlarmKind, window: AlarmWindow) {
        windows[kind] = window
    }

    override fun cancel(kind: AlarmKind) {
        windows.remove(kind)
    }
}

/** Succeeds at once: enqueued work is observed through its WorkInfo without running the real workers. */
class NoOpWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}

/** The data layer, the gate and the scheduling classes wired by hand over an in-memory database and a test clock. */
class SchedulingEnv(start: LocalDateTime = TestTimeSource.REFERENCE, settings: AppSettings = AppSettings()) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: TaskerDatabase = Room.inMemoryDatabaseBuilder(context, TaskerDatabase::class.java).allowMainThreadQueries().build()
    val time = TestTimeSource.at(start)
    val clock = DayClock(time)
    val store = TestSettingsStore(settings)
    val settings = SettingsRepository(store, clock)
    val ledger = NotificationLedger(clock, db)
    val tasks = TaskRepository(db)
    val poster = FakePoster()
    val alarms = FakeAlarms()
    val gate = NotificationGate(this.settings, ledger, tasks, clock, poster, Optional.of(QuietHoursEndAlarm(alarms)))
    val requests = NotificationRequests(context, clock)
    val state = SchedulerState(context)
    val reminders = ReminderScheduler(tasks, this.settings, clock, gate, requests, alarms, state)
    val reminderListener = ReminderCommitListener(reminders)
    val runner = TxRunner(db, clock, this.settings, CommitEffects(emptySet(), CoroutineScope(Dispatchers.Unconfined)))
    val capture = CaptureService(runner, clock, this.settings, ParserProvider(), db)
    val commands = TaskCommands(runner, capture, db)
    val projects = ProjectCommands(runner, commands, capture, db)
    val plans = PlanService(runner, clock, this.settings, BusyTimeRepository(Optional.empty(), CapacityCalculator(clock)), db)
    val review = ReviewService(commands, projects, clock, db)
    val maintenance = MaintenanceRunner(runner, clock, this.settings, db)
    val morningPlan = MorningPlan(maintenance, plans, review, this.settings, clock, gate, requests, state)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Deadline texts below use the 24-hour clock.
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
    }

    private var workManagerStarted = false

    /** Test WorkManager on synchronous executors and the test clock; its workers succeed without doing anything. */
    val workManager: WorkManager by lazy {
        workManagerStarted = true
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setClock { time.now().toEpochMilli() }
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        NoOpWorker(appContext, workerParameters)
                },
            )
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        WorkManager.getInstance(context)
    }

    val scheduler: Scheduler by lazy {
        Scheduler(context, scope, { workManager }, this.settings, clock, alarms, reminders, gate, state)
    }

    fun at(text: String): Instant = LocalDateTime.parse(text).atZone(clock.zone()).toInstant()

    fun setNow(text: String) = time.set(LocalDateTime.parse(text))

    /** Creates a task and lets the reminder listener see the commit, as the post-commit effects would. */
    suspend fun addTask(
        title: String,
        deadline: Deadline? = null,
        bucket: Bucket? = null,
        estimate: Estimate? = null,
        planDate: LocalDate? = null,
        reminders: ReminderOffsets? = null,
    ): Task {
        val task = aTask(title = title, deadline = deadline, bucket = bucket, estimate = estimate, planDate = planDate)
        return commit { runner.user { insertTask(task.copy(reminderOffsets = reminders)) } }
    }

    /** Runs a command and its reminder post-commit effect, which the real app runs on the application scope. */
    suspend fun <T> commit(command: suspend () -> TxResult<T>): T {
        val result = command()
        reminderListener.onCommitted(result.info)
        return result.value
    }

    fun close() {
        scope.cancel()
        if (workManagerStarted) WorkManagerTestInitHelper.closeWorkDatabase()
        db.close()
    }
}

/** Waits for work that runs on the scheduler's own scope. */
fun eventually(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Condition not met within $timeoutMillis ms" }
        Thread.sleep(10)
    }
}
