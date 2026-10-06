package app.tasker.lint.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.api.config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/**
 * Tech plan §5, §7.8: "today" is computed only by the injectable DayClock with the 04:00 day boundary.
 * Reading the system clock directly breaks the "through N days" tests and the logical day.
 */
class ForbiddenClockCall(config: Config) : Rule(config) {
    override val issue = Issue(
        id = javaClass.simpleName,
        severity = Severity.Defect,
        description = "Read time through DayClock/TimeSource, not the system clock.",
        debt = Debt.FIVE_MINS,
    )

    private val allowedFiles: List<String> by config(listOf("SystemTimeSource.kt"))

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val call = expression.selectorExpression as? KtCallExpression ?: return
        val callee = call.calleeExpression?.text ?: return
        val receiver = expression.receiverExpression.text.substringAfterLast('.')
        val signature = "$receiver.$callee"
        if (signature !in FORBIDDEN) return
        if (expression.containingKtFile.baseName in allowedFiles) return
        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "$signature() reads the system clock; inject TimeSource/DayClock instead.",
            ),
        )
    }

    private companion object {
        val FORBIDDEN = setOf(
            "LocalDate.now",
            "LocalDateTime.now",
            "LocalTime.now",
            "ZonedDateTime.now",
            "OffsetDateTime.now",
            "Instant.now",
            "YearMonth.now",
            "Year.now",
            "Clock.systemDefaultZone",
            "Clock.systemUTC",
            "System.currentTimeMillis",
        )
    }
}
