package app.tasker.core.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.tasker.core.domain.notify.NotificationType
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.Deadline
import app.tasker.core.notifications.action.NotificationActionReceiver
import app.tasker.core.testing.TestTimeSource
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class NotificationContentTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val clock = DayClock(TestTimeSource.at())
    private val requests = NotificationRequests(app, clock)
    private val factory = NotificationFactory(app)
    private val manager = app.getSystemService(NotificationManager::class.java)

    @Before
    fun use24HourClock() {
        Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "24")
    }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun deadline(date: String, time: String? = null) = Deadline(date(date), time?.let(LocalTime::parse), time?.let { clock.zone() })

    @Test
    fun `plan notification in English`() {
        val request = requests.planReady(date("2026-10-06"), taskCount = 5, plannedMinutes = 240, freeMinutes = 300, reviewCount = 3)
        assertThat(request.type).isEqualTo(NotificationType.PLAN_READY)
        assertThat(request.key).isEqualTo("plan|2026-10-06")
        assertThat(request.title).isEqualTo("Day plan")
        assertThat(request.text).isEqualTo("Plan is ready: 5 tasks, 4 h of 5 h free, and 3 tasks to review")
        assertThat(requests.planReady(date("2026-10-06"), 1, 45, 294, 0).text).isEqualTo("Plan is ready: 1 task, 45 min of 4 h 54 min free")
    }

    @Test
    @Config(qualifiers = "ru")
    fun `plan notification in Russian with plurals (TTL-5)`() {
        assertThat(requests.planReady(date("2026-10-06"), 5, 240, 300, 3).text)
            .isEqualTo("План готов: 5 задач, 4 ч из 5 ч свободных, и 3 задачи на проверку")
        assertThat(requests.planReady(date("2026-10-06"), 1, 60, 294, 1).text)
            .isEqualTo("План готов: 1 задача, 1 ч из 4 ч 54 мин свободных, и 1 задача на проверку")
        assertThat(requests.planReady(date("2026-10-06"), 22, 60, 60, 11).text)
            .isEqualTo("План готов: 22 задачи, 1 ч из 1 ч свободных, и 11 задач на проверку")
    }

    @Test
    @Config(qualifiers = "uk")
    fun `plan notification in Ukrainian`() {
        assertThat(requests.planReady(date("2026-10-06"), 3, 45, 294, 0).text)
            .isEqualTo("План готовий: 3 задачі, 45 хв з 4 год 54 хв вільних")
    }

    @Test
    @Config(qualifiers = "ru")
    fun `deadline reminder says when the deadline is (NTF-2)`() {
        val today = requests.deadline("t1", "Сдать отчёт", deadline("2026-10-06", "18:00"), "t1|120")
        assertThat(today.title).isEqualTo("Сдать отчёт")
        assertThat(today.text).isEqualTo("Дедлайн сегодня в 18:00")
        assertThat(today.taskId).isEqualTo("t1")
        assertThat(today.deliverBy).isEqualTo(deadline("2026-10-06", "18:00").instantOrNull(clock.zone()))
        assertThat(requests.deadline("t1", "x", deadline("2026-10-07", "09:30"), "k").text).isEqualTo("Дедлайн завтра в 09:30")
        assertThat(requests.deadline("t1", "x", deadline("2026-10-07"), "k").text).isEqualTo("Дедлайн завтра")
        assertThat(requests.deadline("t1", "x", deadline("2026-10-09"), "k").text).isEqualTo("Дедлайн 9 окт.")
        assertThat(requests.deadline("t1", "x", deadline("2026-10-09", "18:00"), "k").text).isEqualTo("Дедлайн 9 окт. в 18:00")
        // A date-only deadline passes at the end of its day.
        assertThat(requests.deadline("t1", "x", deadline("2026-10-09"), "k").deliverBy).isEqualTo(clock.at(date("2026-10-10"), 0))
        // Held back by quiet hours until the morning of the deadline: worded for that morning.
        val morning = clock.at(date("2026-10-07"), 8 * 60)
        assertThat(requests.deadline("t1", "x", deadline("2026-10-07", "23:30"), "k", deliverAt = morning).text)
            .isEqualTo("Дедлайн сегодня в 23:30")
    }

    @Test
    fun `task notification has Done, Postpone and opens the task (NTF-5)`() {
        val notification = factory.build(requests.deadline("t1", "Report", deadline("2026-10-07", "18:00"), "t1|1440"))

        assertThat(notification.channelId).isEqualTo(NotificationChannels.DEADLINES)
        assertThat(notification.title()).isEqualTo("Report")
        assertThat(notification.text()).isEqualTo("Deadline tomorrow at 18:00")
        assertThat(notification.actions.map { it.title.toString() }).containsExactly("Done", "Postpone").inOrder()

        val done = shadowOf(notification.actions[0].actionIntent)
        assertThat(done.isBroadcastIntent).isTrue()
        assertThat(done.savedIntent.component?.className).isEqualTo(NotificationActionReceiver::class.java.name)
        assertThat(done.savedIntent.action).isEqualTo(NotificationActionReceiver.ACTION_COMPLETE)
        assertThat(done.savedIntent.data.toString()).isEqualTo("tasker://task/t1")

        val postpone = shadowOf(notification.actions[1].actionIntent)
        assertThat(postpone.isActivityIntent).isTrue()
        assertThat(postpone.savedIntent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(postpone.savedIntent.data.toString()).isEqualTo("tasker://postpone/t1")
        assertThat(postpone.savedIntent.`package`).isEqualTo(app.packageName)

        val open = shadowOf(notification.contentIntent)
        assertThat(open.isActivityIntent).isTrue()
        assertThat(open.savedIntent.data.toString()).isEqualTo("tasker://task/t1")
    }

    @Test
    fun `plan notification opens the plan and has no task actions`() {
        val notification = factory.build(requests.planReady(date("2026-10-06"), 2, 120, 300, 0))
        assertThat(notification.channelId).isEqualTo(NotificationChannels.PLAN)
        assertThat(notification.actions).isNull()
        assertThat(shadowOf(notification.contentIntent).savedIntent.data.toString()).isEqualTo("tasker://plan")
    }

    @Test
    fun `quiet-hours group has a summary and children with their own actions`() {
        val children = listOf(
            NotificationRequest(NotificationType.DEADLINE, "a", "Report", "Deadline today", "t1"),
            NotificationRequest(NotificationType.DEADLINE, "b", "Tickets", "Deadline tomorrow", "t2"),
        )
        val summary = factory.groupSummary(children, "group")
        assertThat(summary.group).isEqualTo("group")
        assertThat(summary.flags and Notification.FLAG_GROUP_SUMMARY).isNotEqualTo(0)
        assertThat(summary.text()).isEqualTo("2 notifications")

        val child = factory.build(children.first(), "group")
        assertThat(child.group).isEqualTo("group")
        assertThat(child.actions).hasLength(2)
    }

    @Test
    fun `system poster respects the permission, posts one notification per task and groups`() {
        val poster = SystemNotificationPoster(app, factory)
        val report = NotificationRequest(NotificationType.DEADLINE, "a", "Report", "Deadline today", "t1")
        val tickets = NotificationRequest(NotificationType.DEADLINE, "b", "Tickets", "Deadline tomorrow", "t2")

        assertThat(poster.canPost()).isFalse()
        poster.show(report)
        assertThat(shadowOf(manager).allNotifications).isEmpty()

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(poster.canPost()).isTrue()
        poster.show(report)
        poster.show(report.copy(key = "a2", text = "Deadline in 2 hours"))
        assertThat(shadowOf(manager).allNotifications).hasSize(1)

        poster.showGroup(listOf(report, tickets))
        assertThat(shadowOf(manager).allNotifications).hasSize(3)

        poster.cancelTask("t1")
        assertThat(shadowOf(manager).allNotifications.map { it.title() }).containsExactly("Tickets", "While quiet hours were on")
    }

    @Test
    fun `channels exist with their importance and a blocked channel disables its type`() {
        val poster = SystemNotificationPoster(app, factory)
        assertThat(poster.isChannelEnabled(NotificationType.DEADLINE)).isTrue()
        assertThat(manager.notificationChannels.map { it.id })
            .containsExactly(
                NotificationChannels.PLAN,
                NotificationChannels.DEADLINES,
                NotificationChannels.WEEKLY_REVIEW,
                NotificationChannels.INTEGRATIONS,
            )
        assertThat(
            manager.getNotificationChannel(NotificationChannels.INTEGRATIONS).importance,
        ).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        assertThat(manager.getNotificationChannel(NotificationChannels.PLAN).name.toString()).isEqualTo("Day plan")

        val deadlines = manager.getNotificationChannel(NotificationChannels.DEADLINES)
        deadlines.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(deadlines)
        assertThat(poster.isChannelEnabled(NotificationType.DEADLINE)).isFalse()
        assertThat(poster.isChannelEnabled(NotificationType.PLAN_READY)).isTrue()
    }

    @Test
    fun `deep links round trip`() {
        assertThat(DeepLinks.task("abc").toString()).isEqualTo("tasker://task/abc")
        assertThat(DeepLinks.postpone("abc").toString()).isEqualTo("tasker://postpone/abc")
        assertThat(DeepLinks.plan().toString()).isEqualTo("tasker://plan")
        assertThat(DeepLinks.review().toString()).isEqualTo("tasker://review")
        listOf(DeepLink.Task("a"), DeepLink.Postpone("b"), DeepLink.Plan, DeepLink.Review).forEach {
            assertThat(DeepLinks.parse(DeepLinks.uriOf(it))).isEqualTo(it)
        }
        assertThat(DeepLinks.parse(android.net.Uri.parse("https://example.com/task/1"))).isNull()
        assertThat(DeepLinks.parse(android.net.Uri.parse("tasker://task"))).isNull()
        val intent = DeepLinks.viewIntent(app, DeepLinks.plan())
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.`package`).isEqualTo(app.packageName)
    }
}
