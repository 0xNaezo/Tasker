package app.tasker.core.ai.contract

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EnrichValidationTest {

    private fun request(
        text: String = "подготовить квартальный отчёт до конца месяца",
        now: String = "2026-10-06T10:00",
        today: String = "2026-10-06",
        extracted: ExtractedFields = ExtractedFields(),
    ) = EnrichRequest(
        text = text,
        language = "ru",
        now = now,
        today = today,
        timeZone = "Europe/Kyiv",
        estimateScale = EstimateScale(15, 60, 180),
        extracted = extracted,
    )

    @Test
    fun `estimate is normalised and limited to S M L`() {
        assertThat(EnrichRoute.validate(request(), EnrichResponse(estimate = " m ", estimateConfidence = 0.7)).estimate)
            .isEqualTo("M")
        val invalid = EnrichRoute.validate(request(), EnrichResponse(estimate = "XL", estimateConfidence = 0.9))
        assertThat(invalid.estimate).isNull()
        assertThat(invalid.estimateConfidence).isNull()
    }

    @Test
    fun `confidence is clamped and dropped without an estimate`() {
        assertThat(EnrichRoute.validate(request(), EnrichResponse("L", 1.7)).estimateConfidence).isEqualTo(1.0)
        assertThat(EnrichRoute.validate(request(), EnrichResponse("L", -0.2)).estimateConfidence).isEqualTo(0.0)
        assertThat(EnrichRoute.validate(request(), EnrichResponse("L", Double.NaN)).estimateConfidence).isNull()
        // -0.0 would survive coerceIn(0.0, 1.0) and serialize as "-0.0".
        assertThat(EnrichRoute.validate(request(), EnrichResponse("L", -0.0)).estimateConfidence.toString()).isEqualTo("0.0")
        assertThat(EnrichRoute.validate(request(), EnrichResponse(null, 0.8)).estimateConfidence).isNull()
    }

    @Test
    fun `estimate found by the rules is never overridden`() {
        val result = EnrichRoute.validate(request(extracted = ExtractedFields(estimate = "S")), EnrichResponse("L", 0.9))
        assertThat(result.estimate).isNull()
        assertThat(result.estimateConfidence).isNull()
    }

    @Test
    fun `deadline is kept when its fragment occurs in the text and returned as the exact span`() {
        val result = EnrichRoute.validate(
            request(),
            EnrichResponse(deadlineDate = "2026-10-31", deadlineFragment = "ДО КОНЦА МЕСЯЦА"),
        )
        assertThat(result.deadlineDate).isEqualTo("2026-10-31")
        assertThat(result.deadlineFragment).isEqualTo("до конца месяца")
    }

    @Test
    fun `deadline without its fragment in the text is dropped`() {
        val result = EnrichRoute.validate(
            request(),
            EnrichResponse(deadlineDate = "2026-10-31", deadlineFragment = "by the end of the month"),
        )
        assertThat(result.deadlineDate).isNull()
        assertThat(result.deadlineFragment).isNull()
        assertThat(EnrichRoute.validate(request(), EnrichResponse(deadlineDate = "2026-10-31")).deadlineDate).isNull()
    }

    @Test
    fun `too short or punctuation-only fragments are rejected`() {
        assertThat(EnrichRoute.validate(request(), EnrichResponse(deadlineDate = "2026-10-31", deadlineFragment = "до")).deadlineDate)
            .isNull()
        assertThat(EnrichRoute.validate(request("a ... b"), EnrichResponse(planDate = "2026-10-31", planDateFragment = "...")).planDate)
            .isNull()
    }

    @Test
    fun `dates in the past, unparseable or too far ahead are dropped`() {
        fun deadline(date: String) =
            EnrichRoute.validate(request(), EnrichResponse(deadlineDate = date, deadlineFragment = "до конца месяца")).deadlineDate
        assertThat(deadline("2026-10-05")).isNull()
        assertThat(deadline("31.10.2026")).isNull()
        assertThat(deadline("2036-10-31")).isNull()
        assertThat(deadline("2026-10-06")).isEqualTo("2026-10-06")
    }

    @Test
    fun `a date the rules already extracted is not replaced`() {
        val withDeadline = request(extracted = ExtractedFields(deadlineDate = "2026-10-09", deadlineTime = "18:00"))
        assertThat(
            EnrichRoute.validate(withDeadline, EnrichResponse(deadlineDate = "2026-10-31", deadlineFragment = "до конца месяца"))
                .deadlineDate,
        ).isNull()

        val withPlan = request(text = "позвонить в последний день месяца", extracted = ExtractedFields(planDate = "2026-10-07"))
        assertThat(
            EnrichRoute.validate(withPlan, EnrichResponse(planDate = "2026-10-31", planDateFragment = "в последний день месяца"))
                .planDate,
        ).isNull()
    }

    @Test
    fun `deadline time is normalised and only kept with a deadline date`() {
        val text = "сдать отчёт до конца дня в 18:00"
        val result = EnrichRoute.validate(
            request(text),
            EnrichResponse(deadlineDate = "2026-10-06", deadlineTime = "18:00:00", deadlineFragment = "до конца дня в 18:00"),
        )
        assertThat(result.deadlineTime).isEqualTo("18:00")

        val timeOnly = EnrichRoute.validate(request(text), EnrichResponse(deadlineTime = "18:00", deadlineFragment = "в 18:00"))
        assertThat(timeOnly.deadlineTime).isNull()

        val badTime = EnrichRoute.validate(
            request(text),
            EnrichResponse(deadlineDate = "2026-10-06", deadlineTime = "6pm", deadlineFragment = "до конца дня"),
        )
        assertThat(badTime.deadlineDate).isEqualTo("2026-10-06")
        assertThat(badTime.deadlineTime).isNull()
    }

    @Test
    fun `deadline earlier today than now is dropped`() {
        val result = EnrichRoute.validate(
            request(text = "отправить счёт до конца дня в 9:00", now = "2026-10-06T10:00"),
            EnrichResponse(deadlineDate = "2026-10-06", deadlineTime = "09:00", deadlineFragment = "до конца дня в 9:00"),
        )
        assertThat(result.deadlineDate).isNull()
        assertThat(result.deadlineTime).isNull()
        assertThat(result.deadlineFragment).isNull()
    }

    @Test
    fun `logical day before 4am counts as today`() {
        val result = EnrichRoute.validate(
            request(now = "2026-10-07T01:30", today = "2026-10-06"),
            EnrichResponse(deadlineDate = "2026-10-06", deadlineFragment = "до конца месяца"),
        )
        assertThat(result.deadlineDate).isEqualTo("2026-10-06")
    }

    @Test
    fun `plan date is kept with its own fragment and dropped when it reuses the deadline expression`() {
        val text = "обновить сертификаты в последний день месяца"
        val plan = EnrichRoute.validate(
            request(text),
            EnrichResponse(planDate = "2026-10-31", planDateFragment = "в последний день месяца"),
        )
        assertThat(plan.planDate).isEqualTo("2026-10-31")
        assertThat(plan.planDateFragment).isEqualTo("в последний день месяца")

        val both = EnrichRoute.validate(
            request(),
            EnrichResponse(
                deadlineDate = "2026-10-31",
                deadlineFragment = "до конца месяца",
                planDate = "2026-10-31",
                planDateFragment = "конца месяца",
            ),
        )
        assertThat(both.deadlineDate).isEqualTo("2026-10-31")
        assertThat(both.planDate).isNull()
        assertThat(both.planDateFragment).isNull()
    }

    @Test
    fun `fragment matching folds yo and apostrophes but keeps the original span`() {
        val ru = EnrichRoute.validate(
            request("сдать до четвёртого числа"),
            EnrichResponse(deadlineDate = "2026-11-04", deadlineFragment = "до четвертого числа"),
        )
        assertThat(ru.deadlineFragment).isEqualTo("до четвёртого числа")

        val uk = EnrichRoute.validate(
            request("оплатити рахунок до п’ятниці наступного тижня"),
            EnrichResponse(deadlineDate = "2026-10-16", deadlineFragment = "до п'ятниці наступного тижня"),
        )
        assertThat(uk.deadlineDate).isEqualTo("2026-10-16")
        assertThat(uk.deadlineFragment).isEqualTo("до п’ятниці наступного тижня")
    }

    @Test
    fun `invalid context yields an empty response`() {
        val result = EnrichRoute.validate(request(today = "yesterday"), EnrichResponse("M", 0.8))
        assertThat(result).isEqualTo(EnrichResponse())
        assertThat(result.isEmpty).isTrue()
    }
}
