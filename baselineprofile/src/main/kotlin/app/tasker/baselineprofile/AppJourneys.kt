package app.tasker.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** The installed app; benchmarks run on the release build of either channel, which share the id (ADR 0001). */
internal const val PACKAGE_NAME = "io.github.oxnaezo.tasks"

private const val TIMEOUT_MS = 5_000L
private const val ONBOARDING = "When do you work?"
private val FIRST_SCREEN = Pattern.compile("When do you work\\?|Today")

/**
 * First start: the three onboarding steps, the first task through the capture line, then the main frame. Texts are
 * the English ones, the managed device's default locale.
 */
internal fun MacrobenchmarkScope.passOnboarding() {
    // The first screen is either onboarding or, once it was passed, Today.
    device.wait(Until.hasObject(By.text(FIRST_SCREEN)), TIMEOUT_MS)
    if (!device.hasObject(By.text(ONBOARDING))) return
    click("Next")
    dismissPermissionDialog()
    click("Skip")
    device.wait(Until.hasObject(By.text("Add your first task")), TIMEOUT_MS)
    device.findObject(By.clazz("android.widget.EditText"))?.text = "Call the bank tomorrow"
    device.pressEnter()
    device.wait(Until.hasObject(By.text("Saved to Inbox")), TIMEOUT_MS)
    click("Start")
    device.wait(Until.hasObject(By.text("Today")), TIMEOUT_MS)
}

/** The everyday path: Today, the day plan, Inbox with a task card, Tasks and Done. */
internal fun MacrobenchmarkScope.browseTabs() {
    click("Build the plan")
    device.waitForIdle()
    device.pressBack()
    click("Inbox")
    device.wait(Until.hasObject(By.textContains("Call the bank")), TIMEOUT_MS)
    device.findObject(By.textContains("Call the bank"))?.click()
    device.wait(Until.hasObject(By.text("Deadline")), TIMEOUT_MS)
    device.pressBack()
    click("Tasks")
    device.waitForIdle()
    click("Done")
    device.waitForIdle()
    click("Today")
}

private fun MacrobenchmarkScope.click(text: String) {
    device.wait(Until.findObject(By.text(text)), TIMEOUT_MS)?.click()
    device.waitForIdle()
}

/** Android 13+ may ask for notifications when the morning plan is switched on. */
private fun MacrobenchmarkScope.dismissPermissionDialog() {
    device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button")), 1_000L)?.click()
}
