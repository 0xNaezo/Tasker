package app.tasker

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.model.CaptureChannel
import app.tasker.feature.capture.CaptureIntents
import app.tasker.feature.capture.QuickCaptureActivity
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Acceptance: a task from the widget takes at most 2 taps plus the text (tech plan §22.2, NFR "Скорость захвата"). */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class QuickCaptureE2eTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = e2eEnvironment()

    @get:Rule(order = 2)
    val compose = createEmptyComposeRule()

    @Inject
    lateinit var tasks: TaskRepository

    @Test
    fun widgetToSavedTaskInTwoTaps() {
        hilt.inject()
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Tap 1: "+ Task" on the widget opens quick capture with the keyboard up.
        ActivityScenario.launch<QuickCaptureActivity>(CaptureIntents.quickCapture(context, CaptureChannel.WIDGET)).use {
            compose.onNode(hasSetTextAction()).performTextInput("Call the bank tomorrow")
            // Tap 2: Enter saves the task and closes the window.
            compose.onNode(hasSetTextAction()).performImeAction()

            val saved = runBlocking {
                withTimeout(TIMEOUT_MS) { tasks.observeInbox().first { list -> list.any { it.title == "Call the bank" } } }
            }.first { it.title == "Call the bank" }
            assertThat(saved.captureChannel).isEqualTo(CaptureChannel.WIDGET)
            assertThat(saved.planDate).isNotNull()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
