package app.tasker.backend.http

import app.tasker.backend.Alerts
import app.tasker.backend.BackendServices
import app.tasker.backend.auth.IntegrityVerdict
import app.tasker.backend.store.CallOutcome
import app.tasker.backend.store.InstallState
import app.tasker.backend.store.Reservation
import app.tasker.backend.store.Verification
import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EnrichRoute
import app.tasker.core.ai.contract.ErrorCodes
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.InstallIds
import app.tasker.core.ai.contract.InstallRequest
import app.tasker.core.ai.contract.InstallResponse
import app.tasker.core.ai.contract.MetricsReport
import app.tasker.core.ai.contract.ModelPricing
import app.tasker.core.ai.contract.ProxyProtocol
import app.tasker.core.ai.contract.RouteResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("app.tasker.backend.routes")

private const val MAX_INSTALL_BODY_BYTES = 16L * 1024
private const val MAX_ENRICH_BODY_BYTES = 32L * 1024
private const val MAX_METRICS_BODY_BYTES = 16L * 1024
private const val MAX_INTEGRITY_TOKEN_LENGTH = 12 * 1024

/** Weekly reports older than this are dropped (the app keeps unsent weeks for a while when offline). */
private const val MAX_METRICS_AGE_WEEKS = 26L
private val UPSTREAM_RETRY_AFTER: Duration = Duration.ofSeconds(30)
private val VERIFICATION_RETRY_AFTER: Duration = Duration.ofSeconds(60)

@Serializable
data class HealthStatus(val status: String)

/** `GET /health` for the PaaS probe: 200 when the database answers, 503 otherwise. */
fun Route.healthRoute(services: BackendServices) {
    get(ProxyProtocol.HEALTH_PATH) {
        if (withContext(Dispatchers.IO) { services.database.isReachable() }) {
            call.respond(HealthStatus("ok"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthStatus("database_unavailable"))
        }
    }
}

/**
 * `POST /v1/install`: verifies Play Integrity (or the dev key) and issues an install token for 30 days.
 * The same install id may install again at any time, e.g. after its token expired.
 */
fun Route.installRoute(services: BackendServices) {
    post(ProxyProtocol.INSTALL_PATH) {
        val request = call.receiveJson(InstallRequest.serializer(), MAX_INSTALL_BODY_BYTES)
        val devKey = call.request.headers[ProxyProtocol.DEV_INSTALL_KEY_HEADER]
        val problems = buildList {
            if (!InstallIds.isValid(request.installId)) add("installId")
            val token = request.integrityToken
            if ((devKey == null && token.isBlank()) || token.length > MAX_INTEGRITY_TOKEN_LENGTH) add("integrityToken")
        }
        if (problems.isNotEmpty()) return@post call.respondInvalid(problems)

        val verification = if (devKey != null) Verification.DEV else Verification.PLAY
        when (val verdict = verifyInstall(services, request, devKey)) {
            IntegrityVerdict.Valid -> {
                val state = withContext(Dispatchers.IO) {
                    services.installs.upsertVerified(request.installId, verification, services.clock.instant())
                }
                if (state == InstallState.BLOCKED) {
                    call.respondError(HttpStatusCode.Forbidden, ErrorCodes.INSTALL_BLOCKED, "This install is blocked")
                } else {
                    val issued = services.tokens.issue(request.installId)
                    call.respond(InstallResponse(issued.token, issued.expiresAt.epochSecond))
                }
            }
            is IntegrityVerdict.Rejected -> {
                log.warn("Install rejected: {}", verdict.reason)
                call.respondError(HttpStatusCode.Forbidden, ErrorCodes.INTEGRITY_FAILED, "Integrity check failed")
            }
            is IntegrityVerdict.Unavailable -> {
                log.warn("Install verification unavailable: {}", verdict.reason)
                call.respondError(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorCodes.UNAVAILABLE,
                    "Verification is temporarily unavailable",
                    VERIFICATION_RETRY_AFTER,
                )
            }
        }
    }
}

private suspend fun verifyInstall(services: BackendServices, request: InstallRequest, devKey: String?): IntegrityVerdict {
    if (devKey != null) {
        return if (services.policy.acceptsDevKey(devKey)) IntegrityVerdict.Valid else IntegrityVerdict.Rejected("dev_key")
    }
    val verifier = services.integrity ?: return IntegrityVerdict.Rejected("play_not_configured")
    return verifier.verify(request.installId, request.integrityToken)
}

/**
 * `POST /v1/enrich` (tech plan §17.3): checks the request, reserves one request of the install's daily limit
 * and of the global budget, runs the model and answers with the validated [EnrichResponse].
 */
fun Route.enrichRoute(services: BackendServices) {
    post(EnrichRoute.PATH) {
        val installId = call.installId()
        val request = call.receiveJson(EnrichRequest.serializer(), MAX_ENRICH_BODY_BYTES)
        val problems = EnrichRoute.requestProblems(request)
        if (problems.isNotEmpty()) return@post call.respondInvalid(problems)

        val now = services.clock.instant()
        val day = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val policy = services.policy
        val reservation = withContext(Dispatchers.IO) {
            services.usage.reserve(installId, day, policy.dailyRequestsPerInstall, policy.dailyBudgetMicroUsd)
        }
        when (reservation) {
            Reservation.GRANTED -> {
                // The provider call is paid for even when the client goes away, so it is always recorded.
                val executed = withContext(Dispatchers.IO + NonCancellable) { EnrichExecution(services).run(installId, day, request) }
                call.attributes.put(CallUsageKey, executed.usage)
                call.respondEnrichResult(executed.result)
            }
            Reservation.UNKNOWN_INSTALL ->
                call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Unknown install: call /v1/install again")
            Reservation.BLOCKED -> call.respondError(HttpStatusCode.Forbidden, ErrorCodes.INSTALL_BLOCKED, "This install is blocked")
            Reservation.DAILY_LIMIT ->
                call.respondError(HttpStatusCode.TooManyRequests, ErrorCodes.DAILY_LIMIT, "Daily request limit reached", untilNextDay(now))
            Reservation.BUDGET_EXHAUSTED ->
                call.respondError(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorCodes.BUDGET_EXHAUSTED,
                    "AI is paused until tomorrow",
                    untilNextDay(now),
                )
        }
    }
}

private suspend fun ApplicationCall.respondEnrichResult(result: RouteResult<EnrichResponse>) {
    when (result) {
        is RouteResult.Success -> respond(result.value)
        is RouteResult.Refused -> respondError(HttpStatusCode.UnprocessableEntity, ErrorCodes.REFUSED, "The model declined this task")
        is RouteResult.Failed -> when {
            result.kind.retryable ->
                respondError(HttpStatusCode.ServiceUnavailable, ErrorCodes.UPSTREAM_BUSY, "The AI provider is busy", UPSTREAM_RETRY_AFTER)
            result.kind in UNUSABLE_OUTPUT ->
                respondError(HttpStatusCode.BadGateway, ErrorCodes.INVALID_OUTPUT, "The model answer was unusable")
            else -> respondError(HttpStatusCode.BadGateway, ErrorCodes.UPSTREAM_ERROR, "The AI provider request failed")
        }
    }
}

private val UNUSABLE_OUTPUT = setOf(FailureKind.INVALID_OUTPUT, FailureKind.TRUNCATED)

/** Failures that point at our configuration (API key, billing, model name, settings) rather than at the request. */
private val CONFIGURATION_FAILURES = setOf(FailureKind.AUTH, FailureKind.BAD_REQUEST)

/** One reserved `/v1/enrich` call: runs the engine, records outcome, tokens and cost, raises alerts. Blocking. */
private class EnrichExecution(private val services: BackendServices) {

    class Executed(val result: RouteResult<EnrichResponse>, val usage: CallUsage)

    fun run(installId: String, day: LocalDate, request: EnrichRequest): Executed {
        val started = services.timeSource.markNow()
        val result = callEngine(request)
        val latencyMs = started.elapsedNow().inWholeMilliseconds
        val usage = result.usage
        val cost = usage?.let(ModelPricing::costMicroUsd) ?: 0L
        val budget = services.policy.dailyBudgetMicroUsd
        val alerts = services.usage.record(installId, day, outcomeOf(result), usage, cost, latencyMs, budget)
        if (alerts.reachedWarning) Alerts.budgetWarning(alerts.spentMicroUsd, budget)
        if (alerts.exhausted) Alerts.budgetExhausted(alerts.spentMicroUsd, budget)
        services.errorRate.record(failed = result is RouteResult.Failed)?.let { Alerts.errorRate(it, services.errorRate.window) }
        if (result is RouteResult.Failed && result.kind in CONFIGURATION_FAILURES) {
            log.error("Enrich call failed with {} ({}): check the API key, billing and route settings", result.kind, result.detail)
        }
        val callUsage = CallUsage(
            model = usage?.model ?: "none",
            inputTokens = usage?.totalInputTokens ?: 0L,
            outputTokens = usage?.totalOutputTokens ?: 0L,
            cacheReadTokens = usage?.totalCacheReadTokens ?: 0L,
            cacheCreationTokens = usage?.totalCacheCreationTokens ?: 0L,
            costMicroUsd = cost,
            outcome = outcomeLabel(result),
        )
        return Executed(result, callUsage)
    }

    /** The engine must not throw; if it does anyway, the call still ends as a recorded failure. */
    private fun callEngine(request: EnrichRequest): RouteResult<EnrichResponse> = try {
        services.enrichEngine.enrich(request)
    } catch (e: RuntimeException) {
        log.error("Enrich engine threw {}", e.javaClass.name)
        RouteResult.Failed(FailureKind.UNKNOWN, detail = e.javaClass.simpleName)
    }

    private fun outcomeOf(result: RouteResult<*>): CallOutcome = when (result) {
        is RouteResult.Success -> CallOutcome.SUCCEEDED
        is RouteResult.Refused -> CallOutcome.REFUSED
        is RouteResult.Failed -> CallOutcome.FAILED
    }

    private fun outcomeLabel(result: RouteResult<*>): String = when (result) {
        is RouteResult.Success -> "succeeded"
        is RouteResult.Refused -> "refused"
        is RouteResult.Failed -> "failed_" + result.kind.name.lowercase()
    }
}

/**
 * `POST /v1/metrics` (tech plan §23): weekly aggregates of the authenticated install, replaced on resend.
 * Answers 204.
 */
fun Route.metricsRoute(services: BackendServices) {
    post(ProxyProtocol.METRICS_PATH) {
        val installId = call.installId()
        val report = call.receiveJson(MetricsReport.serializer(), MAX_METRICS_BODY_BYTES)
        if (report.installId != installId) {
            return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN, "installId does not match the install token")
        }
        val now = services.clock.instant()
        val problems = report.problems().toMutableList()
        if ("weekStart" !in problems && !isRecentWeek(LocalDate.parse(report.weekStart), now)) problems += "weekStart"
        if (problems.isNotEmpty()) return@post call.respondInvalid(problems)

        when (withContext(Dispatchers.IO) { services.installs.state(installId) }) {
            null -> call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Unknown install: call /v1/install again")
            InstallState.BLOCKED -> call.respondError(HttpStatusCode.Forbidden, ErrorCodes.INSTALL_BLOCKED, "This install is blocked")
            InstallState.ACTIVE -> {
                withContext(Dispatchers.IO) { services.metrics.upsert(report, now) }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** A week that has started (in any time zone) and is not older than [MAX_METRICS_AGE_WEEKS]. */
private fun isRecentWeek(weekStart: LocalDate, now: Instant): Boolean {
    val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
    return !weekStart.isAfter(today.plusDays(1)) && !weekStart.isBefore(today.minusWeeks(MAX_METRICS_AGE_WEEKS))
}

/** Time until the next UTC day, when daily limits and the budget reset. */
private fun untilNextDay(now: Instant): Duration {
    val nextDay = LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
    return Duration.between(now, nextDay)
}
