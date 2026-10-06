package app.tasker.lint.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class TaskerRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "tasker"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            ForbiddenClockCall(config),
            OverdueColorOnly(config),
        ),
    )
}
