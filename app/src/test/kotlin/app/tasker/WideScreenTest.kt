package app.tasker

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
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

/** A tablet in landscape: the navigation rail, and Inbox with its list and the task card side by side (§14.1). */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class WideScreenTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

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
    fun inboxShowsTheListAndTheCardSideBySide() {
        compose.waitUntilExactlyOneExists(hasText("When do you work?"), TIMEOUT_MS)
        compose.onNodeWithText("Next").performClick()
        compose.waitUntilExactlyOneExists(hasText("Plan around your meetings"), TIMEOUT_MS)
        compose.onNodeWithText("Skip").performClick()
        compose.waitUntilExactlyOneExists(hasText("Add your first task"), TIMEOUT_MS)
        compose.onNode(hasSetTextAction()).performTextInput("Call the bank tomorrow")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntilExactlyOneExists(hasText("Saved to Inbox"), TIMEOUT_MS)
        compose.onNodeWithText("Start").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Build the plan"), TIMEOUT_MS)
        shoot("wide-01-today")

        compose.onNodeWithText("Inbox").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Choose a task to see its card here"), TIMEOUT_MS)
        shoot("wide-02-inbox")

        compose.onNodeWithText("Call the bank").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Deadline"), TIMEOUT_MS)
        // The list stays beside the card, with the tab rail and the capture line.
        compose.onNodeWithText("Choose a task to see its card here").assertDoesNotExist()
        compose.onAllNodesWithText("Call the bank").onFirst().assertExists()
        compose.onNodeWithText("New task…").assertExists()
        compose.onNodeWithText("Tasks").assertExists()
        shoot("wide-03-inbox-card")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("Choose a task to see its card here"), TIMEOUT_MS)

        compose.onNodeWithText("Tasks").performClick()
        compose.waitForIdle()
        shoot("wide-04-tasks")
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
