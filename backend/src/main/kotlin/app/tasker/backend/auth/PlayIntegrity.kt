package app.tasker.backend.auth

import app.tasker.core.ai.contract.IntegrityBinding
import com.google.auth.oauth2.GoogleCredentials
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

sealed interface IntegrityVerdict {
    data object Valid : IntegrityVerdict

    /** The token is invalid or does not meet the policy; [reason] is a short code for logs. */
    data class Rejected(val reason: String) : IntegrityVerdict

    /** Verification could not run (Google unreachable, our credentials rejected); retry later. */
    data class Unavailable(val reason: String) : IntegrityVerdict
}

/** Checks that an install request comes from our app, installed by Google Play, on a genuine device. */
fun interface IntegrityVerifier {
    suspend fun verify(installId: String, integrityToken: String): IntegrityVerdict
}

/**
 * Policy for a decoded Play Integrity payload (tech plan §18.1): the app is recognised by Play, the package
 * matches, the device meets device integrity, the token is fresh and was requested for this install id
 * (`requestHash` of the standard API or `nonce` of the classic API equals [IntegrityBinding.requestHash]).
 */
object PlayIntegrityPolicy {
    private val CLOCK_SKEW: Duration = Duration.ofMinutes(1)

    fun evaluate(payload: JsonObject, packageName: String, installId: String, now: Instant, maxAge: Duration): IntegrityVerdict {
        val external = payload.obj("tokenPayloadExternal") ?: payload
        val request = external.obj("requestDetails") ?: return IntegrityVerdict.Rejected("request_details_missing")
        val app = external.obj("appIntegrity")
        val device = external.obj("deviceIntegrity")
        val expectedBinding = IntegrityBinding.requestHash(installId)
        val issuedAt = request.text("timestampMillis")?.toLongOrNull()?.let(Instant::ofEpochMilli)
        val deviceVerdicts = (device?.get("deviceRecognitionVerdict") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }

        val checks = listOf<Pair<String, () -> Boolean>>(
            "request_package" to { request.text("requestPackageName") == packageName },
            "request_binding" to {
                val bound = request.text("requestHash") ?: request.text("nonce")?.trimEnd('=')
                bound == expectedBinding
            },
            "stale_token" to {
                issuedAt != null && !issuedAt.isAfter(now.plus(CLOCK_SKEW)) && !issuedAt.isBefore(now.minus(maxAge))
            },
            "app_not_recognized" to { app?.text("appRecognitionVerdict") == "PLAY_RECOGNIZED" },
            "app_package" to { app?.text("packageName") == packageName },
            "device_integrity" to { "MEETS_DEVICE_INTEGRITY" in deviceVerdicts || "MEETS_STRONG_INTEGRITY" in deviceVerdicts },
        )
        val failed = checks.firstOrNull { (_, passes) -> !passes() }?.first
        return if (failed == null) IntegrityVerdict.Valid else IntegrityVerdict.Rejected(failed)
    }

    private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.content
}

/**
 * Decodes the token with the Play Integrity API (`v1/{packageName}:decodeIntegrityToken`) using a Google
 * service account, then applies [PlayIntegrityPolicy]. [accessToken] supplies an OAuth token with the
 * `playintegrity` scope (see [GoogleAccessTokens]); tests pass a fake and a local endpoint.
 */
class PlayIntegrityVerifier(
    private val packageName: String,
    private val accessToken: () -> String,
    private val clock: Clock,
    private val endpoint: String = "https://playintegrity.googleapis.com",
    private val maxTokenAge: Duration = Duration.ofMinutes(10),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : IntegrityVerifier {

    override suspend fun verify(installId: String, integrityToken: String): IntegrityVerdict = withContext(Dispatchers.IO) {
        val response = try {
            val request = HttpRequest.newBuilder(URI.create("$endpoint/v1/$packageName:decodeIntegrityToken"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer ${accessToken()}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildJsonObject { put("integrityToken", integrityToken) }.toString()))
                .build()
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            log.warn("Play Integrity request failed: {}", e.javaClass.simpleName)
            return@withContext IntegrityVerdict.Unavailable("network")
        }
        when (response.statusCode()) {
            HTTP_OK -> decode(response.body(), installId)
            HTTP_BAD_REQUEST -> IntegrityVerdict.Rejected("token_invalid")
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> {
                log.error("Play Integrity API rejected our credentials (HTTP {})", response.statusCode())
                IntegrityVerdict.Unavailable("google_credentials")
            }
            else -> IntegrityVerdict.Unavailable("google_http_${response.statusCode()}")
        }
    }

    private fun decode(body: String, installId: String): IntegrityVerdict {
        val payload = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return IntegrityVerdict.Unavailable("google_response")
        return PlayIntegrityPolicy.evaluate(payload, packageName, installId, clock.instant(), maxTokenAge)
    }

    private companion object {
        val log = LoggerFactory.getLogger(PlayIntegrityVerifier::class.java)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}

/** OAuth access tokens for the Play Integrity API from a Google service account. */
object GoogleAccessTokens {
    private const val SCOPE = "https://www.googleapis.com/auth/playintegrity"

    /**
     * Uses inline service-account JSON when given (handy on PaaS), otherwise Application Default Credentials,
     * i.e. the file named by GOOGLE_APPLICATION_CREDENTIALS. Fails fast at startup when neither is usable.
     */
    fun create(inlineJson: String?): () -> String {
        val credentials = (
            inlineJson?.let { GoogleCredentials.fromStream(it.byteInputStream()) }
                ?: GoogleCredentials.getApplicationDefault()
            ).createScoped(listOf(SCOPE))
        return {
            credentials.refreshIfExpired()
            checkNotNull(credentials.accessToken) { "Google credentials returned no access token" }.tokenValue
        }
    }
}
