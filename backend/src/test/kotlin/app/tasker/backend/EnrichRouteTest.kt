package app.tasker.backend

import app.tasker.backend.auth.InstallTokens
import app.tasker.backend.db.update
import app.tasker.backend.http.ACCESS_LOGGER_NAME
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EnrichRoute
import app.tasker.core.ai.contract.ErrorBody
import app.tasker.core.ai.contract.ErrorCodes
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteResult
import ch.qos.logback.classic.Level
import com.google.common.truth.Truth.assertThat
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.time.Duration
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Test

class EnrichRouteTest {

    private val today: LocalDate = LocalDate.parse("2026-10-06")

    @Test
    fun `successful call returns the validated answer and records tokens, cost and latency`() {
        val backend = TestBackend()
        backend.engine.answer = { request ->
            backend.timeSource += 1234.milliseconds
            RouteResult.Success(EnrichResponse(estimate = "S", estimateConfidence = 0.8, planDate = null), OPUS_USAGE).also {
                check(request.text == "сдать отчёт до пятницы")
            }
        }
        backend.test { client ->
            val token = client.installToken()
            val response = client.enrich(token)

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.body<EnrichResponse>()).isEqualTo(EnrichResponse(estimate = "S", estimateConfidence = 0.8))
            assertThat(backend.engine.requests.single()).isEqualTo(enrichRequest())

            val usage = checkNotNull(backend.services.usage.dailyUsage(INSTALL_ID, today))
            assertThat(usage.requests).isEqualTo(1)
            assertThat(usage.succeeded).isEqualTo(1)
            assertThat(usage.inputTokens).isEqualTo(1000)
            assertThat(usage.outputTokens).isEqualTo(300)
            assertThat(usage.costMicroUsd).isEqualTo(OPUS_USAGE_COST)
            assertThat(usage.latencyMsTotal).isEqualTo(1234)
            assertThat(usage.latencyMsMax).isEqualTo(1234)
            assertThat(backend.services.usage.globalCost(today)).isEqualTo(OPUS_USAGE_COST)
        }
    }

    @Test
    fun `empty answer is a success with an empty body`() {
        val backend = TestBackend()
        backend.engine.answer = { RouteResult.Success(EnrichResponse(), OPUS_USAGE) }
        backend.test { client ->
            val response = client.enrich(client.installToken())
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.body<EnrichResponse>().isEmpty).isTrue()
        }
    }

    @Test
    fun `the cost the provider reported is charged`() {
        val backend = TestBackend()
        val reported = OPUS_USAGE.copy(cacheReadTokens = 1500, costMicroUsd = 12_345)
        backend.engine.answer = { RouteResult.Success(EnrichResponse(estimate = "M"), reported) }
        backend.test { client ->
            client.enrich(client.installToken())
            val usage = checkNotNull(backend.services.usage.dailyUsage(INSTALL_ID, today))
            assertThat(usage.inputTokens).isEqualTo(1000)
            assertThat(usage.cacheReadTokens).isEqualTo(1500)
            assertThat(usage.costMicroUsd).isEqualTo(12_345)
            assertThat(backend.services.usage.globalCost(today)).isEqualTo(12_345)
        }
    }

    @Test
    fun `missing, malformed, foreign and expired tokens answer 401`() {
        val backend = TestBackend()
        backend.test { client ->
            val token = client.installToken()

            assertThat(client.enrich(null).status).isEqualTo(HttpStatusCode.Unauthorized)
            val garbage = client.enrich("not-a-jwt")
            assertThat(garbage.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(garbage.body<ErrorBody>().code).isEqualTo(ErrorCodes.UNAUTHORIZED)

            val foreign = InstallTokens("another-secret-of-sufficient-length-000", null, backend.clock).issue(INSTALL_ID).token
            assertThat(client.enrich(foreign).status).isEqualTo(HttpStatusCode.Unauthorized)

            backend.clock.instant = TEST_NOW.plus(Duration.ofDays(30)).plusSeconds(31)
            assertThat(client.enrich(token).status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(backend.engine.requests).isEmpty()
        }
    }

    @Test
    fun `token of an install missing from the database answers 401 and a blocked install 403`() {
        val backend = TestBackend()
        backend.test { client ->
            assertThat(client.enrich(backend.tokens.issue("unknown-install-0001").token).status).isEqualTo(HttpStatusCode.Unauthorized)

            val token = client.installToken()
            backend.database.transaction { it.update("UPDATE installs SET blocked = TRUE WHERE install_id = ?", INSTALL_ID) }
            val blocked = client.enrich(token)
            assertThat(blocked.status).isEqualTo(HttpStatusCode.Forbidden)
            assertThat(blocked.body<ErrorBody>().code).isEqualTo(ErrorCodes.INSTALL_BLOCKED)
            assertThat(backend.engine.requests).isEmpty()
        }
    }

    @Test
    fun `invalid request answers 400 without spending the daily limit`() {
        val backend = TestBackend()
        backend.test { client ->
            val token = client.installToken()
            val response = client.enrich(token, enrichRequest().copy(text = " ", timeZone = "Mars/Base"))

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.body<ErrorBody>().message).isEqualTo("Invalid fields: text, timeZone")
            assertThat(backend.services.usage.dailyUsage(INSTALL_ID, today)).isNull()

            val malformed = client.post(EnrichRoute.PATH) {
                bearerAuth(token)
                setBody(TextContent("{\"text\": [", ContentType.Application.Json))
            }
            assertThat(malformed.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(backend.engine.requests).isEmpty()
        }
    }

    @Test
    fun `daily limit answers 429 until the next UTC day`() {
        val backend = TestBackend(dailyRequestsPerInstall = 2)
        backend.test { client ->
            val token = client.installToken()
            repeat(2) { assertThat(client.enrich(token).status).isEqualTo(HttpStatusCode.OK) }

            val limited = client.enrich(token)
            assertThat(limited.status).isEqualTo(HttpStatusCode.TooManyRequests)
            assertThat(limited.headers[HttpHeaders.RetryAfter]).isEqualTo((14 * 3600).toString())
            assertThat(limited.body<ErrorBody>().code).isEqualTo(ErrorCodes.DAILY_LIMIT)
            assertThat(backend.engine.requests).hasSize(2)

            backend.clock.instant = TEST_NOW.plus(Duration.ofHours(14))
            assertThat(client.enrich(token).status).isEqualTo(HttpStatusCode.OK)
        }
    }

    @Test
    fun `global budget raises one warning, one exhaustion alert and then answers 503`() {
        val backend = TestBackend(dailyRequestsPerInstall = 10, dailyBudgetMicroUsd = 25_000)
        LogCapture().use { logs ->
            backend.test { client ->
                val token = client.installToken()
                repeat(3) { assertThat(client.enrich(token).status).isEqualTo(HttpStatusCode.OK) }

                val exhausted = client.enrich(token)
                assertThat(exhausted.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
                assertThat(exhausted.body<ErrorBody>().code).isEqualTo(ErrorCodes.BUDGET_EXHAUSTED)
                assertThat(exhausted.headers[HttpHeaders.RetryAfter]).isEqualTo((14 * 3600).toString())
                assertThat(backend.engine.requests).hasSize(3)
            }
            val alerts = logs.events.filter { it.loggerName == Alerts.LOGGER_NAME }
            assertThat(alerts.map { it.level to it.formattedMessage }).containsExactly(
                Level.WARN to "ALERT ai_budget_warning spentMicroUsd=20000 budgetMicroUsd=25000",
                Level.ERROR to "ALERT ai_budget_exhausted spentMicroUsd=30000 budgetMicroUsd=25000",
            ).inOrder()
        }
    }

    @Test
    fun `refusal answers 422 and is recorded with its tokens`() {
        val backend = TestBackend()
        backend.engine.answer = { RouteResult.Refused("cyber", OPUS_USAGE) }
        backend.test { client ->
            val response = client.enrich(client.installToken())

            assertThat(response.status).isEqualTo(HttpStatusCode.UnprocessableEntity)
            assertThat(response.body<ErrorBody>().code).isEqualTo(ErrorCodes.REFUSED)
            val usage = checkNotNull(backend.services.usage.dailyUsage(INSTALL_ID, today))
            assertThat(usage.refused).isEqualTo(1)
            assertThat(usage.costMicroUsd).isEqualTo(OPUS_USAGE_COST)
        }
    }

    @Test
    fun `provider failures map to 503 or 502 and are recorded`() {
        val expectations = listOf(
            FailureKind.RATE_LIMIT to Triple(HttpStatusCode.ServiceUnavailable, ErrorCodes.UPSTREAM_BUSY, "30"),
            FailureKind.OVERLOADED to Triple(HttpStatusCode.ServiceUnavailable, ErrorCodes.UPSTREAM_BUSY, "30"),
            FailureKind.NETWORK to Triple(HttpStatusCode.ServiceUnavailable, ErrorCodes.UPSTREAM_BUSY, "30"),
            FailureKind.INVALID_OUTPUT to Triple(HttpStatusCode.BadGateway, ErrorCodes.INVALID_OUTPUT, null),
            FailureKind.TRUNCATED to Triple(HttpStatusCode.BadGateway, ErrorCodes.INVALID_OUTPUT, null),
            FailureKind.AUTH to Triple(HttpStatusCode.BadGateway, ErrorCodes.UPSTREAM_ERROR, null),
            FailureKind.BAD_REQUEST to Triple(HttpStatusCode.BadGateway, ErrorCodes.UPSTREAM_ERROR, null),
            FailureKind.UNKNOWN to Triple(HttpStatusCode.BadGateway, ErrorCodes.UPSTREAM_ERROR, null),
        )
        val backend = TestBackend(dailyRequestsPerInstall = 100)
        backend.test { client ->
            val token = client.installToken()
            for ((kind, expected) in expectations) {
                backend.engine.answer = { RouteResult.Failed(kind) }
                val response = client.enrich(token)
                val (status, code, retryAfter) = expected
                assertThat(response.status).isEqualTo(status)
                assertThat(response.body<ErrorBody>().code).isEqualTo(code)
                assertThat(response.headers[HttpHeaders.RetryAfter]).isEqualTo(retryAfter)
            }
            val usage = checkNotNull(backend.services.usage.dailyUsage(INSTALL_ID, today))
            assertThat(usage.failed).isEqualTo(expectations.size)
            assertThat(usage.costMicroUsd).isEqualTo(0)
        }
    }

    @Test
    fun `an engine that throws still ends as a recorded failure`() {
        val backend = TestBackend()
        backend.engine.answer = { error("boom") }
        backend.test { client ->
            val response = client.enrich(client.installToken())
            assertThat(response.status).isEqualTo(HttpStatusCode.BadGateway)
            assertThat(checkNotNull(backend.services.usage.dailyUsage(INSTALL_ID, today)).failed).isEqualTo(1)
        }
    }

    @Test
    fun `logs carry route, status, tokens and cost but never task texts or tokens`() {
        val secretText = "позвонить нотариусу Иванову про наследство"
        val secretSimilar = "передать документы Петровой"
        val backend = TestBackend(dailyRequestsPerInstall = 10)
        LogCapture().use { logs ->
            var token = ""
            backend.test { client ->
                token = client.installToken()
                val request = enrichRequest(text = secretText, similar = secretSimilar)

                assertThat(client.enrich(token, request).status).isEqualTo(HttpStatusCode.OK)
                val access = logs.await { it.loggerName == ACCESS_LOGGER_NAME && it.formattedMessage.contains("route=/v1/enrich") }
                assertThat(access.formattedMessage).isEqualTo(
                    "POST route=/v1/enrich status=200 latencyMs=0 outcome=succeeded model=anthropic/claude-opus-5.5 inputTokens=1000 " +
                        "outputTokens=300 cacheReadTokens=0 cacheCreationTokens=0 costMicroUsd=10000",
                )

                // Failure paths must not leak either: invalid fields, malformed JSON, an engine error quoting the text.
                client.enrich(token, request.copy(timeZone = "Nowhere/$secretText"))
                client.post(EnrichRoute.PATH) {
                    bearerAuth(token)
                    setBody(TextContent("{\"text\": \"$secretText\", \"now\": ", ContentType.Application.Json))
                }
                backend.engine.answer = { throw IllegalStateException("cannot handle '${it.text}'") }
                client.enrich(token, request)
                backend.engine.answer = { throw AssertionError("fatal on '${it.text}'") }
                assertThat(client.enrich(token, request).status).isEqualTo(HttpStatusCode.InternalServerError)
                client.post("/v1/enrich/secret-path-fragment")
                logs.await { it.formattedMessage.contains("status=500") }
                logs.await { it.formattedMessage.contains("route=other status=404") }
            }
            val text = logs.fullText()
            assertThat(text).contains("Unhandled error on /v1/enrich")
            listOf(secretText, secretSimilar, "нотариус", "secret-path-fragment", token, TEST_SECRET).forEach { secret ->
                assertThat(text).doesNotContain(secret)
            }
        }
    }

    @Test
    fun `error rate alert is raised once per outage`() {
        val backend = TestBackend(dailyRequestsPerInstall = 100)
        LogCapture().use { logs ->
            backend.test { client ->
                val token = client.installToken()
                backend.engine.answer = { RouteResult.Failed(FailureKind.OVERLOADED) }
                repeat(ErrorRateMonitor.DEFAULT_MIN_CALLS + 5) { client.enrich(token) }
            }
            val alerts = logs.events.filter { it.loggerName == Alerts.LOGGER_NAME }.map { it.formattedMessage }
            assertThat(alerts).containsExactly("ALERT ai_error_rate failedPercent=100 window=50")
        }
    }
}
