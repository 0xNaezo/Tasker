package app.tasker.platform

import com.google.common.truth.Truth.assertThat
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import org.junit.Test

/** Crash reports leave the phone without task text (tech plan §21): only the shape of the failure is kept. */
class CrashReportingTest {
    @Test
    fun `a report keeps the type of the failure and drops every text`() {
        val event = SentryEvent().apply {
            message = Message().apply { formatted = "Saving \"Call the bank\" failed" }
            exceptions = listOf(
                SentryException().apply {
                    type = "IllegalStateException"
                    value = "Task \"Call the bank\" is missing"
                },
            )
            breadcrumbs = listOf(Breadcrumb("Opened \"Call the bank\""))
            user = User().apply { id = "someone" }
            setExtra("title", "Call the bank")
        }

        val sent = event.withoutUserText()

        assertThat(sent.message).isNull()
        assertThat(sent.exceptions?.single()?.type).isEqualTo("IllegalStateException")
        assertThat(sent.exceptions?.single()?.value).isNull()
        assertThat(sent.breadcrumbs).isNull()
        assertThat(sent.user).isNull()
        assertThat(sent.extras.orEmpty()).isEmpty()
    }
}
