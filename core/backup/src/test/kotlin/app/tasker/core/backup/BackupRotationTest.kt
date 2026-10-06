package app.tasker.core.backup

import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class BackupRotationTest {
    private val today = date("2026-10-06") // Tuesday, ISO week 41

    private fun days(from: String, to: String): List<LocalDate> =
        generateSequence(date(from)) { it.plusDays(1) }.takeWhile { !it.isAfter(date(to)) }.toList()

    @Test
    fun `up to seven copies are all kept`() {
        val dates = days("2026-09-30", "2026-10-06")

        assertThat(BackupRotation.keep(dates, today)).containsExactlyElementsIn(dates)
        assertThat(BackupRotation.expired(dates, today)).isEmpty()
    }

    @Test
    fun `seven daily copies and the newest copy of four older ISO weeks are kept`() {
        val dates = days("2026-08-20", "2026-10-06")

        val kept = BackupRotation.keep(dates, today)

        assertThat(kept).containsExactly(
            // daily
            date("2026-10-06"),
            date("2026-10-05"),
            date("2026-10-04"),
            date("2026-10-03"),
            date("2026-10-02"),
            date("2026-10-01"),
            date("2026-09-30"),
            // weekly: newest older copy of weeks 40, 39, 38, 37
            date("2026-09-29"),
            date("2026-09-27"),
            date("2026-09-20"),
            date("2026-09-13"),
        )
        assertThat(BackupRotation.expired(dates, today)).hasSize(dates.size - kept.size)
        assertThat(BackupRotation.expired(dates, today)).containsNoneIn(kept)
    }

    @Test
    fun `gaps between copies are skipped and weeks without copies take no slot`() {
        val dates = listOf("2026-10-06", "2026-10-01", "2026-09-20", "2026-09-02", "2026-08-25", "2026-08-24", "2026-07-01", "2026-06-01")
            .map(::date)

        val kept = BackupRotation.keep(dates, today, daily = 2, weekly = 3)

        // daily: 10-06, 10-01; weekly: 09-20 (week 38), 09-02 (week 36), 08-25 (week 35, newer than 08-24).
        assertThat(kept).containsExactly(date("2026-10-06"), date("2026-10-01"), date("2026-09-20"), date("2026-09-02"), date("2026-08-25"))
    }

    @Test
    fun `weeks follow the ISO week-based year across new year`() {
        // 2026-12-28 … 2027-01-03 is week 53 of 2026: the two older copies share it and only the newer one stays.
        val dates = listOf("2027-01-12", "2027-01-02", "2026-12-29").map(::date)

        val kept = BackupRotation.keep(dates, date("2027-01-12"), daily = 1, weekly = 4)

        assertThat(kept).containsExactly(date("2027-01-12"), date("2027-01-02"))
    }

    @Test
    fun `copies dated in the future are kept and take no slot`() {
        val future = listOf(date("2027-03-01"), date("2026-10-07"))
        val past = days("2026-09-28", "2026-10-06")

        val kept = BackupRotation.keep(past + future, today, daily = 3, weekly = 0)

        assertThat(kept).containsExactly(date("2027-03-01"), date("2026-10-07"), date("2026-10-06"), date("2026-10-05"), date("2026-10-04"))
    }

    @Test
    fun `duplicates and order of the input do not matter`() {
        val dates = days("2026-09-01", "2026-10-06")

        val kept = BackupRotation.keep(dates.shuffled(kotlin.random.Random(42)) + dates.take(5), today)

        assertThat(kept).isEqualTo(BackupRotation.keep(dates, today))
    }

    @Test
    fun `nothing to rotate gives nothing`() {
        assertThat(BackupRotation.keep(emptyList(), today)).isEmpty()
        assertThat(BackupRotation.expired(emptyList(), today)).isEmpty()
    }
}
