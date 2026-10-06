package app.tasker.core.ai

import android.util.Log
import app.tasker.core.ai.contract.AiJson
import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EnrichRoute
import app.tasker.core.ai.contract.ErrorBody
import app.tasker.core.ai.contract.ErrorCodes
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.InstallRequest
import app.tasker.core.ai.contract.InstallResponse
import app.tasker.core.ai.contract.IntegrityBinding
import app.tasker.core.ai.contract.MetricsReport
import app.tasker.core.ai.contract.ProxyProtocol
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.ai.contract.RouteUsage
import app.tasker.core.data.metrics.WeeklyMetrics
import app.tasker.core.domain.time.DayClock
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** The Ktor client of the AI proxy (OkHttp engine, no logging of bodies or headers). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AiProxyHttpClient

/**
 * Proxy mode of Google Play builds (tech plan §17.2, §18.1): the backend runs the route with its own provider key.
 *
 * Access: an install token from `POST /v1/install`, issued for a Play Integrity token bound to a random install id
 * ([IntegrityBinding]). The token is cached until shortly before it expires (encrypted in [SecretVault]) and renewed
 * once when the backend answers 401. Answers map to [RouteResult] as [ProxyProtocol] describes them: 422 is a refusal;
 * 429 (daily limit) and 5xx are retryable, and a `Retry-After` pauses further calls until then; 400/404/413 and
 * `invalid_output` fail for good; 401/403 mean no access ([FailureKind.AUTH]). A failed install is never blamed on the
 * task: it is reported as [FailureKind.AUTH], or as retryable when it was transient, and a final install failure is
 * not retried for [INSTALL_COOLDOWN] so a device that cannot attest does not call Play Integrity on every task.
 * The same install token carries the weekly telemetry ([sendMetrics]).
 */
@Singleton
class ProxyAiGateway @Inject constructor(
    private val environment: AiEnvironment,
    @param:AiProxyHttpClient private val http: HttpClient,
    private val vault: SecretVault,
    private val integrity: IntegrityTokenProvider,
    private val clock: DayClock,
) : AiGateway,
    MetricsSender {
    private val baseUrl: String? = environment.proxyBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    private val installMutex = Mutex()

    @Volatile
    private var pause: Pause? = null

    @Volatile
    private var installCooldownUntil: Instant? = null

    // Guarded by installMutex. Kept in memory as well, so a store that cannot be written never means a new install per call.
    private var record: InstallRecord? = null
    private var recordLoaded = false

    override suspend fun enrich(request: EnrichRequest): RouteResult<EnrichResponse> {
        val base = baseUrl ?: return RouteResult.Failed(FailureKind.BAD_REQUEST, detail = "proxy_not_configured")
        val problems = EnrichRoute.requestProblems(request)
        if (problems.isNotEmpty()) {
            return RouteResult.Failed(FailureKind.BAD_REQUEST, detail = "invalid request fields: ${problems.joinToString()}")
        }
        pausedFailure(clock.now())?.let { return it }
        val body = AiJson.wire.encodeToString(EnrichRequest.serializer(), request)

        var token = when (val access = installToken(base, stale = null)) {
            is Access.Granted -> access.token
            is Access.Denied -> return access.failure
        }
        var reply = post(base + EnrichRoute.PATH, body, bearer = token)
        if (reply is Reply.Error && reply.status == HTTP_UNAUTHORIZED) {
            // Expired, revoked or signed with a rotated-out key: install again, once.
            token = when (val access = installToken(base, stale = token)) {
                is Access.Granted -> access.token
                is Access.Denied -> return access.failure
            }
            reply = post(base + EnrichRoute.PATH, body, bearer = token)
            if (reply is Reply.Error && reply.status == HTTP_UNAUTHORIZED) forgetToken(clock.now())
        }
        return when (reply) {
            is Reply.Ok -> parseEnrich(request, reply.body)
            is Reply.Error -> failureOf(reply.status, reply.code).also { result ->
                if (result is RouteResult.Failed) rememberPause(reply, result.kind)
            }
            is Reply.Network -> RouteResult.Failed(FailureKind.NETWORK, detail = reply.detail)
        }
    }

    private fun parseEnrich(request: EnrichRequest, body: String): RouteResult<EnrichResponse> {
        val raw = decode(EnrichResponse.serializer(), body)
            ?: return RouteResult.Failed(FailureKind.INVALID_OUTPUT, detail = "proxy_malformed_response")
        // The backend already checked the answer; the check is idempotent and cheap, so the app does not trust blindly.
        return RouteResult.Success(EnrichRoute.validate(request, raw), PROXY_USAGE)
    }

    // region Telemetry

    /**
     * Sends one week of telemetry aggregates (tech plan §23) with the install token of the AI route: the report names
     * the token's install, since the backend accepts aggregates only from the install they describe. A 401 renews the
     * token once, as for [enrich]; 5xx, 429 and network failures are worth a retry, other answers are not.
     */
    override suspend fun sendMetrics(metrics: WeeklyMetrics): MetricsDelivery {
        val base = baseUrl ?: return MetricsDelivery.OFF
        var access = when (val result = installToken(base, stale = null)) {
            is Access.Granted -> result
            is Access.Denied -> return deliveryOf(result.failure.kind)
        }
        var reply = postMetrics(base, access, metrics) ?: return MetricsDelivery.REJECTED
        if (reply is Reply.Error && reply.status == HTTP_UNAUTHORIZED) {
            access = when (val result = installToken(base, stale = access.token)) {
                is Access.Granted -> result
                is Access.Denied -> return deliveryOf(result.failure.kind)
            }
            reply = postMetrics(base, access, metrics) ?: return MetricsDelivery.REJECTED
            if (reply is Reply.Error && reply.status == HTTP_UNAUTHORIZED) forgetToken(clock.now())
        }
        return when (reply) {
            is Reply.Ok -> MetricsDelivery.SENT
            is Reply.Error -> deliveryOf(kindOf(reply.status, reply.code))
            is Reply.Network -> MetricsDelivery.RETRY
        }
    }

    /** Null when the report would not pass the backend's checks: a bug to fix, not something to send again. */
    private suspend fun postMetrics(base: String, access: Access.Granted, metrics: WeeklyMetrics): Reply? {
        val report = MetricsReport(access.installId, metrics.weekStart.toString(), metrics.counters, metrics.values)
        val problems = report.problems()
        if (problems.isNotEmpty()) {
            Log.w(TAG, "Metrics report not sent, invalid fields: ${problems.joinToString()}")
            return null
        }
        return post(
            base + ProxyProtocol.METRICS_PATH,
            AiJson.wire.encodeToString(MetricsReport.serializer(), report),
            bearer = access.token,
        )
    }

    private fun deliveryOf(kind: FailureKind): MetricsDelivery = if (kind.retryable) MetricsDelivery.RETRY else MetricsDelivery.REJECTED

    // endregion

    // region Install token

    private suspend fun installToken(base: String, stale: String?): Access = installMutex.withLock {
        val now = clock.now()
        val current = loadRecord()
        if (current != null) {
            current.usableToken(now)?.takeIf { it != stale }?.let { return@withLock Access.Granted(it, current.installId) }
        }
        installCooldownUntil?.takeIf { now < it }?.let {
            return@withLock Access.Denied(RouteResult.Failed(FailureKind.AUTH, detail = "install_cooldown"))
        }
        val installId = current?.installId ?: UUID.randomUUID().toString().also { saveRecord(InstallRecord(it)) }
        install(base, installId, now)
    }

    private suspend fun install(base: String, installId: String, now: Instant): Access {
        val devKey = environment.proxyDevInstallKey
        val integrityToken = if (devKey != null) {
            ""
        } else {
            when (val result = integrity.requestToken(IntegrityBinding.requestHash(installId))) {
                is IntegrityTokenResult.Token -> result.value
                is IntegrityTokenResult.Failed -> return installFailed(result.kind, result.detail, now)
            }
        }
        val body = AiJson.wire.encodeToString(InstallRequest.serializer(), InstallRequest(installId, integrityToken))
        return when (val reply = post(base + ProxyProtocol.INSTALL_PATH, body, bearer = null, devKey = devKey)) {
            is Reply.Ok -> {
                val response = decode(InstallResponse.serializer(), reply.body)?.takeIf { it.token.isNotBlank() }
                    ?: return installFailed(FailureKind.OVERLOADED, "install_malformed_response", now)
                saveRecord(InstallRecord(installId, response.token, response.expiresAtEpochSeconds, now.toEpochMilli()))
                Access.Granted(response.token, installId)
            }
            is Reply.Error -> {
                val kind = kindOf(reply.status, reply.code)
                rememberPause(reply, kind)
                installFailed(kind, detailOf(reply.status, reply.code), now)
            }
            is Reply.Network -> installFailed(FailureKind.NETWORK, reply.detail, now)
        }
    }

    private fun installFailed(kind: FailureKind, detail: String, now: Instant): Access.Denied {
        if (kind.retryable) return Access.Denied(RouteResult.Failed(kind, detail = "install: $detail"))
        installCooldownUntil = now.plus(INSTALL_COOLDOWN)
        return Access.Denied(RouteResult.Failed(FailureKind.AUTH, detail = "install: $detail"))
    }

    /** The install id stays; only the token goes. */
    private suspend fun forgetToken(now: Instant) {
        installMutex.withLock {
            loadRecord()?.let { saveRecord(InstallRecord(it.installId)) }
            installCooldownUntil = now.plus(INSTALL_COOLDOWN)
        }
    }

    /** With [installMutex] held. */
    private suspend fun loadRecord(): InstallRecord? {
        if (!recordLoaded) {
            record = try {
                vault.read(RECORD_NAME)?.let { decode(InstallRecord.serializer(), it.decodeToString()) }
            } catch (e: IOException) {
                // The store fails right now: this process installs again and keeps the new token in memory.
                Log.w(TAG, "Cannot read the install token (${e.javaClass.simpleName})")
                null
            }
            recordLoaded = true
        }
        return record
    }

    /** With [installMutex] held. */
    private suspend fun saveRecord(record: InstallRecord) {
        this.record = record
        recordLoaded = true
        try {
            vault.write(RECORD_NAME, AiJson.wire.encodeToString(InstallRecord.serializer(), record).encodeToByteArray())
        } catch (e: IOException) {
            // The token still works for this process; it is requested again after a restart.
            Log.w(TAG, "Cannot store the install token (${e.javaClass.simpleName})")
        }
    }

    // endregion

    // region HTTP

    private suspend fun post(url: String, json: String, bearer: String?, devKey: String? = null): Reply = try {
        val response = http.post(url) {
            accept(ContentType.Application.Json)
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            devKey?.let { header(ProxyProtocol.DEV_INSTALL_KEY_HEADER, it) }
            setBody(TextContent(json, ContentType.Application.Json))
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) {
            Reply.Ok(text)
        } else {
            Reply.Error(response.status.value, decode(ErrorBody.serializer(), text)?.code, retryAfterOf(response))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Reply.Network(e.javaClass.simpleName)
    } catch (e: IllegalStateException) {
        // The engine reports some connection failures this way; never with request content.
        Reply.Network(e.javaClass.simpleName)
    }

    private fun retryAfterOf(response: HttpResponse): Duration? =
        response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let(Duration::ofSeconds)

    private fun <T> decode(serializer: KSerializer<T>, text: String): T? = try {
        AiJson.wire.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    // endregion

    // region Retry-After

    private fun pausedFailure(now: Instant): RouteResult.Failed? =
        pause?.takeIf { now < it.until }?.let { RouteResult.Failed(it.kind, detail = "proxy_paused") }

    private fun rememberPause(reply: Reply.Error, kind: FailureKind) {
        val after = reply.retryAfter ?: return
        if (kind.retryable) pause = Pause(clock.now().plus(minOf(after, MAX_PAUSE)), kind)
    }

    // endregion

    private class Pause(val until: Instant, val kind: FailureKind)

    private sealed interface Access {
        class Granted(val token: String, val installId: String) : Access

        class Denied(val failure: RouteResult.Failed) : Access
    }

    private sealed interface Reply {
        class Ok(val body: String) : Reply

        class Error(val status: Int, val code: String?, val retryAfter: Duration?) : Reply

        class Network(val detail: String) : Reply
    }

    /** Persisted proxy access; not a data class, so the token never ends up in a string. */
    @Serializable
    private class InstallRecord(
        val installId: String,
        val token: String? = null,
        val expiresAtEpochSeconds: Long = 0,
        val obtainedAtEpochMillis: Long = 0,
    ) {
        /**
         * The token while it is fresh. Its lifetime is measured on the device clock from the moment it was obtained,
         * so a device clock far off the server's neither renews it on every call nor keeps it forever; a 401 renews
         * it anyway.
         */
        fun usableToken(now: Instant): String? {
            val value = token ?: return null
            val obtained = Instant.ofEpochMilli(obtainedAtEpochMillis)
            val serverLifetime = Duration.between(obtained, Instant.ofEpochSecond(expiresAtEpochSeconds))
            val lifetime = if (serverLifetime in MIN_LIFETIME..MAX_LIFETIME) serverLifetime else MAX_LIFETIME
            return value.takeIf { now < obtained.plus(lifetime).minus(RENEW_MARGIN) }
        }
    }

    internal companion object {
        /** Model name in [RouteUsage] of proxy answers: the backend picks the model and keeps the token accounting. */
        const val PROXY_MODEL = "proxy"
        private val PROXY_USAGE = RouteUsage(model = PROXY_MODEL, inputTokens = 0, outputTokens = 0)

        private const val TAG = "ProxyAiGateway"
        private const val RECORD_NAME = "proxy_install"
        private const val HTTP_UNAUTHORIZED = 401

        val INSTALL_COOLDOWN: Duration = Duration.ofHours(1)
        val MAX_PAUSE: Duration = Duration.ofHours(24)
        private val RENEW_MARGIN: Duration = Duration.ofHours(1)
        private val MIN_LIFETIME: Duration = Duration.ofHours(2)
        private val MAX_LIFETIME: Duration = Duration.ofDays(ProxyProtocol.INSTALL_TOKEN_TTL_DAYS)
        private val ERROR_CODE = Regex("[a-z_]{1,40}")

        /** Maps an error answer of the proxy (status and [ErrorBody.code]) to a route result. */
        fun failureOf(status: Int, code: String?): RouteResult<Nothing> = when {
            status == HTTP_UNPROCESSABLE || code == ErrorCodes.REFUSED -> RouteResult.Refused(category = null)
            else -> RouteResult.Failed(kindOf(status, code), detail = detailOf(status, code))
        }

        fun kindOf(status: Int, code: String?): FailureKind = when (status) {
            400, 404, 413 -> FailureKind.BAD_REQUEST
            401, 403 -> FailureKind.AUTH
            408 -> FailureKind.NETWORK
            429 -> FailureKind.RATE_LIMIT
            502 -> if (code == ErrorCodes.INVALID_OUTPUT) FailureKind.INVALID_OUTPUT else FailureKind.OVERLOADED
            in 500..599 -> FailureKind.OVERLOADED
            in 400..499 -> FailureKind.BAD_REQUEST
            else -> FailureKind.UNKNOWN
        }

        private fun detailOf(status: Int, code: String?): String =
            "proxy_http_$status" + (code?.takeIf { ERROR_CODE.matches(it) }?.let { "_$it" } ?: "")

        private const val HTTP_UNPROCESSABLE = 422
    }
}
