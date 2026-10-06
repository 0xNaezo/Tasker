package app.tasker.lint.detekt

import com.google.common.truth.Truth.assertThat
import io.github.detekt.test.utils.compileContentForTest
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import org.junit.Test

class TaskerRulesTest {
    @Test
    fun `flags direct system clock reads`() {
        val code = """
            import java.time.Instant
            import java.time.LocalDate
            fun today() = LocalDate.now()
            fun now() = java.time.Instant.now()
            fun millis() = System.currentTimeMillis()
        """.trimIndent()
        assertThat(ForbiddenClockCall(Config.empty).lint(code)).hasSize(3)
    }

    @Test
    fun `allows clock through injected source`() {
        val code = """
            fun today(clock: DayClock) = clock.today()
            fun parse() = java.time.LocalDate.parse("2026-10-06")
        """.trimIndent()
        assertThat(ForbiddenClockCall(Config.empty).lint(code)).isEmpty()
    }

    @Test
    fun `allows the system clock in the time source file`() {
        val code = "class SystemTimeSource { fun now() = java.time.Instant.now() }"
        val file = compileContentForTest(code, "SystemTimeSource.kt")
        assertThat(ForbiddenClockCall(Config.empty).lint(file)).isEmpty()
    }

    @Test
    fun `flags material error colour`() {
        val code = """
            fun color() = MaterialTheme.colorScheme.error
            fun ok() = MaterialTheme.colorScheme.primary
        """.trimIndent()
        assertThat(OverdueColorOnly(Config.empty).lint(code)).hasSize(1)
    }
}
