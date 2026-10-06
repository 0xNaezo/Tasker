package app.tasker.core.ai

import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.data.ai.AiFill
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Test

class EnrichResponseMapperTest {
    private val zone = ZoneId.of("Europe/Kyiv")

    @Test
    fun `estimate, confidence and a timed deadline in the current zone`() {
        val fill = EnrichResponseMapper.toFill(
            EnrichResponse(
                estimate = "L",
                estimateConfidence = 0.72,
                deadlineDate = "2026-10-31",
                deadlineTime = "18:30",
                deadlineFragment = "до 31 октября в 18:30",
            ),
            zone,
        )

        assertThat(fill).isEqualTo(
            AiFill(
                estimate = Estimate.L,
                estimateConfidence = 0.72,
                deadline = Deadline(date("2026-10-31"), LocalTime.of(18, 30), zone),
            ),
        )
    }

    @Test
    fun `a date-only deadline has no zone and the plan date is a plain date`() {
        val fill = EnrichResponseMapper.toFill(
            EnrichResponse(estimate = "S", deadlineDate = "2026-10-20", planDate = "2026-10-08"),
            zone,
        )

        assertThat(fill.deadline).isEqualTo(Deadline(date("2026-10-20")))
        assertThat(fill.deadline?.zone).isNull()
        assertThat(fill.planDate).isEqualTo(date("2026-10-08"))
        assertThat(fill.estimateConfidence).isNull()
    }

    @Test
    fun `an empty answer fills nothing`() {
        assertThat(EnrichResponseMapper.toFill(EnrichResponse(), zone)).isEqualTo(AiFill())
    }

    @Test
    fun `values that do not parse are ignored`() {
        val fill = EnrichResponseMapper.toFill(
            EnrichResponse(
                estimate = "XL",
                estimateConfidence = 0.9,
                deadlineDate = "2026-13-40",
                deadlineTime = "18:00",
                planDate = "tomorrow",
            ),
            zone,
        )

        assertThat(fill).isEqualTo(AiFill())
    }

    @Test
    fun `a time without a date and a broken time are dropped, confidence is kept within 0 and 1`() {
        val noDate = EnrichResponseMapper.toFill(EnrichResponse(estimate = "M", estimateConfidence = 1.7, deadlineTime = "10:00"), zone)
        assertThat(noDate.deadline).isNull()
        assertThat(noDate.estimateConfidence).isEqualTo(1.0)

        val badTime = EnrichResponseMapper.toFill(EnrichResponse(deadlineDate = "2026-10-20", deadlineTime = "25:61"), zone)
        assertThat(badTime.deadline).isEqualTo(Deadline(date("2026-10-20")))

        val nan = EnrichResponseMapper.toFill(EnrichResponse(estimate = "M", estimateConfidence = Double.NaN), zone)
        assertThat(nan.estimate).isEqualTo(Estimate.M)
        assertThat(nan.estimateConfidence).isNull()
    }
}
