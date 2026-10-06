package app.tasker.core.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tasker.core.data.command.CaptureRequest
import app.tasker.core.data.command.CaptureService
import app.tasker.core.data.command.ParserProvider
import app.tasker.core.data.command.ProjectCommands
import app.tasker.core.data.command.TaskCommands
import app.tasker.core.data.command.TxRunner
import app.tasker.core.data.effects.CommitEffects
import app.tasker.core.data.export.DataExporter
import app.tasker.core.data.maintenance.MaintenanceRunner
import app.tasker.core.data.plan.PlanService
import app.tasker.core.data.repository.BusyTimeRepository
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.review.ReviewService
import app.tasker.core.data.search.SearchRepository
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.capacity.CapacityCalculator
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.Task
import app.tasker.core.testing.TestTimeSource
import java.time.LocalDateTime
import java.util.Optional
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Copied from core/data/src/test/.../DataEnv.kt: the data layer wired by hand for Robolectric tests.

/** In-memory settings store for tests. */
class InMemoryDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()

    override val data: Flow<T> = state

    override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock {
        transform(state.value).also { state.value = it }
    }
}

/** The data layer wired by hand on top of an in-memory database and a controllable clock. */
class DataEnv(
    start: LocalDateTime = TestTimeSource.REFERENCE,
    settings: AppSettings = AppSettings(parserLanguages = setOf("ru", "uk", "en")),
    val db: TaskerDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TaskerDatabase::class.java,
    ).allowMainThreadQueries().build(),
) {
    val time = TestTimeSource.at(start)
    val clock = DayClock(time)
    val store = InMemoryDataStore(settings)
    val settings = SettingsRepository(store, clock)
    val effects = CommitEffects(emptySet(), CoroutineScope(Dispatchers.Unconfined))
    val runner = TxRunner(db, clock, this.settings, effects)
    val parsers = ParserProvider()
    val capture = CaptureService(runner, clock, this.settings, parsers, db)
    val tasks = TaskCommands(runner, capture, db)
    val projects = ProjectCommands(runner, tasks, capture, db)
    val calculator = CapacityCalculator(clock)
    val busy = BusyTimeRepository(Optional.empty(), calculator)
    val plans = PlanService(runner, clock, this.settings, busy, db)
    val review = ReviewService(tasks, projects, clock, db)
    val maintenance = MaintenanceRunner(runner, clock, this.settings, db)
    val repo = TaskRepository(db)
    val search = SearchRepository(db)
    val exporter = DataExporter(db, this.settings, clock)

    suspend fun add(text: String, channel: CaptureChannel = CaptureChannel.BAR): Task =
        capture.capture(CaptureRequest(text, channel)).value.task

    fun advanceDays(days: Long) = time.advanceDays(days)

    fun close() = db.close()
}
