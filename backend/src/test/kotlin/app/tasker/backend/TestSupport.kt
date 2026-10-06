package app.tasker.backend

import app.tasker.backend.auth.InstallTokens
import app.tasker.backend.auth.IntegrityVerdict
import app.tasker.backend.auth.IntegrityVerifier
import app.tasker.backend.db.Database
import app.tasker.backend.db.Migrations
import app.tasker.core.ai.contract.AiJson
import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EnrichRoute
import app.tasker.core.ai.contract.EstimateScale
import app.tasker.core.ai.contract.ExtractedFields
import app.tasker.core.ai.contract.InstallRequest
import app.tasker.core.ai.contract.InstallResponse
import app.tasker.core.ai.contract.ProxyProtocol
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.ai.contract.RouteUsage
import app.tasker.core.ai.contract.SimilarTask
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.TestTimeSource
import kotlinx.coroutines.delay
import org.h2.jdbcx.JdbcDataSource
import org.slf4j.LoggerFactory

/** Tuesday 2026-10-06 10:00 UTC: 14 hours before the next UTC day. */
val TEST_NOW: Instant = Instant.parse("2026-10-06T10:00:00Z")
const val TEST_SECRET = "test-install-token-secret-0123456789abcdef"
const val TEST_DEV_KEY = "dev-install-key-0123456789"
const val INSTALL_ID = "3f2b6c1e-8a4d-4b7e-9c0f-1a2b3c4d5e6f"

class MutableClock(var instant: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)

    override fun instant(): Instant = instant
}

/** A fresh in-memory H2 database in PostgreSQL mode, migrated. */
fun h2Database(clock: Clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC)): Database {
    val dataSource = JdbcDataSource().apply {
        setURL(
            "jdbc:h2:mem:test-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        )
    }
    return Database(dataSource).also { Migrations(it).migrate(clock) }
}

fun enrichRequest(text: String = "сдать отчёт до пятницы", similar: String = "сдать отчёт за сентябрь") = EnrichRequest(
    text = text,
    language = "ru",
    now = "2026-10-06T13:00",
    today = "2026-10-06",
    timeZone = "Europe/Kyiv",
    estimateScale = EstimateScale(15, 60, 180),
    extracted = ExtractedFields(),
    similarDone = listOf(SimilarTask(similar, "M", 75)),
)

/** 1000 input + 300 output tokens of claude-opus-5-5: $0.004 + $0.006 = 10 000 micro-USD. */
val OPUS_USAGE = RouteUsage(model = "claude-opus-5-5", inputTokens = 1000, outputTokens = 300, cacheReadTokens = 0)
const val OPUS_USAGE_COST = 10_000L

class FakeEnrichEngine : EnrichEngine {
    val requests = CopyOnWriteArrayList<EnrichRequest>()

    @Volatile
    var answer: (EnrichRequest) -> RouteResult<EnrichResponse> = {
        RouteResult.Success(EnrichResponse(estimate = "M", estimateConfidence = 0.7), OPUS_USAGE)
    }

    override fun enrich(request: EnrichRequest): RouteResult<EnrichResponse> {
        requests += request
        return answer(request)
    }
}

/** The backend wired with H2, a fake model and a fake Play Integrity verifier. */
class TestBackend(
    dailyRequestsPerInstall: Int = 5,
    dailyBudgetMicroUsd: Long = 1_000_000,
    devInstallKey: String? = TEST_DEV_KEY,
    playConfigured: Boolean = true,
) {
    val clock = MutableClock(TEST_NOW)
    val timeSource = TestTimeSource()
    val database = h2Database(clock)
    val engine = FakeEnrichEngine()
    val tokens = InstallTokens(TEST_SECRET, null, clock)
    val integrityCalls = CopyOnWriteArrayList<Pair<String, String>>()

    @Volatile
    var verdict: IntegrityVerdict = IntegrityVerdict.Valid

    val services = BackendServices(
        clock = clock,
        database = database,
        tokens = tokens,
        integrity = if (playConfigured) {
            IntegrityVerifier { installId, token ->
                integrityCalls += installId to token
                verdict
            }
        } else {
            null
        },
        enrichEngine = engine,
        policy = ProxyPolicy(dailyRequestsPerInstall, dailyBudgetMicroUsd, devInstallKey),
        timeSource = timeSource,
    )

    fun test(block: suspend ApplicationTestBuilder.(client: HttpClient) -> Unit) = testApplication {
        application { module(services) }
        val client = createClient { install(ContentNegotiation) { json(AiJson.wire) } }
        block(client)
    }
}

suspend fun HttpClient.installRequest(
    installId: String = INSTALL_ID,
    integrityToken: String = "play-integrity-token",
    devKey: String? = null,
): HttpResponse = post(ProxyProtocol.INSTALL_PATH) {
    contentType(ContentType.Application.Json)
    devKey?.let { header(ProxyProtocol.DEV_INSTALL_KEY_HEADER, it) }
    setBody(InstallRequest(installId, integrityToken))
}

/** Installs through the fake Play Integrity verifier and returns the install token. */
suspend fun HttpClient.installToken(installId: String = INSTALL_ID): String {
    val response = installRequest(installId)
    check(response.status == HttpStatusCode.OK) { "install failed: ${response.status}" }
    return response.body<InstallResponse>().token
}

suspend fun HttpClient.enrich(token: String?, request: EnrichRequest = enrichRequest()): HttpResponse = post(EnrichRoute.PATH) {
    token?.let { bearerAuth(it) }
    contentType(ContentType.Application.Json)
    setBody(request)
}

/** Collects every log event of the process while open (logback root logger). */
class LogCapture : AutoCloseable {
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = object : AppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            events += event
        }
    }
    val events = CopyOnWriteArrayList<ILoggingEvent>()

    init {
        appender.start()
        root.addAppender(appender)
    }

    /** Message, exception class names, messages and stack frames of every event. */
    fun fullText(): String = events.joinToString("\n") { event ->
        buildString {
            append(event.loggerName).append(' ').append(event.formattedMessage)
            var proxy = event.throwableProxy
            while (proxy != null) {
                append('\n').append(proxy.className).append(": ").append(proxy.message)
                proxy.stackTraceElementProxyArray.forEach { append('\n').append(it.steAsString) }
                proxy = proxy.cause
            }
        }
    }

    /** Waits for an event matching [predicate]: the access log is written after the response is sent. */
    suspend fun await(timeoutMs: Long = 5_000, predicate: (ILoggingEvent) -> Boolean): ILoggingEvent {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            events.firstOrNull(predicate)?.let { return it }
            delay(10)
        }
        error("No matching log event; got:\n${fullText()}")
    }

    override fun close() {
        root.detachAppender(appender)
        appender.stop()
    }
}
