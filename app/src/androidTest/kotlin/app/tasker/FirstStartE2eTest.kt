package app.tasker

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** First start on a real device: onboarding, the first task, then the four tabs with the task in Inbox. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FirstStartE2eTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = e2eEnvironment()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboardingLeadsToTheTabs() {
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
        compose.onNodeWithText("Inbox").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Call the bank"), TIMEOUT_MS)
        compose.onNodeWithText("Tasks").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Completed tasks appear here"), TIMEOUT_MS)
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
