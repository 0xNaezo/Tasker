package app.tasker.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.domain.capacity.Capacity
import app.tasker.core.domain.capacity.TimeInterval
import app.tasker.core.model.Bucket
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.TaskStatus
import app.tasker.core.testing.aTask
import app.tasker.core.testing.date
import app.tasker.core.ui.component.Banner
import app.tasker.core.ui.component.CapacityBar
import app.tasker.core.ui.component.EmptyState
import app.tasker.core.ui.component.ReasonLabel
import app.tasker.core.ui.component.SectionHeader
import app.tasker.core.ui.component.TaskRow
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference pictures of the shared components (tech plan §22.1): light and dark theme, the three languages and a
 * 200 % font. `./gradlew :core:ui:recordRoborazziDebug` updates them; CI verifies them on every PR.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h1300dp-mdpi")
class ComponentScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lightEnglish() = capture("components_light_en")

    @Test
    @Config(qualifiers = "w400dp-h1300dp-night-mdpi")
    fun darkEnglish() = capture("components_dark_en")

    @Test
    @Config(qualifiers = "ru-w400dp-h1300dp-mdpi")
    fun russian() = capture("components_light_ru")

    @Test
    @Config(qualifiers = "uk-w400dp-h2400dp-mdpi", fontScale = 2f)
    fun ukrainianLargeFont() = capture("components_light_uk_font200")

    private fun capture(name: String) {
        compose.setContent {
            TaskerTheme {
                CompositionLocalProvider(LocalDayContext provides DayContext(today, now, zone)) {
                    Surface { Gallery() }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun Gallery() {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = TaskerTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s),
        ) {
            SectionHeader("Today")
            tasks.forEach { (task, project) ->
                TaskRow(task = task, onClick = {}, onComplete = {}, onPostpone = {}, projectName = project)
            }
            ReasonLabel("Deadline in 2 days", modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l))
            CapacityBar(plannedMinutes = 240, capacity = capacity, modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l))
            CapacityBar(plannedMinutes = 480, capacity = capacity, modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l))
            Banner(
                text = "4 tasks to review",
                icon = Icons.Outlined.FactCheck,
                actionLabel = "Review",
                onAction = {},
                onDismiss = {},
                modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l),
            )
            EmptyState(title = "Nothing for today yet", body = "Add a task in the line below.", icon = Icons.Outlined.WbSunny)
        }
    }

    private companion object {
        val zone: ZoneId = ZoneId.of("Europe/Kyiv")
        val today = date("2026-10-06")
        val now: Instant = Instant.parse("2026-10-06T06:00:00Z")

        val capacity = Capacity(
            day = today,
            window = TimeInterval(Instant.parse("2026-10-06T06:00:00Z"), Instant.parse("2026-10-06T15:00:00Z")),
            workingMin = 540,
            busyMin = 60,
            freeMin = 480,
            capacityMin = 360,
            isWorkDay = true,
            calendarAvailable = true,
        )

        val tasks = listOf(
            aTask(title = "Pay the electricity bill", id = "t1", deadline = Deadline(today.minusDays(1)), estimate = Estimate.S) to null,
            aTask(title = "Draft the quarterly report", id = "t2", status = TaskStatus.IN_PROGRESS, estimate = Estimate.L) to "Work",
            aTask(
                title = "Send the contract",
                id = "t3",
                deadline = Deadline(today.plusDays(2), LocalTime.of(18, 0), zone),
                estimate = Estimate.M,
            ) to "Work",
            aTask(title = "Call the dentist", id = "t4", planDate = today.plusDays(1)).copy(tags = listOf("health")) to null,
            aTask(title = "Learn Spanish", id = "t5", bucket = Bucket.SOMEDAY, inReview = true) to null,
            aTask(title = "Buy a birthday present", id = "t6", status = TaskStatus.DONE).copy(completedAt = now) to null,
        )
    }
}
