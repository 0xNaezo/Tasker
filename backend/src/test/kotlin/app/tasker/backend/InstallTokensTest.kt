package app.tasker.backend

import app.tasker.backend.auth.InstallTokens
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Test

class InstallTokensTest {

    private val clock = MutableClock(TEST_NOW)
    private val previousSecret = "previous-install-token-secret-000000000"

    @Test
    fun `token is bound to the install id and valid for 30 days`() {
        val tokens = InstallTokens(TEST_SECRET, null, clock)
        val issued = tokens.issue(INSTALL_ID)

        assertThat(issued.expiresAt).isEqualTo(TEST_NOW.plus(Duration.ofDays(30)))
        assertThat(tokens.installIdOf(issued.token)).isEqualTo(INSTALL_ID)

        clock.instant = TEST_NOW.plus(Duration.ofDays(30))
        assertThat(tokens.installIdOf(issued.token)).isEqualTo(INSTALL_ID)
        clock.instant = TEST_NOW.plus(Duration.ofDays(30)).plusSeconds(31)
        assertThat(tokens.installIdOf(issued.token)).isNull()
    }

    @Test
    fun `rotation keeps tokens of the previous secret valid until it is removed`() {
        val old = InstallTokens(previousSecret, null, clock).issue(INSTALL_ID).token
        val rotated = InstallTokens(TEST_SECRET, previousSecret, clock)

        assertThat(rotated.installIdOf(old)).isEqualTo(INSTALL_ID)
        assertThat(rotated.installIdOf(rotated.issue(INSTALL_ID).token)).isEqualTo(INSTALL_ID)
        assertThat(JWT.decode(rotated.issue(INSTALL_ID).token).keyId).isNotEqualTo(JWT.decode(old).keyId)

        val afterRotation = InstallTokens(TEST_SECRET, null, clock)
        assertThat(afterRotation.installIdOf(old)).isNull()
    }

    @Test
    fun `tampered, foreign and malformed tokens are rejected`() {
        val tokens = InstallTokens(TEST_SECRET, null, clock)
        val issued = tokens.issue(INSTALL_ID).token
        val (header, payload, signature) = issued.split('.')
        val otherPayload = tokens.issue("other-install-0001").token.split('.')[1]

        assertThat(tokens.installIdOf("$header.$otherPayload.$signature")).isNull()
        assertThat(tokens.installIdOf("$header.$payload.")).isNull()
        assertThat(tokens.installIdOf("garbage")).isNull()
        assertThat(tokens.verifierFor("garbage")).isNull()

        // Same key id and secret but another audience: not an install token.
        val keyId = JWT.decode(issued).keyId
        val wrongAudience = JWT.create().withKeyId(keyId).withIssuer(InstallTokens.ISSUER).withAudience("someone-else")
            .withSubject(INSTALL_ID).withExpiresAt(TEST_NOW.plusSeconds(60)).sign(Algorithm.HMAC256(TEST_SECRET))
        assertThat(tokens.installIdOf(wrongAudience)).isNull()
    }
}
