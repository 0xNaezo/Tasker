package app.tasker.tools.aieval

import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.ModelPricing
import app.tasker.core.ai.contract.RouteResult
import kotlin.math.abs
import kotlin.math.ceil

/** One case run against the model, with the wall time of the call. */
data class EvalOutcome(val case: EvalCase, val result: RouteResult<EnrichResponse>, val latencyMs: Long) {
    /** The answer the app would apply; null when the call was refused or failed. */
    val answer: EnrichResponse? get() = (result as? RouteResult.Success)?.value
}

/** Share helper: null when there is nothing to divide by. */
fun ratio(part: Int, whole: Int): Double? = if (whole == 0) null else part.toDouble() / whole

/**
 * Size accuracy over answered cases with a reference size. "Not sure" means the model left the size empty
 * where the reference has one; [vague] counts references without a size and [vagueLeftEmpty] how many of
 * them the model left empty, as it should.
 */
data class EstimateScore(
    val labeled: Int,
    val correct: Int,
    val unsure: Int,
    val offByOne: Int,
    val offByTwo: Int,
    val vague: Int,
    val vagueLeftEmpty: Int,
) {
    val accuracy: Double? get() = ratio(correct, labeled)
    val unsureShare: Double? get() = ratio(unsure, labeled)
    val accuracyWhenAnswered: Double? get() = ratio(correct, labeled - unsure)

    companion object {
        private val SIZES = listOf("S", "M", "L")

        fun of(answered: List<Pair<EvalCase, EnrichResponse>>): EstimateScore {
            val labeled = answered.filter { (case, _) -> case.expected.estimate != null }
            val vague = answered.filter { (case, _) -> case.expected.estimate == null }
            val distances = labeled.mapNotNull { (case, answer) ->
                answer.estimate?.let { abs(SIZES.indexOf(it) - SIZES.indexOf(case.expected.estimate)) }
            }
            return EstimateScore(
                labeled = labeled.size,
                correct = distances.count { it == 0 },
                unsure = labeled.count { (_, answer) -> answer.estimate == null },
                offByOne = distances.count { it == 1 },
                offByTwo = distances.count { it == 2 },
                vague = vague.size,
                vagueLeftEmpty = vague.count { (_, answer) -> answer.estimate == null },
            )
        }
    }
}

/** Exact-match accuracy of one date field over answered cases, with precision and recall of non-empty values. */
data class FieldScore(val total: Int, val exact: Int, val expected: Int, val predicted: Int, val truePositives: Int) {
    val accuracy: Double? get() = ratio(exact, total)
    val precision: Double? get() = ratio(truePositives, predicted)
    val recall: Double? get() = ratio(truePositives, expected)

    companion object {
        fun of(answered: List<Pair<EvalCase, EnrichResponse>>, expected: (ExpectedFields) -> String?, actual: (EnrichResponse) -> String?) =
            FieldScore(
                total = answered.size,
                exact = answered.count { (case, answer) -> expected(case.expected) == actual(answer) },
                expected = answered.count { (case, _) -> expected(case.expected) != null },
                predicted = answered.count { (_, answer) -> actual(answer) != null },
                truePositives = answered.count { (case, answer) -> actual(answer) != null && expected(case.expected) == actual(answer) },
            )
    }
}

/** Metrics of tech plan §17.6 over a run: size and date accuracy, "not sure" share, latency and cost. */
class EvalSummary(val outcomes: List<EvalOutcome>) {
    val answered: List<Pair<EvalCase, EnrichResponse>> = outcomes.mapNotNull { outcome -> outcome.answer?.let { outcome.case to it } }
    val refused: Int = outcomes.count { it.result is RouteResult.Refused }
    val failures: Map<FailureKind, Int> = outcomes.mapNotNull { (it.result as? RouteResult.Failed)?.kind }.groupingBy { it }.eachCount()

    val estimate: EstimateScore = EstimateScore.of(answered)
    val deadlineDate: FieldScore = FieldScore.of(answered, { it.deadlineDate }, { it.deadlineDate })
    val deadlineTime: FieldScore = FieldScore.of(answered, { it.deadlineTime }, { it.deadlineTime })
    val planDate: FieldScore = FieldScore.of(answered, { it.planDate }, { it.planDate })

    /** Answered cases whose deadline date, deadline time and plan date all match the reference. */
    val datesCorrect: Int = answered.count { (case, answer) -> datesMatch(case.expected, answer) }

    val languages: List<String> = outcomes.map { it.case.language }.distinct()

    fun estimateFor(language: String): EstimateScore = EstimateScore.of(answered.filter { it.first.language == language })

    fun datesCorrectFor(language: String): Pair<Int, Int> {
        val cases = answered.filter { it.first.language == language }
        return cases.count { (case, answer) -> datesMatch(case.expected, answer) } to cases.size
    }

    /** Estimate rows (reference size or null) to answered sizes (or null) with counts. */
    val confusion: Map<Pair<String?, String?>, Int> =
        answered.groupingBy { (case, answer) -> case.expected.estimate to answer.estimate }.eachCount()

    val latencyP50Ms: Long? = percentile(outcomes.map { it.latencyMs }, P50)
    val latencyP95Ms: Long? = percentile(outcomes.map { it.latencyMs }, P95)

    private val usages = outcomes.mapNotNull { it.result.usage }
    val costMicroUsd: Long = usages.sumOf(ModelPricing::costMicroUsd)
    val inputTokens: Long = usages.sumOf { it.totalInputTokens }
    val outputTokens: Long = usages.sumOf { it.totalOutputTokens }
    val cacheReadTokens: Long = usages.sumOf { it.totalCacheReadTokens }
    val cacheCreationTokens: Long = usages.sumOf { it.totalCacheCreationTokens }

    /** Share of prompt tokens served from the prompt cache (input tokens exclude cached ones in the API). */
    val cacheReadShare: Double? get() {
        val prompt = inputTokens + cacheReadTokens + cacheCreationTokens
        return if (prompt == 0L) null else cacheReadTokens.toDouble() / prompt
    }

    /** Cases whose answer differs from the reference in size or any date field, or that got no answer. */
    val mismatches: List<EvalOutcome> = outcomes.filter { outcome ->
        val answer = outcome.answer
        answer == null || answer.estimate != outcome.case.expected.estimate || !datesMatch(outcome.case.expected, answer)
    }

    companion object {
        const val P50 = 50
        const val P95 = 95

        fun datesMatch(expected: ExpectedFields, answer: EnrichResponse): Boolean = expected.deadlineDate == answer.deadlineDate &&
            expected.deadlineTime == answer.deadlineTime &&
            expected.planDate == answer.planDate

        /** Nearest-rank percentile; null for an empty list. */
        fun percentile(values: List<Long>, percent: Int): Long? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val rank = ceil(percent / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}
