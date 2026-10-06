package app.tasker.core.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.tasker.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun work(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackupScheduler.WORK_NAME).get()

    @Test
    fun `the backup is scheduled once a day while the battery is not low, first at night`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val scheduler = BackupScheduler(context, TestClock().clock) // Tuesday 2026-10-06 10:00, Kyiv

        scheduler.schedule()
        scheduler.schedule()

        val info = work().single()
        assertThat(info.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(info.constraints.requiresBatteryNotLow()).isTrue()
        assertThat(info.periodicityInfo?.repeatIntervalMillis).isEqualTo(TimeUnit.HOURS.toMillis(24))
        assertThat(info.initialDelayMillis).isEqualTo(TimeUnit.HOURS.toMillis(17))

        scheduler.cancel()

        assertThat(work().single().state).isEqualTo(WorkInfo.State.CANCELLED)
    }

    @Test
    fun `the first run waits for the next night`() {
        val zone = ZoneId.of("Europe/Kyiv")
        fun delay(local: String) = BackupScheduler.initialDelay(LocalDateTime.parse(local).atZone(zone).toInstant(), zone)

        assertThat(delay("2026-10-06T10:00")).isEqualTo(Duration.ofHours(17))
        assertThat(delay("2026-10-06T02:00")).isEqualTo(Duration.ofHours(1))
        assertThat(delay("2026-10-06T03:00")).isEqualTo(Duration.ofHours(24))
        assertThat(delay("2026-10-06T23:30")).isEqualTo(Duration.ofMinutes(210))
    }
}
