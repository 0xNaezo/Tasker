package app.tasker.backend

import app.tasker.backend.db.update
import app.tasker.core.ai.contract.ErrorBody
import app.tasker.core.ai.contract.ErrorCodes
import app.tasker.core.ai.contract.MetricsReport
import app.tasker.core.ai.contract.ProxyProtocol
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import java.time.LocalDate
import org.junit.Test

class MetricsRouteTest {

    private val report = MetricsReport(
        installId = INSTALL_ID,
        weekStart = "2026-09-28",
        counters = mapOf("tasks.created" to 42, "plan.items_done" to 17),
        values = mapOf("maintenance.minutes" to 31.5),
    )

    @Test
    fun `weekly report is stored and replaced on resend`() {
        val backend = TestBackend()
        backend.test { client ->
            val token = client.installToken()
            assertThat(client.sendMetrics(token, report).status).isEqualTo(HttpStatusCode.NoContent)

            val resent = report.copy(counters = mapOf("tasks.created" to 43))
            assertThat(client.sendMetrics(token, resent).status).isEqualTo(HttpStatusCode.NoContent)

            assertThat(backend.services.metrics.find(INSTALL_ID, LocalDate.parse("2026-09-28"))).isEqualTo(resent)
        }
    }

    @Test
    fun `report must belong to the authenticated install`() {
        TestBackend().test { client ->
            val token = client.installToken()
            val response = client.sendMetrics(token, report.copy(installId = "another-install-01"))
            assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden)
            assertThat(response.body<ErrorBody>().code).isEqualTo(ErrorCodes.FORBIDDEN)
        }
    }

    @Test
    fun `free text keys, non-mondays and stale or future weeks are rejected`() {
        TestBackend().test { client ->
            val token = client.installToken()
            val invalid = listOf(
                report.copy(counters = mapOf("Купить молоко" to 1)),
                report.copy(counters = mapOf("negative" to -1)),
                report.copy(weekStart = "2026-09-29"),
                report.copy(weekStart = "2026-03-02"),
                report.copy(weekStart = "2026-10-12"),
            )
            for (bad in invalid) {
                val response = client.sendMetrics(token, bad)
                assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
                assertThat(response.body<ErrorBody>().code).isEqualTo(ErrorCodes.INVALID_REQUEST)
            }
            val notANumber = client.post(ProxyProtocol.METRICS_PATH) {
                bearerAuth(token)
                setBody(
                    TextContent(
                        "{\"installId\":\"$INSTALL_ID\",\"weekStart\":\"2026-09-28\",\"values\":{\"x\":NaN}}",
                        ContentType.Application.Json,
                    ),
                )
            }
            assertThat(notANumber.status).isEqualTo(HttpStatusCode.BadRequest)
        }
    }

    @Test
    fun `current week is accepted`() {
        TestBackend().test { client ->
            val token = client.installToken()
            assertThat(client.sendMetrics(token, report.copy(weekStart = "2026-10-05")).status).isEqualTo(HttpStatusCode.NoContent)
        }
    }

    @Test
    fun `metrics need a token of an active install`() {
        val backend = TestBackend()
        backend.test { client ->
            assertThat(client.sendMetrics(null, report).status).isEqualTo(HttpStatusCode.Unauthorized)

            val token = client.installToken()
            backend.database.transaction { it.update("UPDATE installs SET blocked = TRUE WHERE install_id = ?", INSTALL_ID) }
            assertThat(client.sendMetrics(token, report).status).isEqualTo(HttpStatusCode.Forbidden)
        }
    }

    private suspend fun HttpClient.sendMetrics(token: String?, report: MetricsReport): HttpResponse = post(ProxyProtocol.METRICS_PATH) {
        token?.let { bearerAuth(it) }
        contentType(ContentType.Application.Json)
        setBody(report)
    }
}
