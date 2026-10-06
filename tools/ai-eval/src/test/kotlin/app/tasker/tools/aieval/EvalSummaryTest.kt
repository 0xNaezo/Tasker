package app.tasker.tools.aieval

import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EstimateScale
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.ai.contract.RouteSettings
import app.tasker.core.ai.contract.RouteUsage
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EvalSummaryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val meta = EvalMeta(
        version = "test",
        status = "DRAFT",
        now = "2026-10-06T10:00",
        today = "2026-10-06",
        timeZone = "Europe/Kyiv",
        scale = EstimateScale(15, 60, 180),
    )

    /** 1000 input ($0.004) + 200 output ($0.004) + 3000 cache-read ($0.0006) tokens of Claude Opus 5.5. */
    private val usage = RouteUsage("anthropic/claude-opus-5.5", inputTokens = 1000, outputTokens = 200, cacheReadTokens = 3000)

    private fun case(id: String, language: String, estimate: String?, deadline: String? = null, plan: String? = null) = EvalCase(
        id = id,
        language = language,
        text = "task $id | with a pipe",
        expected = ExpectedFields(estimate = estimate, deadlineDate = deadline, deadlineFragment = deadline?.let { "x" }, planDate = plan),
    )

    private fun success(estimate: String?, deadline: String? = null, plan: String? = null) =
        RouteResult.Success(EnrichResponse(estimate = estimate, deadlineDate = deadline, planDate = plan), usage)

    private val outcomes = listOf(
        EvalOutcome(case("a", "ru", "S"), success("S"), 100),
        EvalOutcome(case("b", "ru", "M", deadline = "2026-10-09"), success("M", deadline = "2026-10-09"), 200),
        EvalOutcome(case("c", "uk", "L"), success(null), 300),
        EvalOutcome(case("d", "en", "S", plan = "2026-10-07"), success("L", plan = "2026-10-08"), 400),
        EvalOutcome(case("e", "en", null), success(null), 500),
        EvalOutcome(case("f", "en", "M"), RouteResult.Failed(FailureKind.OVERLOADED), 600),
        EvalOutcome(case("g", "uk", "S"), RouteResult.Refused("cyber"), 700),
    )

    @Test
    fun `metrics of plan section 17_6`() {
        val summary = EvalSummary(outcomes)

        assertThat(summary.answered).hasSize(5)
        assertThat(summary.refused).isEqualTo(1)
        assertThat(summary.failures).containsExactly(FailureKind.OVERLOADED, 1)

        with(summary.estimate) {
            assertThat(listOf(labeled, correct, unsure, offByOne, offByTwo, vague, vagueLeftEmpty))
                .containsExactly(4, 2, 1, 0, 1, 1, 1).inOrder()
            assertThat(accuracy).isEqualTo(0.5)
            assertThat(unsureShare).isEqualTo(0.25)
            assertThat(accuracyWhenAnswered).isWithin(1e-9).of(2.0 / 3)
        }
        assertThat(summary.deadlineDate).isEqualTo(FieldScore(total = 5, exact = 5, expected = 1, predicted = 1, truePositives = 1))
        assertThat(summary.planDate).isEqualTo(FieldScore(total = 5, exact = 4, expected = 1, predicted = 1, truePositives = 0))
        assertThat(summary.planDate.precision).isEqualTo(0.0)
        assertThat(summary.datesCorrect).isEqualTo(4)
        assertThat(summary.datesCorrectFor("en")).isEqualTo(1 to 2)

        assertThat(summary.latencyP50Ms).isEqualTo(400)
        assertThat(summary.latencyP95Ms).isEqualTo(700)
        assertThat(summary.costMicroUsd).isEqualTo(5 * 8_600L)
        assertThat(summary.cacheReadShare).isEqualTo(0.75)
        assertThat(summary.mismatches.map { it.case.id }).containsExactly("c", "d", "f", "g").inOrder()
        assertThat(summary.confusion["S" to "L"]).isEqualTo(1)
    }

    @Test
    fun `report renders the summary as markdown`() {
        val report = EvalReport.render(meta, RouteSettings(), LocalDate.parse("2026-10-07"), EvalSummary(outcomes))

        assertThat(report).startsWith("# AI eval: `/v1/enrich`, 2026-10-07")
        assertThat(report).contains("| Calls | 7: answered 5, refused 1, failed 1 |")
        assertThat(report).contains("| Size accuracy | 50.0% (2 of 4 with a reference size) |")
        assertThat(report).contains("| \"Not sure\" share (size left empty) | 25.0% (1) |")
        assertThat(report).contains("| Latency p50 / p95 | 0.40 s / 0.70 s |")
        assertThat(report).contains("| Cost total / per call | $0.0430 / $0.0061 |")
        assertThat(report).contains("| planDate | 80.0% | 0.0% | 0.0% | 1 / 1 |")
        assertThat(report).contains("| overloaded | 1 |")
        assertThat(report).contains("| d | task d \\| with a pipe | size S, plan 2026-10-07 | size L, plan 2026-10-08 |")
        assertThat(report).contains("| g | task g \\| with a pipe | size S | refused (cyber) |")
    }

    @Test
    fun `percentiles use the nearest rank`() {
        assertThat(EvalSummary.percentile(emptyList(), 50)).isNull()
        assertThat(EvalSummary.percentile(listOf(5), 95)).isEqualTo(5)
        assertThat(EvalSummary.percentile((1L..100L).toList(), 95)).isEqualTo(95)
        assertThat(EvalSummary.percentile((1L..100L).toList(), 50)).isEqualTo(50)
    }

    @Test
    fun `runner keeps dataset order and measures each call`() {
        val time = TestTimeSource()
        val cases = (1..6).map { case("c$it", "ru", "S") }
        val runner = EvalRunner(
            enrich = { request ->
                time += 25.milliseconds
                success(if (request.text.startsWith("task c2")) "M" else "S")
            },
            concurrency = 1,
            timeSource = time,
        )
        val progress = mutableListOf<Int>()

        val result = runner.run(meta, cases) { done, _ -> progress += done }

        assertThat(result.map { it.case.id }).containsExactlyElementsIn(cases.map { it.id }).inOrder()
        assertThat(result.map { it.latencyMs }.distinct()).containsExactly(25L)
        assertThat(result[1].answer?.estimate).isEqualTo("M")
        assertThat(progress).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
        assertThat(EvalRunner({ success("S") }, concurrency = 4).run(meta, cases)).hasSize(6)
    }

    @Test
    fun `options and report file names`() {
        val options = EvalOptions.parse(
            listOf("--model", "anthropic/claude-sonnet-5.5", "--effort", "medium", "--no-zdr", "--limit", "20", "--language", "uk"),
        )
        assertThat(options.settings)
            .isEqualTo(RouteSettings(model = "anthropic/claude-sonnet-5.5", effort = "medium", zeroDataRetention = false))
        assertThat(options.limit).isEqualTo(20)
        assertThat(options.language).isEqualTo("uk")
        listOf(listOf("--bogus"), listOf("--limit"), listOf("--limit", "x"), listOf("--effort", "huge"), listOf("--language", "de"))
            .forEach { args ->
                runCatching {
                    EvalOptions.parse(args)
                }.exceptionOrNull().let { assertThat(it).isInstanceOf(IllegalArgumentException::class.java) }
            }

        val dir = temporaryFolder.root.toPath()
        val date = LocalDate.parse("2026-10-07")
        assertThat(reportFile(dir, date).fileName.toString()).isEqualTo("report-2026-10-07.md")
        Files.writeString(dir.resolve("report-2026-10-07.md"), "first")
        assertThat(reportFile(dir, date).fileName.toString()).isEqualTo("report-2026-10-07-2.md")
    }
}
