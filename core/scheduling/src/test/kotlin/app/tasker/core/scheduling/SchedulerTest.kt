package app.tasker.core.scheduling

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.tasker.core.model.Bucket
import app.tasker.core.model.Deadline
import app.tasker.core.notifications.NotificationChannels
import app.tasker.core.notifications.NotificationFactory
import app.tasker.core.scheduling.maintenance.MaintenanceWorker
import app.tasker.core.scheduling.plan.MorningPlanWorker
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The scheduler facade over WorkManager (test driver, synchronous executors) and fake alarms (tech plan §11.1, §12.2). */
@RunWith(RobolectricTestRunner::class)
class SchedulerTest {
    private val env = SchedulingEnv()
    private val scheduler get() = env.scheduler

    @After
    fun tearDown() = env.close()

    private fun allWork(name: String): List<WorkInfo> = env.workManager.getWorkInfosForUniqueWork(name).get()

    private fun pendingWork(name: String): List<WorkInfo> = allWork(name).filterNot { it.state.isFinished }

    private fun window(start: String, minutes: Long = 10) = AlarmWindow(env.at(start), Duration.ofMinutes(minutes))

    private inline fun <reified W : ListenableWorker> worker(attempt: Int = 0, crossinline create: (Context, WorkerParameters) -> W): W =
        TestListenableWorkerBuilder<W>(env.context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        create(appContext, workerParameters)
                },
            )
            .build()

    @Test
    fun `everything is armed - the morning plan, reminders and the daily catch-up after the day boundary`() = runTest {
        env.addTask("Send the report", Deadline(date("2026-10-08"), LocalTime.of(18, 0), env.clock.zone()))

        scheduler.rescheduleAll()

        // Installed on Tuesday at 10:00: nothing was missed, the first plan comes on Wednesday.
        assertThat(env.alarms.windows[AlarmKind.MORNING_PLAN]).isEqualTo(window("2026-10-07T08:30"))
        assertThat(env.alarms.windows[AlarmKind.DEADLINE_REMINDERS]).isEqualTo(window("2026-10-07T18:00"))
        assertThat(env.alarms.windows).doesNotContainKey(AlarmKind.QUIET_HOURS_END)
        val daily = pendingWork(Scheduler.DAILY_MAINTENANCE_WORK).single()
        assertThat(daily.workerClassName).isEqualTo(MaintenanceWorker::class.java.name)
        assertThat(daily.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(daily.periodicityInfo?.repeatIntervalMillis).isEqualTo(Duration.ofDays(1).toMillis())
        assertThat(daily.initialDelayMillis).isEqualTo(Duration.ofHours(18).plusMinutes(15).toMillis())
        assertThat(Instant.ofEpochMilli(daily.nextScheduleTimeMillis)).isEqualTo(env.at("2026-10-07T04:15"))

        // Registering again keeps aligned work as it is.
        env.setNow("2026-10-06T16:00")
        scheduler.rescheduleAll()
        assertThat(pendingWork(Scheduler.DAILY_MAINTENANCE_WORK).single().id).isEqualTo(daily.id)
    }

    @Test
    fun `a new day boundary realigns the daily catch-up`() = runTest {
        scheduler.rescheduleAll()
        val before = pendingWork(Scheduler.DAILY_MAINTENANCE_WORK).single()

        env.store.updateData { it.copy(dayBoundaryMinutes = 6 * 60) }
        scheduler.rescheduleAll(realignMaintenance = true)

        val after = pendingWork(Scheduler.DAILY_MAINTENANCE_WORK).single()
        assertThat(after.id).isNotEqualTo(before.id)
        assertThat(Instant.ofEpochMilli(after.nextScheduleTimeMillis)).isEqualTo(env.at("2026-10-07T06:15"))
    }

    @Test
    fun `a time zone change catches up and moves every alarm to the new local time`() = runTest {
        scheduler.rescheduleAll()
        val before = pendingWork(Scheduler.DAILY_MAINTENANCE_WORK).single()

        // Tuesday 10:00 in Kyiv is 09:00 in Berlin.
        env.time.setZone(ZoneId.of("Europe/Berlin"))
        scheduler.onSystemEvent(Intent.ACTION_TIMEZONE_CHANGED)

        assertThat(allWork(Scheduler.CATCH_UP_WORK).single().workerClassName).isEqualTo(MaintenanceWorker::class.java.name)
        val after = pendingWork(Scheduler.DAILY_MAINTENANCE_WORK).single()
        assertThat(after.id).isNotEqualTo(before.id)
        assertThat(Instant.ofEpochMilli(after.nextScheduleTimeMillis)).isEqualTo(env.at("2026-10-07T04:15"))
        assertThat(env.alarms.windows[AlarmKind.MORNING_PLAN]).isEqualTo(window("2026-10-07T08:30"))
    }

    @Test
    fun `app start creates the channels, catches up and then follows the settings`() = runTest {
        scheduler.onAppStart()
        scheduler.onAppStart()

        assertThat(allWork(Scheduler.CATCH_UP_WORK)).isNotEmpty()
        val manager = env.context.getSystemService(NotificationManager::class.java)
        assertThat(manager.notificationChannels.map { it.id })
            .containsExactly(
                NotificationChannels.PLAN,
                NotificationChannels.DEADLINES,
                NotificationChannels.WEEKLY_REVIEW,
                NotificationChannels.INTEGRATIONS,
            )
        eventually { env.alarms.windows[AlarmKind.MORNING_PLAN] == window("2026-10-07T08:30") }
        eventually { pendingWork(Scheduler.DAILY_MAINTENANCE_WORK).isNotEmpty() }

        // A setting the alarms depend on moves them.
        env.store.updateData { it.copy(planTimeMinutes = 7 * 60) }
        eventually { env.alarms.windows[AlarmKind.MORNING_PLAN] == window("2026-10-07T07:00") }
        env.store.updateData { it.copy(notifyPlan = false) }
        eventually { AlarmKind.MORNING_PLAN !in env.alarms.windows }
    }

    @Test
    fun `the morning plan alarm starts unique work for the plan worker`() {
        scheduler.enqueueMorningPlan()

        val work = allWork(Scheduler.MORNING_PLAN_WORK).single()
        assertThat(work.workerClassName).isEqualTo(MorningPlanWorker::class.java.name)
        assertThat(work.state).isEqualTo(WorkInfo.State.SUCCEEDED)
    }

    @Test
    fun `the plan worker sends the plan and arms the alarm for the next working day`() = runTest {
        env.setNow("2026-10-06T08:31")
        env.addTask("Write the report", bucket = Bucket.TODAY)
        val notifications = NotificationFactory(env.context)
        val plan = worker { context, params -> MorningPlanWorker(context, params, env.morningPlan, scheduler, notifications) }

        assertThat(plan.doWork()).isEqualTo(ListenableWorker.Result.success())

        assertThat(env.poster.shown.single().text).isEqualTo("Plan is ready: 1 task, 1 h of 6 h 18 min free")
        assertThat(env.alarms.windows[AlarmKind.MORNING_PLAN]).isEqualTo(window("2026-10-07T08:30"))
        // Before Android 12 expedited work runs in the foreground and needs a notification.
        assertThat(plan.getForegroundInfo().notification.channelId).isEqualTo(NotificationChannels.PLAN)
    }

    @Test
    fun `the catch-up worker runs the rules and registers the alarms, retrying a few times on failure`() = runTest {
        val catchUp = worker { context, params -> MaintenanceWorker(context, params, env.maintenance, scheduler) }

        assertThat(catchUp.doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(env.alarms.windows).containsKey(AlarmKind.MORNING_PLAN)
        assertThat(pendingWork(Scheduler.DAILY_MAINTENANCE_WORK)).hasSize(1)

        env.db.close()
        assertThat(catchUp.doWork()).isEqualTo(ListenableWorker.Result.retry())
        val last = worker(attempt = 3) { context, params -> MaintenanceWorker(context, params, env.maintenance, scheduler) }
        assertThat(last.doWork()).isEqualTo(ListenableWorker.Result.failure())
    }
}
