package app.tasker.tools.aieval

import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.ai.contract.RouteSettings
import java.time.LocalDate
import java.util.Locale

/** Markdown report of one run, written to `docs/ai-eval/report-<date>.md` (tech plan §17.6). */
object EvalReport {

    fun render(meta: EvalMeta, settings: RouteSettings, date: LocalDate, summary: EvalSummary): String = buildString {
        val outcomes = summary.outcomes
        val failed = summary.failures.values.sum()
        appendLine("# AI eval: `/v1/enrich`, $date")
        appendLine()
        appendLine("- Dataset: `${meta.version}`, ${outcomes.size} cases. ${meta.status}")
        appendLine(
            "- Model `${settings.model}`, effort `${settings.effort}`, max_tokens ${settings.maxTokens}, " +
                "server-side fallbacks ${if (settings.fallbacks) "on" else "off"}.",
        )
        appendLine(
            "- Context: now ${meta.now}, ${meta.timeZone}; scale S=${meta.scale.s}, M=${meta.scale.m}, L=${meta.scale.l} minutes.",
        )
        appendLine()

        appendLine("## Summary")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("| --- | --- |")
        appendLine("| Calls | ${outcomes.size}: answered ${summary.answered.size}, refused ${summary.refused}, failed $failed |")
        with(summary.estimate) {
            appendLine("| Size accuracy | ${percent(accuracy)} ($correct of $labeled with a reference size) |")
            appendLine("| \"Not sure\" share (size left empty) | ${percent(unsureShare)} ($unsure) |")
            appendLine("| Size accuracy when answered | ${percent(accuracyWhenAnswered)} |")
            appendLine("| Off by one size / by two sizes | $offByOne / $offByTwo |")
            appendLine("| Vague texts left without a size | $vagueLeftEmpty of $vague |")
        }
        appendLine("| Dates fully correct | ${percent(ratio(summary.datesCorrect, summary.answered.size))} (${summary.datesCorrect}) |")
        appendLine("| Latency p50 / p95 | ${seconds(summary.latencyP50Ms)} / ${seconds(summary.latencyP95Ms)} |")
        appendLine("| Cost total / per call | ${usd(summary.costMicroUsd)} / ${usd(perCall(summary.costMicroUsd, outcomes.size))} |")
        appendLine(
            "| Tokens: input / cache read / cache write / output | ${summary.inputTokens} / ${summary.cacheReadTokens} / " +
                "${summary.cacheCreationTokens} / ${summary.outputTokens} |",
        )
        appendLine("| Prompt tokens served from cache | ${percent(summary.cacheReadShare)} |")
        appendLine()

        appendLine("## By language")
        appendLine()
        appendLine("| Language | Cases | Size accuracy | \"Not sure\" | Dates fully correct |")
        appendLine("| --- | --- | --- | --- | --- |")
        for (language in summary.languages) {
            val score = summary.estimateFor(language)
            val (datesCorrect, answered) = summary.datesCorrectFor(language)
            val cases = outcomes.count { it.case.language == language }
            appendLine(
                "| $language | $cases | ${percent(
                    score.accuracy,
                )} | ${percent(score.unsureShare)} | ${percent(ratio(datesCorrect, answered))} |",
            )
        }
        appendLine()

        appendLine("## Dates")
        appendLine()
        appendLine("| Field | Exact match | Precision | Recall | Expected / answered non-empty |")
        appendLine("| --- | --- | --- | --- | --- |")
        listOf("deadlineDate" to summary.deadlineDate, "deadlineTime" to summary.deadlineTime, "planDate" to summary.planDate)
            .forEach { (name, score) ->
                appendLine(
                    "| $name | ${percent(score.accuracy)} | ${percent(score.precision)} | ${percent(score.recall)} | " +
                        "${score.expected} / ${score.predicted} |",
                )
            }
        appendLine()

        appendLine("## Sizes: reference (rows) against answer (columns)")
        appendLine()
        val sizes = listOf("S", "M", "L", null)
        appendLine("| | " + sizes.joinToString(" | ") { it ?: "empty" } + " |")
        appendLine("| --- | --- | --- | --- | --- |")
        for (expected in sizes) {
            appendLine(
                "| ${expected ?: "empty"} | " + sizes.joinToString(" | ") {
                    (summary.confusion[expected to it] ?: 0).toString()
                } + " |",
            )
        }
        appendLine()

        if (summary.refused + failed > 0) {
            appendLine("## Refusals and failures")
            appendLine()
            appendLine("| Outcome | Count |")
            appendLine("| --- | --- |")
            if (summary.refused > 0) appendLine("| refused | ${summary.refused} |")
            summary.failures.forEach { (kind, count) -> appendLine("| ${kind.name.lowercase()} | $count |") }
            appendLine()
        }

        appendLine("## Mismatches")
        appendLine()
        if (summary.mismatches.isEmpty()) {
            appendLine("None.")
        } else {
            appendLine("| Case | Task | Reference | Answer |")
            appendLine("| --- | --- | --- | --- |")
            summary.mismatches.forEach { outcome ->
                appendLine(
                    "| ${outcome.case.id} | ${cell(outcome.case.text)} | ${cell(describe(outcome.case.expected))} | " +
                        "${cell(describe(outcome))} |",
                )
            }
        }
    }

    private fun describe(expected: ExpectedFields): String = fields(
        expected.estimate,
        expected.deadlineDate,
        expected.deadlineTime,
        expected.planDate,
    )

    private fun describe(outcome: EvalOutcome): String = when (val result = outcome.result) {
        is RouteResult.Success -> describe(result.value)
        is RouteResult.Refused -> "refused (${result.category ?: "no category"})"
        is RouteResult.Failed -> "failed: ${result.kind.name.lowercase()}"
    }

    private fun describe(answer: EnrichResponse): String =
        fields(answer.estimate, answer.deadlineDate, answer.deadlineTime, answer.planDate) +
            (answer.estimateConfidence?.let { " (confidence ${String.format(Locale.ROOT, "%.2f", it)})" } ?: "")

    private fun fields(estimate: String?, deadlineDate: String?, deadlineTime: String?, planDate: String?): String = buildList {
        add("size ${estimate ?: "empty"}")
        if (deadlineDate != null) add("deadline $deadlineDate" + (deadlineTime?.let { " $it" } ?: ""))
        if (planDate != null) add("plan $planDate")
    }.joinToString(", ")

    private fun cell(value: String): String = value.replace("|", "\\|").replace("\n", " ")

    private fun percent(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "n/a"

    private fun seconds(ms: Long?): String = ms?.let { String.format(Locale.ROOT, "%.2f s", it / 1000.0) } ?: "n/a"

    private fun usd(microUsd: Long?): String = microUsd?.let { String.format(Locale.ROOT, "$%.4f", it / 1_000_000.0) } ?: "n/a"

    private fun perCall(totalMicroUsd: Long, calls: Int): Long? = if (calls == 0) null else totalMicroUsd / calls
}
