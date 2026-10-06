package app.tasker

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The whole app on Robolectric with the real Hilt graph, database and settings: first start through onboarding, the
 * first task, the four tabs and the secondary screens. Screenshots go to `build/screenshots` for a visual check.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-xhdpi")
class AppSmokeTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    /** WorkManager and permissions must be ready before the activity starts. */
    @get:Rule(order = 1)
    val environment = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstStartLeadsThroughOnboardingToTheTabs() {
        compose.waitUntilExactlyOneExists(hasText("When do you work?"), TIMEOUT_MS)
        shoot("01-onboarding-hours")
        compose.onNodeWithText("Next").performClick()

        compose.waitUntilExactlyOneExists(hasText("Plan around your meetings"), TIMEOUT_MS)
        shoot("02-onboarding-calendar")
        compose.onNodeWithText("Skip").performClick()

        compose.waitUntilExactlyOneExists(hasText("Add your first task"), TIMEOUT_MS)
        compose.onNode(hasSetTextAction()).performTextInput("Call the bank tomorrow")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntilExactlyOneExists(hasText("Saved to Inbox"), TIMEOUT_MS)
        shoot("03-onboarding-task")
        compose.onNodeWithText("Start").performClick()

        compose.waitUntilAtLeastOneExists(hasText("Build the plan"), TIMEOUT_MS)
        shoot("04-today")
        compose.onNodeWithText("Build the plan").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Accept the plan") or hasText("The plan is empty"), TIMEOUT_MS)
        shoot("04b-plan")
        back()

        compose.onNodeWithText("Inbox").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Call the bank"), TIMEOUT_MS)
        shoot("05-inbox")
        compose.onNodeWithText("Call the bank").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Deadline"), TIMEOUT_MS)
        shoot("05b-task")
        back()
        compose.waitUntilAtLeastOneExists(hasText("New task…"), TIMEOUT_MS)

        compose.onNodeWithText("Tasks").performClick()
        compose.waitForIdle()
        shoot("06-tasks")

        compose.onNodeWithText("Done").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Completed tasks appear here"), TIMEOUT_MS)
        shoot("07-done")

        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Working time"), TIMEOUT_MS)
        shoot("08-settings")

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Off until you agree"))
        compose.onNodeWithText("Off until you agree").performClick()
        compose.waitUntilAtLeastOneExists(hasText("What is sent"), TIMEOUT_MS)
        shoot("09-ai")
        compose.onNodeWithText("Agree and turn on").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Anthropic API key"), TIMEOUT_MS)
        compose.onNodeWithText("Add your API key to start").assertExists()
        shoot("10-ai-on")
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun shoot(name: String) {
        val dir = System.getProperty("tasker.screenshots") ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val PNG_QUALITY = 100
    }
}
