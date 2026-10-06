package app.tasker.lint.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.api.config
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/**
 * DAT-1 and tech plan §14.4: red is reserved for overdue deadlines. Material's error colours are
 * defined once in the design system and must not be used by screens.
 */
class OverdueColorOnly(config: Config) : Rule(config) {
    override val issue = Issue(
        id = javaClass.simpleName,
        severity = Severity.Defect,
        description = "Only the `overdue` token may be red; do not use colorScheme.error in screens.",
        debt = Debt.FIVE_MINS,
    )

    private val allowedFiles: List<String> by config(listOf("Color.kt", "Theme.kt"))

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val selector = expression.selectorExpression?.text ?: return
        if (selector !in ERROR_TOKENS) return
        if (!expression.receiverExpression.text.endsWith("colorScheme")) return
        if (expression.containingKtFile.name in allowedFiles) return
        report(CodeSmell(issue, Entity.from(expression), "colorScheme.$selector is reserved; use TaskerTheme.colors.overdue."))
    }

    private companion object {
        val ERROR_TOKENS = setOf("error", "onError", "errorContainer", "onErrorContainer")
    }
}
