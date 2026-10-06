package app.tasker.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start (NFR "Отклик": at most 1.5 s on the reference phone), with and without the Baseline Profile, so a
 * regression or a stale profile shows up in the nightly run. `timeToInitialDisplayMs` is the first frame;
 * `timeToFullDisplayMs` is the app's readiness mark (`ReportDrawnWhen` in `TaskerApp`): the frame with the input line.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupWithoutCompilation() = startup(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            // Onboarding is passed once; every measured start is a cold start into Today.
            if (!onboarded) {
                startActivityAndWait()
                passOnboarding()
                onboarded = true
                killProcess()
            }
        },
    ) {
        startActivityAndWait()
    }

    private companion object {
        const val ITERATIONS = 10

        /** App data outlives iterations and tests of one run. */
        var onboarded = false
    }
}
