package app.tasker.tools.aieval

import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EnrichRoute
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Test

/** Checks the hand-written dataset itself, so label typos fail in CI instead of skewing a paid run. */
class DatasetTest {

    private val dataset = EvalDataset.load(Path.of(checkNotNull(System.getProperty("tasker.evalDataset"))))

    @Test
    fun `dataset has 200+ cases in ru, uk and en and is marked as a draft`() {
        assertThat(dataset.cases.size).isAtLeast(200)
        val perLanguage = dataset.cases.groupingBy { it.language }.eachCount()
        assertThat(perLanguage.keys).containsExactly("ru", "uk", "en")
        perLanguage.values.forEach { assertThat(it).isAtLeast(60) }
        dataset.cases.forEach { assertWithMessage(it.id).that(it.id).startsWith(it.language + "-") }
        assertThat(dataset.meta.status).startsWith("DRAFT")
        assertThat(LocalDate.parse(dataset.meta.today).dayOfWeek).isEqualTo(DayOfWeek.TUESDAY)
    }

    @Test
    fun `every case is a valid request`() {
        dataset.cases.forEach { case ->
            assertWithMessage(case.id).that(EnrichRoute.requestProblems(case.request(dataset.meta))).isEmpty()
        }
    }

    @Test
    fun `every reference answer passes the route validation unchanged`() {
        dataset.cases.forEach { case ->
            val expected = case.expected
            val perfect = EnrichResponse(
                estimate = expected.estimate,
                estimateConfidence = expected.estimate?.let { 0.9 },
                deadlineDate = expected.deadlineDate,
                deadlineTime = expected.deadlineTime,
                deadlineFragment = expected.deadlineFragment,
                planDate = expected.planDate,
                planDateFragment = expected.planDateFragment,
            )
            assertWithMessage(case.id).that(EnrichRoute.validate(case.request(dataset.meta), perfect)).isEqualTo(perfect)
        }
    }

    @Test
    fun `labels are complete and cover the tricky cases`() {
        dataset.cases.forEach { case ->
            val expected = case.expected
            assertWithMessage(case.id).that(expected.estimate == null || expected.estimate in EnrichRoute.SIZES).isTrue()
            assertWithMessage(case.id).that(expected.deadlineDate == null).isEqualTo(expected.deadlineFragment == null)
            assertWithMessage(case.id).that(expected.planDate == null).isEqualTo(expected.planDateFragment == null)
            assertWithMessage(case.id).that(expected.deadlineTime == null || expected.deadlineDate != null).isTrue()
        }
        val cases = dataset.cases
        assertThat(cases.count { it.expected.deadlineDate != null }).isAtLeast(40)
        assertThat(cases.count { it.expected.planDate != null }).isAtLeast(40)
        assertThat(cases.count { it.expected.deadlineTime != null }).isAtLeast(9)
        assertThat(cases.count { it.expected.estimate == null }).isAtLeast(9)
        assertThat(cases.count { it.similarDone.isNotEmpty() }).isAtLeast(9)
        assertThat(cases.count { it.note?.contains("vague") == true }).isAtLeast(12)
        assertThat(cases.count { it.note == "past day" }).isAtLeast(6)
    }
}
