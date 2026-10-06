package app.tasker.debug

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tasker.core.domain.time.DayClock
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class DebugClockTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var clock: DayClock

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun `the adb broadcast moves the app clock and the reset brings it back`() {
        val today = clock.today()

        ClockShiftReceiver().onReceive(context, Intent(ACTION).putExtra("days", 14).putExtra("hours", 1))
        assertThat(clock.today()).isAnyOf(today.plusDays(14), today.plusDays(15))

        ClockShiftReceiver().onReceive(context, Intent(ACTION).putExtra("reset", true))
        assertThat(clock.today()).isAnyOf(today, today.plusDays(1))
    }

    private companion object {
        const val ACTION = "app.tasker.debug.SHIFT_CLOCK"
    }
}
