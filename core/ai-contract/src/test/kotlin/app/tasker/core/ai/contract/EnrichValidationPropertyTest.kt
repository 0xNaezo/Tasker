package app.tasker.core.ai.contract

import com.google.common.truth.Truth.assertThat
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EnrichValidationPropertyTest {

    private val today = LocalDate.parse("2026-10-06")

    @Test
    fun `validated answers never leak fragments or past dates`() = runTest {
        checkAll(
            400,
            Arb.string(0..40),
            Arb.int(-30..400),
            Arb.int(0..45),
            Arb.int(0..45),
            Arb.element(listOf(null, "S", "M", "L", "XL", "m", "")),
            Arb.double(-2.0..2.0),
        ) { text, offsetDays, from, length, estimate, confidence ->
            val fragment = if (from < text.length) text.substring(from, minOf(text.length, from + length)) else "до конца месяца"
            val date = today.plusDays(offsetDays.toLong()).toString()
            val request = EnrichRequest(
                text = text.ifBlank { "задача" },
                language = null,
                now = "2026-10-06T10:00",
                today = today.toString(),
                timeZone = "UTC",
                estimateScale = EstimateScale(15, 60, 180),
                extracted = ExtractedFields(),
            )
            val result = EnrichRoute.validate(
                request,
                EnrichResponse(estimate, confidence, date, "12:00", fragment, date, fragment),
            )
            assertThat(result.estimate == null || result.estimate in EnrichRoute.SIZES).isTrue()
            result.estimateConfidence?.let { assertThat(it).isIn(com.google.common.collect.Range.closed(0.0, 1.0)) }
            listOfNotNull(result.deadlineFragment, result.planDateFragment).forEach { assertThat(request.text).contains(it) }
            listOfNotNull(result.deadlineDate, result.planDate).forEach { assertThat(LocalDate.parse(it)).isAtLeast(today) }
            assertThat(result.planDate != null && result.deadlineDate != null).isFalse()
        }
    }
}
