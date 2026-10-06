package app.tasker.backend

import app.tasker.backend.auth.GoogleAccessTokens
import app.tasker.backend.auth.InstallTokens
import app.tasker.backend.auth.IntegrityVerifier
import app.tasker.backend.auth.PlayIntegrityVerifier
import app.tasker.backend.db.Database
import app.tasker.backend.db.Migrations
import app.tasker.backend.http.INSTALL_AUTH
import app.tasker.backend.http.enrichRoute
import app.tasker.backend.http.healthRoute
import app.tasker.backend.http.installPlugins
import app.tasker.backend.http.installRoute
import app.tasker.backend.http.metricsRoute
import app.tasker.backend.store.InstallStore
import app.tasker.backend.store.MetricsStore
import app.tasker.backend.store.UsageStore
import app.tasker.core.ai.claude.ClaudeRouteRunner
import app.tasker.core.ai.claude.createClient
import app.tasker.core.ai.contract.AiRoute
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import kotlin.system.exitProcess
import kotlin.time.TimeSource
import org.slf4j.LoggerFactory

private const val EXIT_CONFIG_ERROR = 78

/** Entry point of the container (`backend/bin/backend`); configuration comes from the environment only. */
fun main() {
    val log = LoggerFactory.getLogger("app.tasker.backend.Main")
    val config = try {
        BackendConfig.fromEnv(System.getenv())
    } catch (e: ConfigException) {
        log.error(e.message)
        exitProcess(EXIT_CONFIG_ERROR)
    }
    log.info("Starting with {}", config)
    val services = BackendServices.create(config, SystemTimeSource.clock)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(services) }.start(wait = true)
}

/** The HTTP API of tech plan §18.1; [services] are production wiring or test fakes. */
fun Application.module(services: BackendServices) {
    installPlugins(services.tokens, services.clock)
    routing {
        healthRoute(services)
        installRoute(services)
        authenticate(INSTALL_AUTH) {
            enrichRoute(services)
            metricsRoute(services)
        }
    }
    monitor.subscribe(ApplicationStopped) { services.close() }
}

/** Limits and the dev-environment key; the key is compared in constant time. */
class ProxyPolicy(
    val dailyRequestsPerInstall: Int,
    val dailyBudgetMicroUsd: Long,
    devInstallKey: String?,
) {
    private val devKey: ByteArray? = devInstallKey?.toByteArray(Charsets.UTF_8)

    fun acceptsDevKey(candidate: String): Boolean {
        val expected = devKey ?: return false
        return MessageDigest.isEqual(expected, candidate.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Everything the routes need. [integrity] is null when PLAY_PACKAGE_NAME is unset (a dev environment that
 * accepts only DEV_INSTALL_KEY). Tests build it from H2 and fakes; production uses [create].
 */
class BackendServices(
    val clock: Clock,
    val database: Database,
    val tokens: InstallTokens,
    val integrity: IntegrityVerifier?,
    val enrichEngine: EnrichEngine,
    val policy: ProxyPolicy,
    val timeSource: TimeSource = TimeSource.Monotonic,
    val errorRate: ErrorRateMonitor = ErrorRateMonitor(),
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    val installs = InstallStore(database)
    val usage = UsageStore(database)
    val metrics = MetricsStore(database)

    override fun close() = onClose()

    companion object {
        /** The proxy answers the app within its own timeout, so provider calls get one quick retry at most. */
        private val PROVIDER_TIMEOUT: Duration = Duration.ofSeconds(30)
        private const val PROVIDER_MAX_RETRIES = 1

        /** Connects the database, applies migrations and creates the provider clients. */
        fun create(config: BackendConfig, clock: Clock): BackendServices {
            val dataSource = Database.pooled(config.database)
            val database = Database(dataSource)
            Migrations(database).migrate(clock)
            val client = createClient(
                apiKey = config.anthropicApiKey,
                baseUrl = config.anthropicBaseUrl,
                timeout = PROVIDER_TIMEOUT,
                maxRetries = PROVIDER_MAX_RETRIES,
            )
            val integrity = config.playPackageName?.let { packageName ->
                PlayIntegrityVerifier(packageName, GoogleAccessTokens.create(config.googleCredentialsJson), clock)
            }
            return BackendServices(
                clock = clock,
                database = database,
                tokens = InstallTokens(config.installTokenSecret, config.installTokenPreviousSecret, clock),
                integrity = integrity,
                enrichEngine = ClaudeEnrichEngine(ClaudeRouteRunner(client, config.routeSettings(AiRoute.ENRICH))),
                policy = ProxyPolicy(config.dailyRequestsPerInstall, config.dailyBudgetMicroUsd, config.devInstallKey),
                onClose = {
                    client.close()
                    dataSource.close()
                },
            )
        }
    }
}
