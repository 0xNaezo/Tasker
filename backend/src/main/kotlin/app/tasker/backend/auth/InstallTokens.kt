package app.tasker.backend.auth

import app.tasker.core.ai.contract.ProxyProtocol
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat

data class IssuedToken(val token: String, val expiresAt: Instant)

/**
 * Signed install tokens (HMAC-SHA256 JWT, 30 days, tech plan §18.1) bound to an install id (`sub`).
 *
 * Rotation: tokens carry a key id derived from the secret. Set the new secret in INSTALL_TOKEN_SECRET and
 * the old one in INSTALL_TOKEN_SECRET_PREVIOUS: new tokens are signed with the new key, old ones stay valid
 * until they expire or the previous secret is removed (then the app re-runs `/v1/install`).
 */
class InstallTokens(
    secret: String,
    previousSecret: String?,
    private val clock: Clock,
    private val ttl: Duration = Duration.ofDays(ProxyProtocol.INSTALL_TOKEN_TTL_DAYS),
) {
    private val current = SigningKey(secret)
    private val keys: Map<String, SigningKey> = listOfNotNull(current, previousSecret?.let(::SigningKey)).associateBy { it.id }

    fun issue(installId: String): IssuedToken {
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val expiresAt = now.plus(ttl)
        val token = JWT.create()
            .withKeyId(current.id)
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(installId)
            .withIssuedAt(now)
            .withExpiresAt(expiresAt)
            .sign(current.algorithm)
        return IssuedToken(token, expiresAt)
    }

    /** Verifier for the key that signed [token]; null for malformed tokens and unknown (rotated out) keys. */
    fun verifierFor(token: String): JWTVerifier? = try {
        keys[JWT.decode(token).keyId]?.verifier
    } catch (ignored: JWTDecodeException) {
        null
    }

    /** Install id of a valid, unexpired token, or null. */
    fun installIdOf(token: String): String? = try {
        verifierFor(token)?.verify(token)?.subject
    } catch (ignored: JWTVerificationException) {
        null
    }

    private inner class SigningKey(secret: String) {
        val id: String = HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)))
            .take(KEY_ID_LENGTH)
        val algorithm: Algorithm = Algorithm.HMAC256(secret)

        // build(Clock) lives on the implementation class; JWT.require always returns it.
        val verifier: JWTVerifier = (
            JWT.require(algorithm)
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .acceptLeeway(LEEWAY_SECONDS) as JWTVerifier.BaseVerification
            ).build(clock)
    }

    companion object {
        const val ISSUER = "tasker-ai-proxy"
        const val AUDIENCE = "tasker-install"
        private const val KEY_ID_LENGTH = 16
        private const val LEEWAY_SECONDS = 30L
    }
}
