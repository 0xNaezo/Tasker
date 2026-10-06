package app.tasker.widget

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the widget's RemoteViews as a launcher would, for every state; saves pictures for a visual check. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class WidgetRenderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val day = WidgetState.Day(
        rows = listOf(
            WidgetRow("1", "Draft the quarterly report", RowMark.IN_PROGRESS),
            WidgetRow("2", "Reply to the landlord", RowMark.PAUSED),
            WidgetRow("3", "Pay the electricity bill", RowMark.OVERDUE),
            WidgetRow("4", "Call the dentist and move the appointment to next week", RowMark.NONE),
            WidgetRow("5", "Buy a birthday present", RowMark.NONE),
        ),
        planAccepted = true,
        planDone = 2,
        planTotal = 6,
    )

    @Test
    fun `accepted plan`() {
        val view = render("plan", day, LARGE)

        assertThat(view.texts()).containsAtLeast("Today", "2 of 6 done", "Draft the quarterly report", "In progress", "Overdue")
    }

    @Test
    fun `candidates without a plan`() {
        val view = render("candidates", day.copy(planAccepted = false), LARGE)

        assertThat(view.texts()).contains("Build the day's plan")
    }

    @Test
    fun `locked app hides tasks`() {
        val view = render("locked", WidgetState.Locked, LARGE)

        assertThat(view.texts()).contains("Tasks are hidden while the app lock is on")
        assertThat(view.texts()).doesNotContain("Pay the electricity bill")
        assertThat(view.descriptions()).containsAtLeast("New task", "New task by voice")
    }

    @Test
    fun `empty day`() {
        val view = render("empty", WidgetState.Day(emptyList(), planAccepted = false, planDone = 0, planTotal = 0), LARGE)

        assertThat(view.texts()).contains("Nothing for today yet")
    }

    @Test
    fun `smallest size keeps only capture`() {
        val view = render("compact", day, TodayWidget.CAPTURE_ONLY)

        assertThat(view.texts().filter { it.isNotBlank() }).isEmpty()
        assertThat(view.descriptions()).containsAtLeast("New task", "New task by voice")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun `dark theme`() {
        val view = render("plan-dark", day, LARGE)

        assertThat(view.texts()).contains("Pay the electricity bill")
    }

    private fun render(name: String, state: WidgetState, size: DpSize): View {
        val widget = object : GlanceAppWidget() {
            override val sizeMode: SizeMode = SizeMode.Exact

            override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
                WidgetTheme { WidgetContent(state) }
            }
        }
        val views = runBlocking { widget.compose(context, size = size) }
        // Since Android 15 list items are applied only inside a widget host view, as on a launcher.
        val parent = AppWidgetHostView(context)
        val view = views.apply(context, parent)
        val density = context.resources.displayMetrics.density
        val width = (size.width.value * density).toInt()
        val height = (size.height.value * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        save(name, view, width, height)
        return view
    }

    private fun save(name: String, view: View, width: Int, height: Int) {
        val dir = System.getProperty("tasker.screenshots") ?: return
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // A launcher-like wallpaper behind the widget, so its own background and corners are visible.
        bitmap.eraseColor(if (RuntimeEnvironment.getQualifiers().contains("night")) WALLPAPER_DARK else WALLPAPER_LIGHT)
        view.draw(Canvas(bitmap))
        File(dir).mkdirs()
        File(dir, "widget-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }

    private fun View.texts(): List<String> = all().filterIsInstance<android.widget.TextView>().map { it.text.toString() }

    private fun View.descriptions(): List<String> = all().mapNotNull { it.contentDescription?.toString() }

    private fun View.all(): List<View> = if (this is android.view.ViewGroup) {
        listOf(this) + (0 until childCount).flatMap { getChildAt(it).all() }
    } else {
        listOf(this)
    }

    private companion object {
        val LARGE = DpSize(300.dp, 220.dp)
        const val WALLPAPER_LIGHT = 0xFF8FA9A0.toInt()
        const val WALLPAPER_DARK = 0xFF1F2B27.toInt()
        const val PNG_QUALITY = 100
    }
}
