package app.tasker.backend.http

import app.tasker.backend.auth.InstallTokens
import app.tasker.core.ai.contract.AiJson
import app.tasker.core.ai.contract.EnrichRoute
import app.tasker.core.ai.contract.ErrorCodes
import app.tasker.core.ai.contract.InstallIds
import app.tasker.core.ai.contract.ProxyProtocol
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import java.time.Clock
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/** Name of the bearer-token authentication of `/v1/enrich` and `/v1/metrics`. */
const val INSTALL_AUTH = "install-token"

/** The install a valid install token belongs to. */
data class InstallPrincipal(val installId: String)

const val ACCESS_LOGGER_NAME = "app.tasker.backend.access"
private val accessLog = LoggerFactory.getLogger(ACCESS_LOGGER_NAME)
private val errorLog = LoggerFactory.getLogger("app.tasker.backend.errors")

private val KNOWN_PATHS = setOf(ProxyProtocol.HEALTH_PATH, ProxyProtocol.INSTALL_PATH, ProxyProtocol.METRICS_PATH, EnrichRoute.PATH)

fun Application.installPlugins(tokens: InstallTokens, clock: Clock) {
    install(ContentNegotiation) { json(AiJson.wire) }
    install(CallLogging) {
        logger = accessLog
        level = Level.INFO
        disableDefaultColors()
        clock { clock.millis() }
        format { call -> accessLogLine(call, call.processingTimeMillis { clock.millis() }) }
    }
    install(StatusPages) {
        exception<InvalidRequestException> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_REQUEST, cause.message ?: "Invalid request")
        }
        exception<BodyTooLargeException> { call, _ ->
            call.respondError(HttpStatusCode.PayloadTooLarge, ErrorCodes.PAYLOAD_TOO_LARGE, "Request body too large")
        }
        exception<BadRequestException> { call, _ ->
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_REQUEST, "Malformed request")
        }
        exception<Throwable> { call, cause ->
            errorLog.error("Unhandled error on {}", routeLabel(call), SanitizedError(cause))
            call.respondError(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL, "Internal error")
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such route")
        }
    }
    install(Authentication) {
        jwt(INSTALL_AUTH) {
            realm = REALM
            verifier { header -> (header as? HttpAuthHeader.Single)?.blob?.let(tokens::verifierFor) }
            validate { credential -> credential.payload.subject?.takeIf(InstallIds::isValid)?.let(::InstallPrincipal) }
            challenge { _, _ ->
                call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Missing, invalid or expired install token")
            }
        }
    }
}

/** Install id of the authenticated call; only valid inside `authenticate(INSTALL_AUTH)`. */
fun ApplicationCall.installId(): String = checkNotNull(principal<InstallPrincipal>()) { "Route is not authenticated" }.installId

/**
 * One access-log line (tech plan §18.1): route, status, latency, and for model calls the outcome, model,
 * tokens and cost. Never bodies, headers, query strings or install ids; unknown paths are logged as "other"
 * because a path can carry arbitrary text.
 */
internal fun accessLogLine(call: ApplicationCall, latencyMs: Long): String = buildString {
    val method = call.request.httpMethod
    append(if (method in HttpMethod.DefaultMethods) method.value else "OTHER")
    append(" route=").append(routeLabel(call))
    append(" status=").append(call.response.status()?.value ?: 0)
    append(" latencyMs=").append(latencyMs)
    call.attributes.getOrNull(CallUsageKey)?.let { usage ->
        append(" outcome=").append(usage.outcome)
        append(" model=").append(usage.model)
        append(" inputTokens=").append(usage.inputTokens)
        append(" outputTokens=").append(usage.outputTokens)
        append(" cacheReadTokens=").append(usage.cacheReadTokens)
        append(" cacheCreationTokens=").append(usage.cacheCreationTokens)
        append(" costMicroUsd=").append(usage.costMicroUsd)
    }
}

private fun routeLabel(call: ApplicationCall): String = call.request.path().takeIf { it in KNOWN_PATHS } ?: "other"

/**
 * Stack trace of an unexpected error without exception messages: messages can quote request content
 * (JSON parser errors do), and task texts must never reach the logs.
 */
private class SanitizedError(cause: Throwable) :
    RuntimeException(generateSequence(cause) { it.cause }.take(MAX_CAUSES).joinToString(" <- ") { it.javaClass.name }) {
    init {
        stackTrace = cause.stackTrace
    }

    private companion object {
        const val MAX_CAUSES = 5
    }
}

private const val REALM = "tasker"
