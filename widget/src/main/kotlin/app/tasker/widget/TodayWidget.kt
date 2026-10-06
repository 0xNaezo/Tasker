package app.tasker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.PreviewSizeMode
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.SquareIconButton
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.tasker.core.designsystem.theme.TaskerColorSchemes
import app.tasker.core.model.CaptureChannel
import app.tasker.core.notifications.DeepLinks
import app.tasker.core.ui.R as UiR
import app.tasker.feature.capture.CaptureIntents
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/**
 * The home screen widget (tech plan §13): "+ Task" and the microphone open quick capture in one tap (CAP-6), the
 * list shows what Today shows. With the app lock on, capture still works but no task is shown.
 */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(SIZES)

    override val previewSizeMode: PreviewSizeMode = SizeMode.Responsive(SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val states = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetStates().observe()
        // The first frame already has data; later changes arrive while the widget session is running.
        val initial = states.first()
        provideContent {
            val state by states.collectAsState(initial)
            WidgetTheme { WidgetContent(state) }
        }
    }

    /** The widget picker (Android 15+) shows sample tasks, never the user's own. */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val sample = WidgetState.Day(
            rows = listOf(
                WidgetRow("sample-1", context.getString(R.string.widget_sample_working), RowMark.IN_PROGRESS),
                WidgetRow("sample-2", context.getString(R.string.widget_sample_overdue), RowMark.OVERDUE),
                WidgetRow("sample-3", context.getString(R.string.widget_sample_planned), RowMark.NONE),
            ),
            planAccepted = true,
            planDone = 1,
            planTotal = SAMPLE_PLAN_SIZE,
        )
        provideContent { WidgetTheme { WidgetContent(sample) } }
    }

    internal companion object {
        val CAPTURE_ONLY = DpSize(110.dp, 40.dp)
        val WITH_LIST = DpSize(180.dp, 110.dp)
        private val SIZES = setOf(CAPTURE_ONLY, WITH_LIST)
        private const val SAMPLE_PLAN_SIZE = 4
    }
}

private val colors = ColorProviders(light = TaskerColorSchemes.light, dark = TaskerColorSchemes.dark)

/** The app's own colours rather than the wallpaper's: the widget looks like the app it opens. */
@Composable
internal fun WidgetTheme(content: @Composable () -> Unit) {
    GlanceTheme(colors = colors, content = content)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {
    fun widgetStates(): WidgetStates
}

@Composable
internal fun WidgetContent(state: WidgetState) {
    val size = LocalSize.current
    if (size.width >= TodayWidget.WITH_LIST.width && size.height >= TodayWidget.WITH_LIST.height) {
        Scaffold(
            titleBar = {
                TitleBar(
                    startIcon = ImageProvider(R.drawable.ic_widget_today),
                    title = LocalContext.current.getString(R.string.widget_title),
                    iconColor = GlanceTheme.colors.primary,
                    modifier = GlanceModifier.clickable(openApp()),
                    actions = {
                        CircleIconButton(
                            imageProvider = ImageProvider(R.drawable.ic_widget_mic),
                            contentDescription = LocalContext.current.getString(R.string.widget_voice),
                            onClick = capture(voice = true),
                            backgroundColor = null,
                            contentColor = GlanceTheme.colors.onSurfaceVariant,
                        )
                        Spacer(GlanceModifier.width(ACTION_GAP.dp))
                        SquareIconButton(
                            imageProvider = ImageProvider(R.drawable.ic_widget_add),
                            contentDescription = LocalContext.current.getString(R.string.widget_add),
                            onClick = capture(voice = false),
                            modifier = GlanceModifier.size(BUTTON_SIZE.dp),
                        )
                        // Keeps the button clear of the launcher's rounded corner.
                        Spacer(GlanceModifier.width(PADDING.dp))
                    },
                )
            },
        ) { Body(state) }
    } else {
        CaptureOnly()
    }
}

@Composable
private fun Body(state: WidgetState) {
    val context = LocalContext.current
    Column(GlanceModifier.fillMaxSize().padding(bottom = PADDING.dp)) {
        when {
            state is WidgetState.Locked -> Message(context.getString(R.string.widget_locked))
            state is WidgetState.Day -> {
                Summary(state)
                if (state.rows.isEmpty()) {
                    val text = if (state.planAccepted && state.planTotal > 0) R.string.widget_plan_done else R.string.widget_empty
                    Message(context.getString(text))
                } else {
                    LazyColumn(GlanceModifier.fillMaxSize()) {
                        items(state.rows) { row -> TaskLine(row) }
                    }
                }
            }
        }
    }
}

/** "2 of 5 done" for an accepted plan; otherwise a link to build it (PLN-9: candidates are not a plan yet). */
@Composable
private fun Summary(state: WidgetState.Day) {
    val context = LocalContext.current
    val style = TextStyle(fontSize = CAPTION_SIZE.sp)
    if (state.planAccepted) {
        Text(
            text = context.getString(R.string.widget_plan_progress, state.planDone, state.planTotal),
            style = style.copy(color = GlanceTheme.colors.onSurfaceVariant),
            maxLines = 1,
            modifier = GlanceModifier.padding(bottom = ROW_GAP.dp),
        )
    } else {
        Text(
            text = context.getString(R.string.widget_build_plan),
            style = style.copy(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = GlanceModifier.padding(bottom = ROW_GAP.dp).clickable(open(DeepLinks.plan())),
        )
    }
}

@Composable
private fun TaskLine(row: WidgetRow) {
    val context = LocalContext.current
    val color = markColor(row.mark)
    val label = when (row.mark) {
        RowMark.IN_PROGRESS -> context.getString(UiR.string.status_in_progress)
        RowMark.PAUSED -> context.getString(UiR.string.status_paused)
        RowMark.OVERDUE -> context.getString(R.string.widget_overdue)
        RowMark.NONE -> null
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = ROW_GAP.dp).clickable(open(DeepLinks.task(row.taskId))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_dot),
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = GlanceModifier.size(DOT_SIZE.dp),
        )
        Spacer(GlanceModifier.width(DOT_GAP.dp))
        Text(
            text = row.title,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = TITLE_SIZE.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        if (label != null) {
            Spacer(GlanceModifier.width(DOT_GAP.dp))
            Text(text = label, style = TextStyle(color = color, fontSize = CAPTION_SIZE.sp), maxLines = 1)
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = TITLE_SIZE.sp), maxLines = 3)
    }
}

/** The smallest widget: only the two capture buttons. */
@Composable
private fun CaptureOnly() {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(CORNER.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A one-row widget can be as low as 40 dp.
            SquareIconButton(
                imageProvider = ImageProvider(R.drawable.ic_widget_add),
                contentDescription = context.getString(R.string.widget_add),
                onClick = capture(voice = false),
                modifier = GlanceModifier.size(COMPACT_BUTTON_SIZE.dp),
            )
            Spacer(GlanceModifier.width(ACTION_GAP.dp))
            CircleIconButton(
                imageProvider = ImageProvider(R.drawable.ic_widget_mic),
                contentDescription = context.getString(R.string.widget_voice),
                onClick = capture(voice = true),
                modifier = GlanceModifier.size(COMPACT_BUTTON_SIZE.dp),
                backgroundColor = GlanceTheme.colors.secondaryContainer,
                contentColor = GlanceTheme.colors.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun markColor(mark: RowMark): ColorProvider = when (mark) {
    RowMark.IN_PROGRESS -> GlanceTheme.colors.primary
    RowMark.PAUSED -> GlanceTheme.colors.secondary
    // Red is reserved for overdue deadlines (DAT-1, tech plan §14.4).
    RowMark.OVERDUE -> ColorProvider(day = TaskerColorSchemes.lightTokens.overdue, night = TaskerColorSchemes.darkTokens.overdue)
    RowMark.NONE -> GlanceTheme.colors.outline
}

@Composable
private fun capture(
    voice: Boolean,
): Action = actionStartActivity(CaptureIntents.quickCapture(LocalContext.current, CaptureChannel.WIDGET, voice))

@Composable
private fun open(uri: android.net.Uri): Action {
    val context = LocalContext.current
    return actionStartActivity(DeepLinks.viewIntent(context, uri))
}

@Composable
private fun openApp(): Action {
    val context = LocalContext.current
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    return if (launch != null) actionStartActivity(launch) else open(DeepLinks.plan())
}

private const val PADDING = 12
private const val BUTTON_SIZE = 48
private const val COMPACT_BUTTON_SIZE = 40
private const val CORNER = 16
private const val ROW_GAP = 4
private const val ACTION_GAP = 4
private const val DOT_SIZE = 8
private const val DOT_GAP = 10
private const val TITLE_SIZE = 14
private const val CAPTION_SIZE = 12
