package app.tasker.core.ai.contract

import java.security.MessageDigest
import java.time.DayOfWeek
import java.util.Base64
import kotlinx.serialization.Serializable

/**
 * App <-> backend protocol for Google Play builds (tech plan §18.1). Every route except `/health` and
 * `/v1/install` needs `Authorization: Bearer <install token>`.
 *
 * Status codes: 400 invalid request, 401 missing/expired token (call `/v1/install` again), 403 install
 * rejected or blocked, 413 body too large, 422 the model declined (do not retry), 429 daily limit of the
 * install (retry after `Retry-After`), 502 upstream error (`invalid_output`: the answer was unusable, do not
 * retry the same task), 503 temporarily unavailable or daily budget exhausted (retry after `Retry-After`).
 * Error bodies are [ErrorBody]; the app keeps its rule-based fields on any error.
 */
object ProxyProtocol {
    const val INSTALL_PATH = "/v1/install"
    const val METRICS_PATH = "/v1/metrics"
    const val HEALTH_PATH = "/health"

    /** Dev environment only: replaces the Play Integrity token with a shared test key (§18.1). */
    const val DEV_INSTALL_KEY_HEADER = "X-Dev-Install-Key"

    const val INSTALL_TOKEN_TTL_DAYS = 30L
}

@Serializable
data class InstallRequest(
    /** Random id generated once per installation (a UUID). */
    val installId: String,
    /** Play Integrity token requested with [IntegrityBinding.requestHash] of [installId]. */
    val integrityToken: String,
)

@Serializable
data class InstallResponse(
    /** Signed install token for `Authorization: Bearer`. */
    val token: String,
    val expiresAtEpochSeconds: Long,
)

/**
 * Weekly telemetry aggregates (tech plan §23): numbers only, never texts. Sent only with the separate
 * telemetry consent. [weekStart] is the Monday of the ISO week.
 */
@Serializable
data class MetricsReport(
    val installId: String,
    val weekStart: String,
    val counters: Map<String, Long> = emptyMap(),
    val values: Map<String, Double> = emptyMap(),
) {
    /** Field-level problems; empty when the report may be stored. */
    fun problems(): List<String> = buildList {
        if (!InstallIds.isValid(installId)) add("installId")
        val week = IsoValues.date(weekStart)
        if (week == null || week.dayOfWeek != DayOfWeek.MONDAY) add("weekStart")
        if (counters.size > MAX_ENTRIES || counters.any { (key, value) -> !isMetricKey(key) || value < 0 }) add("counters")
        if (values.size > MAX_ENTRIES || values.any { (key, value) -> !isMetricKey(key) || !value.isFinite() }) add("values")
    }

    companion object {
        const val MAX_ENTRIES = 64
        private val KEY = Regex("[a-z][a-z0-9_.]{0,63}")

        /** Metric names are short machine identifiers, so no free text can be smuggled in as a key. */
        fun isMetricKey(key: String): Boolean = KEY.matches(key)
    }
}

@Serializable
data class ErrorBody(val code: String, val message: String)

/** Values of [ErrorBody.code]. */
object ErrorCodes {
    const val INVALID_REQUEST = "invalid_request"
    const val PAYLOAD_TOO_LARGE = "payload_too_large"
    const val UNAUTHORIZED = "unauthorized"
    const val INTEGRITY_FAILED = "integrity_failed"
    const val INSTALL_BLOCKED = "install_blocked"
    const val FORBIDDEN = "forbidden"
    const val DAILY_LIMIT = "daily_limit"
    const val BUDGET_EXHAUSTED = "budget_exhausted"
    const val REFUSED = "refused"
    const val UPSTREAM_BUSY = "upstream_busy"
    const val UPSTREAM_ERROR = "upstream_error"

    /** The model's answer was unusable (truncated or malformed); retrying the same task is unlikely to help. */
    const val INVALID_OUTPUT = "invalid_output"
    const val UNAVAILABLE = "unavailable"
    const val NOT_FOUND = "not_found"
    const val INTERNAL = "internal"
}

object InstallIds {
    private val FORMAT = Regex("[A-Za-z0-9-]{8,64}")

    fun isValid(installId: String): Boolean = FORMAT.matches(installId)
}

/**
 * Binds a Play Integrity token to one install id: the app passes [requestHash] as `requestHash` (standard
 * API) or `nonce` (classic API); the backend recomputes it from the install id in the request.
 */
object IntegrityBinding {
    private const val DOMAIN = "tasker-install-v1:"

    /** URL-safe Base64 without padding of SHA-256("tasker-install-v1:" + installId); 43 characters. */
    fun requestHash(installId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((DOMAIN + installId).toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
