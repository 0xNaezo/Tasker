package app.tasker.backend

import app.tasker.backend.auth.IntegrityVerdict
import app.tasker.backend.db.queryOne
import app.tasker.backend.db.update
import app.tasker.core.ai.contract.ErrorBody
import app.tasker.core.ai.contract.ErrorCodes
import app.tasker.core.ai.contract.InstallResponse
import app.tasker.core.ai.contract.ProxyProtocol
import com.google.common.truth.Truth.assertThat
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import java.time.Duration
import org.junit.Test

class InstallRouteTest {

    @Test
    fun `play verified install gets a 30-day token bound to the install id`() {
        val backend = TestBackend()
        backend.test { client ->
            val response = client.installRequest(integrityToken = "token-from-play")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = response.body<InstallResponse>()
            assertThat(body.expiresAtEpochSeconds).isEqualTo(TEST_NOW.plus(Duration.ofDays(30)).epochSecond)
            assertThat(backend.tokens.installIdOf(body.token)).isEqualTo(INSTALL_ID)
            assertThat(backend.integrityCalls).containsExactly(INSTALL_ID to "token-from-play")
            assertThat(verificationOf(backend, INSTALL_ID)).isEqualTo("play")
        }
    }

    @Test
    fun `installing again refreshes the verification time and issues a new token`() {
        val backend = TestBackend()
        backend.test { client ->
            client.installToken()
            backend.clock.instant = TEST_NOW.plus(Duration.ofDays(29))
            val second = client.installRequest().body<InstallResponse>()

            assertThat(second.expiresAtEpochSeconds).isEqualTo(TEST_NOW.plus(Duration.ofDays(59)).epochSecond)
            val verifiedAt = backend.database.transaction { connection ->
                connection.queryOne("SELECT last_verified_at FROM installs WHERE install_id = ?", INSTALL_ID) {
                    it.getObject(1, java.time.OffsetDateTime::class.java).toInstant()
                }
            }
            assertThat(verifiedAt).isEqualTo(TEST_NOW.plus(Duration.ofDays(29)))
        }
    }

    @Test
    fun `rejected integrity verdict answers 403 and stores nothing`() {
        val backend = TestBackend()
        backend.verdict = IntegrityVerdict.Rejected("device_integrity")
        backend.test { client ->
            val response = client.installRequest()

            assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
            assertThat(response.body<ErrorBody>().code).isEqualTo(ErrorCodes.INTEGRITY_FAILED)
            assertThat(backend.services.installs.state(INSTALL_ID)).isNull()
        }
    }

    @Test
    fun `unavailable verification answers 503 with Retry-After`() {
        val backend = TestBackend()
        backend.verdict = IntegrityVerdict.Unavailable("network")
        backend.test { client ->
            val response = client.installRequest()

            assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
            assertThat(response.headers[HttpHeaders.RetryAfter]).isEqualTo("60")
            assertThat(response.body<ErrorBody>().code).isEqualTo(ErrorCodes.UNAVAILABLE)
        }
    }

    @Test
    fun `dev key replaces play integrity in the dev environment`() {
        val backend = TestBackend(playConfigured = false)
        backend.test { client ->
            val response = client.installRequest(integrityToken = "", devKey = TEST_DEV_KEY)

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(backend.tokens.installIdOf(response.body<InstallResponse>().token)).isEqualTo(INSTALL_ID)
            assertThat(verificationOf(backend, INSTALL_ID)).isEqualTo("dev")
            assertThat(backend.integrityCalls).isEmpty()
        }
    }

    @Test
    fun `wrong or unconfigured dev key is rejected`() {
        TestBackend().test { client ->
            val wrong = client.installRequest(devKey = "not-the-dev-key-at-all")
            assertThat(wrong.status).isEqualTo(HttpStatusCode.Forbidden)
            assertThat(wrong.body<ErrorBody>().code).isEqualTo(ErrorCodes.INTEGRITY_FAILED)
        }
        TestBackend(devInstallKey = null).test { client ->
            assertThat(client.installRequest(devKey = TEST_DEV_KEY).status).isEqualTo(HttpStatusCode.Forbidden)
        }
    }

    @Test
    fun `without play integrity configured only the dev key works`() {
        TestBackend(playConfigured = false).test { client ->
            val response = client.installRequest()
            assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
            assertThat(response.body<ErrorBody>().code).isEqualTo(ErrorCodes.INTEGRITY_FAILED)
        }
    }

    @Test
    fun `blocked install gets no token`() {
        val backend = TestBackend()
        backend.test { client ->
            client.installToken()
            backend.database.transaction { it.update("UPDATE installs SET blocked = TRUE WHERE install_id = ?", INSTALL_ID) }

            val response = client.installRequest()
            assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
            assertThat(response.body<ErrorBody>().code).isEqualTo(ErrorCodes.INSTALL_BLOCKED)
        }
    }

    @Test
    fun `invalid fields answer 400 without calling play integrity`() {
        val backend = TestBackend()
        backend.test { client ->
            val badId = client.installRequest(installId = "short")
            assertThat(badId.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(badId.body<ErrorBody>().message).contains("installId")

            val blankToken = client.installRequest(integrityToken = " ")
            assertThat(blankToken.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(blankToken.body<ErrorBody>().message).contains("integrityToken")
            assertThat(backend.integrityCalls).isEmpty()
        }
    }

    @Test
    fun `malformed and oversized bodies are rejected`() {
        TestBackend().test { client ->
            val malformed = client.post(ProxyProtocol.INSTALL_PATH) {
                setBody(TextContent("{\"installId\": ", ContentType.Application.Json))
            }
            assertThat(malformed.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(malformed.body<ErrorBody>().code).isEqualTo(ErrorCodes.INVALID_REQUEST)

            val huge = client.post(ProxyProtocol.INSTALL_PATH) {
                setBody(TextContent("{\"installId\":\"${"x".repeat(20_000)}\"}", ContentType.Application.Json))
            }
            assertThat(huge.status).isEqualTo(HttpStatusCode.PayloadTooLarge)
            assertThat(huge.body<ErrorBody>().code).isEqualTo(ErrorCodes.PAYLOAD_TOO_LARGE)

            // Without Content-Length (chunked) the limit applies while reading.
            val streamed = client.post(ProxyProtocol.INSTALL_PATH) {
                setBody(
                    object : OutgoingContent.WriteChannelContent() {
                        override val contentType = ContentType.Application.Json

                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            channel.writeStringUtf8("{\"installId\":\"${"x".repeat(20_000)}\"}")
                        }
                    },
                )
            }
            assertThat(streamed.status).isEqualTo(HttpStatusCode.PayloadTooLarge)
        }
    }

    @Test
    fun `health reports the database and unknown routes answer 404`() {
        TestBackend().test { client ->
            val health = client.get(ProxyProtocol.HEALTH_PATH)
            assertThat(health.status).isEqualTo(HttpStatusCode.OK)
            assertThat(health.bodyAsText()).contains("\"ok\"")

            val missing = client.get("/v1/nothing-here")
            assertThat(missing.status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(missing.body<ErrorBody>().code).isEqualTo(ErrorCodes.NOT_FOUND)
        }
    }

    @Test
    fun `dev key comparison accepts only the exact key`() {
        val policy = ProxyPolicy(1, 1, TEST_DEV_KEY)
        assertThat(policy.acceptsDevKey(TEST_DEV_KEY)).isTrue()
        assertThat(policy.acceptsDevKey(TEST_DEV_KEY + "x")).isFalse()
        assertThat(policy.acceptsDevKey(TEST_DEV_KEY.dropLast(1))).isFalse()
        assertThat(policy.acceptsDevKey("")).isFalse()
        assertThat(ProxyPolicy(1, 1, null).acceptsDevKey("")).isFalse()
    }

    private fun verificationOf(backend: TestBackend, installId: String): String? = backend.database.transaction { connection ->
        connection.queryOne("SELECT verification FROM installs WHERE install_id = ?", installId) { it.getString(1) }
    }
}
