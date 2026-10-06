package app.tasker

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.tasker.core.data.command.CaptureRequest
import app.tasker.core.data.command.CaptureService
import app.tasker.core.data.command.TaskCommands
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.repository.TodayRepository
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.domain.time.ClockShift
import app.tasker.core.domain.time.SystemTimeSource
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.SourceKind
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import app.tasker.core.notifications.DeepLinks
import app.tasker.core.scheduling.plan.MorningPlan
import app.tasker.core.scheduling.plan.MorningPlanResult
import app.tasker.debug.DebugClockModule
import app.tasker.feature.capture.ShareActivity
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Key scenarios of the spec (ТЗ §10, tech plan §22.3) through the whole app: the real Hilt graph, database and
 * screens, with the app's clock pinned to a working Monday morning and moved by days. Scenarios 6 and 7 (relevance
 * and the return after a break) run against the data layer in `core:data` (`AutomationTest`); scenario 8 is GitHub
 * (L4). Scenario 4 pauses with a typed note: voice input is not part of this build.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@UninstallModules(DebugClockModule::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// As wide as Android's preferred dialog width (320dp). On a wider screen Robolectric measures a dialog window at that
// width but lays it out at the full one, so a dialog with text in a field asks for layout again on every pass.
@Config(application = HiltTestApplication::class, qualifiers = "w320dp-h720dp-xhdpi")
class ScenariosTest {
    private val clock = PinnedClock(MONDAY.atTime(8, 30))

    /** Replaces the managed clock of debug builds. */
    @BindValue
    @JvmField
    val shift: ClockShift = clock

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = object : ExternalResource() {
        override fun before() {
            WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
            shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @get:Rule(order = 2)
    val compose = createEmptyComposeRule()

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var capture: CaptureService

    @Inject
    lateinit var commands: TaskCommands

    @Inject
    lateinit var tasks: TaskRepository

    @Inject
    lateinit var today: TodayRepository

    @Inject
    lateinit var morningPlan: MorningPlan

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking { settings.update { it.copy(onboardingDone = true, parserLanguages = setOf("en")) } }
    }

    @Test
    fun `1 quick capture - a message shared from Telegram lands in Inbox with its source and no taps`() {
        val telegram = Uri.parse("android-app://$TELEGRAM")
        share("Call Ivan about the contract", telegram)
        share("Look at this https://t.me/tasker_news/42", telegram)

        val inbox = runBlocking { tasks.observeInbox().first() }
        assertThat(inbox.map { it.title }).containsExactly("Call Ivan about the contract", "Look at this")
        inbox.forEach { assertThat(it.captureChannel).isEqualTo(CaptureChannel.SHARE) }
        val call = sourcesOf(inbox.first { it.title.startsWith("Call") }).single()
        assertThat(call.kind).isEqualTo(SourceKind.APP)
        assertThat(call.appPackage).isEqualTo(TELEGRAM)
        val link = sourcesOf(inbox.first { it.title.startsWith("Look") })
        assertThat(link.map { it.url }).contains("https://t.me/tasker_news/42")
    }

    @Test
    fun `2 morning - the plan notification shows the numbers and opens the plan to accept`() {
        add("Call the bank today")
        add("Write the report today")

        val result = runBlocking { morningPlan.run() }

        assertThat(result).isInstanceOf(MorningPlanResult.Submitted::class.java)
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        // 540 working minutes with the default 30 % buffer leave 6 h 18 min.
        assertThat(notification.extras.getCharSequence("android.text").toString())
            .isEqualTo("Plan is ready: 2 tasks, 2 h of 6 h 18 min free")
        val opened = Intent(shadowOf(notification.contentIntent).savedIntent).setClass(context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(opened).use {
            compose.waitUntilAtLeastOneExists(hasText("Accept the plan"), TIMEOUT_MS)
            compose.onNodeWithText("Accept the plan").performClick()
            waitFor { today.observeToday().first().isAccepted }
        }
    }

    @Test
    fun `3 overload - three tasks that do not fit go to tomorrow in one action`() {
        // Three free hours: 09:00-12:00 without a buffer, and six hours of tasks for today.
        runBlocking { settings.update { it.copy(workEndMinutes = 12 * 60, bufferPercent = 0) } }
        (1..6).forEach { add("Task $it today") }

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasText("Build the plan"), TIMEOUT_MS)
            compose.onNodeWithText("Build the plan").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Doesn't fit"), TIMEOUT_MS)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("All to tomorrow"))
            compose.onNodeWithText("All to tomorrow").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Postponed: 3 tasks"), TIMEOUT_MS)
        }

        val active = runBlocking { tasks.observeActive().first() }
        assertThat(active.filter { it.planDate == MONDAY.plusDays(1) }.map { it.title }).containsExactly("Task 4", "Task 5", "Task 6")
        assertThat(active.filter { it.planDate != MONDAY.plusDays(1) }.map { it.title }).containsExactly("Task 1", "Task 2", "Task 3")
    }

    @Test
    fun `4 interruption - the note left on pause is the first thing on the card three days later`() {
        val task = add("Fix the retries today")
        runBlocking { commands.start(task.id) }

        ActivityScenario.launch<MainActivity>(link(DeepLinks.task(task.id))).use {
            compose.waitUntilAtLeastOneExists(hasText("Pause") and hasClickAction(), TIMEOUT_MS)
            compose.onNode(hasText("Pause") and hasClickAction()).performClick()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput(NOTE)
            compose.onNode(hasText("Pause") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            waitFor { tasks.task(task.id)?.status == TaskStatus.PAUSED }
        }
        clock.advance(Duration.ofDays(3))

        ActivityScenario.launch<MainActivity>(link(DeepLinks.task(task.id))).use {
            compose.waitUntilAtLeastOneExists(hasText(NOTE), TIMEOUT_MS)
            val noteTop = compose.onNodeWithText(NOTE).fetchSemanticsNode().positionInRoot.y
            val titleTop = compose.onNode(hasSetTextAction() and hasText("Fix the retries")).fetchSemanticsNode().positionInRoot.y
            assertThat(noteTop).isLessThan(titleTop)
            compose.onNodeWithText("Where I stopped").assertExists()
        }
    }

    @Test
    fun `5 stuck task - after three postpones the plan asks, and the task is split into steps by hand`() {
        val task = add("Prepare the tax return today")
        // A postpone counts once a day: three days in a row.
        repeat(3) {
            runBlocking { commands.postpone(task.id, PostponeOption.Tomorrow) }
            clock.advance(Duration.ofDays(1))
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasText("Build the plan"), TIMEOUT_MS)
            compose.onNodeWithText("Build the plan").performClick()
            compose.waitUntilAtLeastOneExists(hasScrollAction(), TIMEOUT_MS)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Postponed 3 times"))
            compose.onNode(hasText("Split into steps") and hasClickAction()).performClick()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput(STEPS.joinToString("\n"))
            compose.onNode(hasText("Split") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            waitFor { tasks.observeProjects().first().isNotEmpty() }
        }

        val project = runBlocking { tasks.observeProjects().first() }.single()
        assertThat(project.project.name).isEqualTo("Prepare the tax return")
        val steps = runBlocking { tasks.observeProject(project.project.id).first() }!!.tasks
        assertThat(steps.map { it.title }).containsExactlyElementsIn(STEPS)
        assertThat(runBlocking { tasks.task(task.id) }?.status).isEqualTo(TaskStatus.ARCHIVED)
    }

    /** Waits until [condition] holds in the app's data; the main thread runs between checks, as on a device. */
    private fun waitFor(condition: suspend () -> Boolean) = compose.waitUntil(TIMEOUT_MS) {
        compose.waitForIdle()
        runBlocking { condition() }
    }

    private fun add(text: String): Task = runBlocking { capture.capture(CaptureRequest(text, CaptureChannel.BAR)).value.task }

    private fun share(text: String, referrer: Uri) {
        val intent = Intent(context, ShareActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_REFERRER, referrer)
        val before = runBlocking { tasks.observeInbox().first().size }
        ActivityScenario.launch<ShareActivity>(intent).use {
            waitFor { tasks.observeInbox().first().size > before }
        }
    }

    private fun sourcesOf(task: Task) = runBlocking { tasks.observeDetail(task.id).first() }!!.sources

    private fun link(uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri).setClass(context, MainActivity::class.java)

    /** The app's clock: pinned to [start] when the test begins, then running in real time and moved by [advance]. */
    private class PinnedClock(start: LocalDateTime) : ClockShift {
        private val system = SystemTimeSource()

        @Volatile
        private var offset: Duration = Duration.between(system.now(), start.atZone(system.zone()).toInstant())

        override fun offset(): Duration = offset

        fun advance(by: Duration) {
            offset += by
        }
    }

    private companion object {
        val MONDAY: LocalDate = LocalDate.of(2026, 10, 5)
        const val TELEGRAM = "org.telegram.messenger"
        const val NOTE = "Stopped at the retries, tests next"
        val STEPS = listOf("Collect the receipts", "Fill in the form", "Send it")
        const val TIMEOUT_MS = 10_000L
    }
}
