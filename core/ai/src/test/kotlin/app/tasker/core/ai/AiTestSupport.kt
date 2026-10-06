package app.tasker.core.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.ai.contract.RouteUsage
import app.tasker.core.data.ai.AiCommands
import app.tasker.core.data.command.CaptureRequest
import app.tasker.core.data.command.CaptureService
import app.tasker.core.data.command.ParserProvider
import app.tasker.core.data.command.TaskCommands
import app.tasker.core.data.command.TxRunner
import app.tasker.core.data.effects.CommitEffects
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AiMode
import app.tasker.core.model.AiSettings
import app.tasker.core.model.AppSettings
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.Task
import app.tasker.core.testing.TestTimeSource
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory settings store for tests. */
class InMemoryDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()

    override val data: Flow<T> = state

    override suspend fun updateData(transform: suspend (t: T) -> T): T = mutex.withLock {
        transform(state.value).also { state.value = it }
    }
}

/** The data layer wired by hand on an in-memory database and a controllable clock (as in core:data's tests). */
class AiTestEnv(
    settings: AppSettings = AI_ON,
    start: LocalDateTime = TestTimeSource.REFERENCE,
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: TaskerDatabase = Room.inMemoryDatabaseBuilder(context, TaskerDatabase::class.java).allowMainThreadQueries().build()
    val time = TestTimeSource.at(start)
    val clock = DayClock(time)
    val store = InMemoryDataStore(settings)
    val settings = SettingsRepository(store, clock)
    val effects = CommitEffects(emptySet(), CoroutineScope(Dispatchers.Unconfined))
    val runner = TxRunner(db, clock, this.settings, effects)
    val parsers = ParserProvider()
    val capture = CaptureService(runner, clock, this.settings, parsers, db)
    val tasks = TaskCommands(runner, capture, db)
    val ai = AiCommands(runner, db)
    val repo = TaskRepository(db)
    val requests = EnrichRequestFactory(clock, this.settings, parsers, ai)

    /** Captures [text]; the clock moves one second on, so tasks keep their capture order. */
    suspend fun add(text: String): Task = capture.capture(CaptureRequest(text, CaptureChannel.BAR)).value.task
        .also { time.advance(Duration.ofSeconds(1)) }

    suspend fun task(id: String): Task = checkNotNull(repo.task(id)) { "Task $id is missing" }

    fun close() = db.close()

    companion object {
        val DIRECT_ONLY = AiEnvironment(directAvailable = true, proxyAvailable = false, proxyBaseUrl = null)
        val PROXY_ONLY = AiEnvironment(directAvailable = false, proxyAvailable = true, proxyBaseUrl = "https://proxy.test")

        val AI_ON = AppSettings(
            parserLanguages = setOf("ru", "uk", "en"),
            ai = AiSettings(enabled = true, consentAtEpochMillis = 1L, mode = AiMode.DIRECT),
        )
        val AI_OFF = AppSettings(parserLanguages = setOf("ru", "uk", "en"))
    }
}

val TEST_USAGE = RouteUsage(model = "test", inputTokens = 1, outputTokens = 1)

fun success(response: EnrichResponse = EnrichResponse()): RouteResult<EnrichResponse> = RouteResult.Success(response, TEST_USAGE)

/**
 * Records every request and answers with [answer] when set, otherwise from a queue of canned results, then with
 * [fallback]. [onCall] runs before answering, e.g. to change the world while a request is "in flight".
 */
class FakeGateway(vararg results: RouteResult<EnrichResponse>) : AiGateway {
    val requests = mutableListOf<EnrichRequest>()
    private val queue = ArrayDeque(results.toList())
    var fallback: RouteResult<EnrichResponse> = success()
    var answer: ((EnrichRequest) -> RouteResult<EnrichResponse>)? = null
    var onCall: (suspend (EnrichRequest) -> Unit)? = null

    override suspend fun enrich(request: EnrichRequest): RouteResult<EnrichResponse> {
        requests += request
        onCall?.invoke(request)
        return answer?.invoke(request) ?: queue.removeFirstOrNull() ?: fallback
    }
}

class FakeScheduler : EnrichmentScheduler {
    val enqueued = AtomicInteger()
    val cancelled = AtomicInteger()

    override suspend fun enqueue() {
        enqueued.incrementAndGet()
    }

    override suspend fun cancel() {
        cancelled.incrementAndGet()
    }
}

/** A plain JVM AEAD in place of the Keystore-wrapped one. */
fun testAead(): Aead {
    AeadConfig.register()
    return KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
}

fun testVault(directory: File, aead: Aead = testAead()): SecretVault = SecretVault(directory, { aead }, Dispatchers.Unconfined)

fun gatewayProvider(
    env: AiTestEnv,
    keys: ApiKeyStore,
    direct: AiGateway = FakeGateway(),
    proxy: AiGateway = FakeGateway(),
    environment: AiEnvironment = AiTestEnv.DIRECT_ONLY,
): AiGatewayProvider = AiGatewayProvider(environment, env.settings, keys, Provider { direct }, Provider { proxy })
