package app.tasker.core.ai

import app.tasker.core.ai.contract.EnrichRoute
import app.tasker.core.ai.contract.EstimateScale
import app.tasker.core.ai.contract.ExtractedFields
import app.tasker.core.ai.contract.SimilarTask
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.EstimateMinutes
import app.tasker.core.model.FieldSource
import app.tasker.core.model.TaskField
import app.tasker.core.testing.aTask
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EnrichRequestFactoryTest {
    private val env = AiTestEnv(settings = AiTestEnv.AI_ON.copy(estimateMinutes = EstimateMinutes(s = 10, m = 45, l = 240)))

    @After
    fun tearDown() = env.close()

    @Test
    fun `request carries the title, a language hint, local time, zone and the user's scale`() = runTest {
        val task = env.add("подготовить квартальный отчёт")

        val request = env.requests.build(task)

        assertThat(request.text).isEqualTo("подготовить квартальный отчёт")
        assertThat(request.language).isEqualTo("ru")
        assertThat(request.now).isEqualTo("2026-10-06T10:00")
        assertThat(request.today).isEqualTo("2026-10-06")
        assertThat(request.timeZone).isEqualTo("Europe/Kyiv")
        assertThat(request.estimateScale).isEqualTo(EstimateScale(10, 45, 240))
        assertThat(request.extracted).isEqualTo(ExtractedFields())
        assertThat(request.similarDone).isEmpty()
        assertThat(EnrichRoute.requestProblems(request)).isEmpty()
    }

    @Test
    fun `now and today follow the clock and the day boundary`() = runTest {
        val task = env.add("позвонить маме")
        env.time.set(java.time.LocalDateTime.of(2026, 10, 7, 1, 30, 42))

        val request = env.requests.build(task)

        // 01:30 is still the logical day of October 6 (04:00 boundary); seconds are not sent.
        assertThat(request.now).isEqualTo("2026-10-07T01:30")
        assertThat(request.today).isEqualTo("2026-10-06")
        assertThat(EnrichRoute.requestProblems(request)).isEmpty()
    }

    @Test
    fun `fields set by the rules or the user are sent as extracted, fields the AI may change are not`() {
        val zone = ZoneId.of("Europe/Kyiv")
        val task = aTask(
            title = "сдать отчёт",
            deadline = Deadline(date("2026-10-20"), LocalTime.of(18, 0), zone),
            estimate = Estimate.M,
            planDate = date("2026-10-08"),
        ).copy(
            fieldSources = mapOf(
                TaskField.DEADLINE to FieldSource.PARSER,
                TaskField.ESTIMATE to FieldSource.USER,
                TaskField.PLAN_DATE to FieldSource.AI,
            ),
        )

        val extracted = EnrichRequestFactory.extractedFields(task)

        assertThat(extracted).isEqualTo(
            ExtractedFields(estimate = "M", deadlineDate = "2026-10-20", deadlineTime = "18:00", planDate = null),
        )
    }

    @Test
    fun `a date-only deadline is sent without time and a field without provenance is not protected`() {
        val task = aTask(title = "x", deadline = Deadline(date("2026-10-20")), estimate = Estimate.S)
            .copy(fieldSources = mapOf(TaskField.DEADLINE to FieldSource.INTEGRATION))

        val extracted = EnrichRequestFactory.extractedFields(task)

        assertThat(extracted).isEqualTo(ExtractedFields(deadlineDate = "2026-10-20"))
    }

    @Test
    fun `parsed fields of a captured task are extracted`() = runTest {
        val task = env.add("подготовить презентацию до 20.10")
        assertThat(task.deadline?.date).isEqualTo(date("2026-10-20"))

        val request = env.requests.build(task)

        assertThat(request.extracted.deadlineDate).isEqualTo("2026-10-20")
        assertThat(request.text).doesNotContain("20.10")
    }

    @Test
    fun `up to five similar completed tasks are sent with size and actual minutes`() = runTest {
        repeat(7) { index ->
            val done = env.add("написать пост про корутины $index")
            env.tasks.start(done.id)
            env.time.advance(Duration.ofMinutes(30L + index))
            env.tasks.complete(done.id)
        }
        env.add("купить хлеб").also { env.tasks.complete(it.id) }
        val task = env.add("написать пост про Flow")

        val similar = env.requests.build(task).similarDone

        assertThat(similar).hasSize(EnrichRoute.MAX_SIMILAR_TASKS)
        assertThat(similar.map { it.text }).doesNotContain("купить хлеб")
        assertThat(similar.all { it.text.startsWith("написать пост про корутины") }).isTrue()
        assertThat(similar.mapNotNull { it.actualMinutes }).hasSize(EnrichRoute.MAX_SIMILAR_TASKS)
        assertThat(similar.mapNotNull { it.actualMinutes }.all { it in 30..36 }).isTrue()
    }

    @Test
    fun `a completed task is not its own example`() = runTest {
        val earlier = env.add("полить цветы")
        env.tasks.complete(earlier.id)
        val task = env.add("полить цветы")
        env.tasks.complete(task.id)

        val similar = env.requests.build(env.task(task.id)).similarDone

        assertThat(similar).containsExactly(SimilarTask(text = "полить цветы", estimate = null, actualMinutes = null))
    }

    @Test
    fun `notes never leave the device`() = runTest {
        val task = env.add("позвонить в банк").let { env.task(it.id).copy(note = "PIN 1234") }

        val request = env.requests.build(task)

        assertThat(request.toString()).doesNotContain("PIN")
    }

    @Test
    fun `long titles are clipped to the route limit without splitting a character`() = runTest {
        val long = "а".repeat(EnrichRoute.MAX_TEXT_LENGTH - 1) + "😀" + "б".repeat(10)
        val task = aTask(title = long)

        val request = env.requests.build(task)

        assertThat(request.text.length).isEqualTo(EnrichRoute.MAX_TEXT_LENGTH - 1)
        assertThat(EnrichRoute.requestProblems(request)).isEmpty()
        assertThat(EnrichRequestFactory.clip("short")).isEqualTo("short")
    }

    @Test
    fun `the connection probe sends a fixed text and nothing from the tasks`() = runTest {
        env.add("секретный проект")

        val probe = env.requests.probe()

        assertThat(probe.text).isEqualTo(EnrichRequestFactory.PROBE_TEXT)
        assertThat(probe.language).isEqualTo("en")
        assertThat(probe.similarDone).isEmpty()
        assertThat(probe.extracted).isEqualTo(ExtractedFields())
        assertThat(EnrichRoute.requestProblems(probe)).isEmpty()
    }

    @Test
    fun `language hint uses distinctive letters, the parser languages and Latin text`() {
        val all = setOf("ru", "uk", "en")
        assertThat(LanguageHint.detect("подготовить отчёт", all)).isEqualTo("ru")
        assertThat(LanguageHint.detect("оновити договір оренди", all)).isEqualTo("uk")
        assertThat(LanguageHint.detect("review Anna's pull request", all)).isEqualTo("en")
        // Cyrillic without distinctive letters: ru or uk, unknown while both are on.
        assertThat(LanguageHint.detect("позвонить в банк", all)).isNull()
        assertThat(LanguageHint.detect("позвонить в банк", setOf("ru", "en"))).isEqualTo("ru")
        assertThat(LanguageHint.detect("написать пост про Flow", setOf("uk", "en"))).isEqualTo("uk")
        // Mostly Latin with a Cyrillic word, mixed distinctive letters, no letters at all.
        assertThat(LanguageHint.detect("fix login bug в api", all)).isEqualTo("en")
        assertThat(LanguageHint.detect("съесть їжу", all)).isNull()
        assertThat(LanguageHint.detect("12:30 #42", all)).isNull()
    }
}
