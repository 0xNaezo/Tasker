package app.tasker.core.domain

import app.tasker.core.domain.postpone.PostponeCounter
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.domain.postpone.PostponePolicy
import app.tasker.core.model.Bucket
import app.tasker.core.testing.aTask
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PostponeTest {
    private val today = date("2026-10-06")

    @Test
    fun `tomorrow sets the plan date and moves Today to Week`() {
        val result = PostponePolicy.apply(aTask(bucket = Bucket.TODAY), PostponeOption.Tomorrow, today, inTodaysPlan = false)
        assertThat(result.planDate).isEqualTo(date("2026-10-07"))
        assertThat(result.bucket).isEqualTo(Bucket.WEEK)
        assertThat(result.countsAsPostpone).isTrue()
    }

    @Test
    fun `week clears a past or today's plan date but keeps a future one`() {
        val past = PostponePolicy.apply(aTask(planDate = today), PostponeOption.Week, today, inTodaysPlan = false)
        assertThat(past.planDate).isNull()
        assertThat(past.bucket).isEqualTo(Bucket.WEEK)
        assertThat(past.countsAsPostpone).isTrue()

        val future = PostponePolicy.apply(aTask(planDate = date("2026-10-09")), PostponeOption.Week, today, inTodaysPlan = false)
        assertThat(future.planDate).isEqualTo(date("2026-10-09"))
        assertThat(future.countsAsPostpone).isFalse()
    }

    @Test
    fun `someday clears the plan date`() {
        val result = PostponePolicy.apply(aTask(bucket = Bucket.WEEK, planDate = today), PostponeOption.Someday, today, false)
        assertThat(result.planDate).isNull()
        assertThat(result.bucket).isEqualTo(Bucket.SOMEDAY)
    }

    @Test
    fun `postponing a task that was not for today does not count`() {
        val result = PostponePolicy.apply(aTask(bucket = Bucket.SOMEDAY), PostponeOption.Tomorrow, today, inTodaysPlan = false)
        assertThat(result.countsAsPostpone).isFalse()
        assertThat(result.bucket).isEqualTo(Bucket.SOMEDAY)
    }

    @Test
    fun `a task in today's plan counts even from the Week bucket`() {
        val result = PostponePolicy.apply(aTask(bucket = Bucket.WEEK), PostponeOption.Tomorrow, today, inTodaysPlan = true)
        assertThat(result.countsAsPostpone).isTrue()
    }

    @Test
    fun `choosing today as the date is not a postpone`() {
        val result = PostponePolicy.apply(aTask(bucket = Bucket.TODAY), PostponeOption.OnDate(today), today, false)
        assertThat(result.countsAsPostpone).isFalse()
        assertThat(result.bucket).isEqualTo(Bucket.TODAY)
    }

    @Test
    fun `counter grows at most once per day`() {
        val once = PostponeCounter.increment(aTask(), today)
        val twice = PostponeCounter.increment(once, today)
        assertThat(once.postponeCount).isEqualTo(1)
        assertThat(twice.postponeCount).isEqualTo(1)
        assertThat(PostponeCounter.increment(twice, today.plusDays(1)).postponeCount).isEqualTo(2)
    }

    @Test
    fun `question after threshold and again after another threshold once kept`() {
        assertThat(PostponeCounter.needsQuestion(aTask(postponeCount = 2), 3)).isFalse()
        assertThat(PostponeCounter.needsQuestion(aTask(postponeCount = 3), 3)).isTrue()
        val kept = aTask(postponeCount = 3).copy(postponePromptedAt = 3)
        assertThat(PostponeCounter.needsQuestion(kept, 3)).isFalse()
        assertThat(PostponeCounter.needsQuestion(kept.copy(postponeCount = 6), 3)).isTrue()
    }
}
