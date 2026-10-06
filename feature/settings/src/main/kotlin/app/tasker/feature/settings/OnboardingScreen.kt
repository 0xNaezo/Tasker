package app.tasker.feature.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.AppSettings
import app.tasker.core.ui.component.TimeDialog
import app.tasker.core.ui.text.UiText
import app.tasker.core.ui.text.resolve

/**
 * Onboarding, three screens at most (ONB-1): working hours with the plan notification question, calendar access
 * (skippable) and the first task. [firstTask] is the capture line supplied by the app; it reports the saved message.
 */
@Composable
fun OnboardingScreen(
    firstTask: @Composable (onSaved: (UiText) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = step > 0) { step-- }
    val current = settings
    if (current == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val context = LocalContext.current
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { step = STEP_CALENDAR }
    var calendarDenied by rememberSaveable { mutableStateOf(false) }
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) step = STEP_TASK else calendarDenied = true
    }
    // The capture line reports a UiText; it is kept as a plain string to survive configuration changes.
    var saved by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            StepBar(
                step = step,
                onBack = { step-- },
                primaryLabel = when {
                    step == STEP_CALENDAR && !calendarGranted(
                        context,
                    ) && !calendarDenied -> stringResource(R.string.onboarding_calendar_allow)
                    step == STEP_TASK -> stringResource(R.string.onboarding_start)
                    else -> stringResource(R.string.onboarding_next)
                },
                onPrimary = {
                    when (step) {
                        STEP_HOURS -> if (current.notifyPlan && needsNotificationPermission(context)) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            step = STEP_CALENDAR
                        }
                        STEP_CALENDAR -> if (calendarGranted(context) || calendarDenied) {
                            step = STEP_TASK
                        } else {
                            calendarLauncher.launch(Manifest.permission.READ_CALENDAR)
                        }
                        else -> viewModel.finish()
                    }
                },
                secondaryLabel = when {
                    step == STEP_CALENDAR && !calendarGranted(context) && !calendarDenied -> stringResource(R.string.onboarding_skip)
                    step == STEP_TASK && saved == null -> stringResource(R.string.onboarding_skip)
                    else -> null
                },
                onSecondary = { if (step == STEP_CALENDAR) step = STEP_TASK else viewModel.finish() },
            )
        },
    ) { padding ->
        AnimatedContent(targetState = step, label = "onboarding", modifier = Modifier.padding(padding)) { shown ->
            when (shown) {
                STEP_HOURS -> HoursStep(current, viewModel::update)
                STEP_CALENDAR -> CalendarStep(granted = calendarGranted(context), denied = calendarDenied)
                else -> TaskStep(saved) { firstTask { text -> saved = text.resolve(context) } }
            }
        }
    }
}

/** Header with the page's own margins; [content] spans the full width, as list rows bring their own padding. */
@Composable
private fun StepPage(icon: ImageVector, title: String, body: String, content: @Composable () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = TaskerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.m),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.m),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(ICON_SIZE.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyLarge, color = TaskerTheme.colors.muted)
        }
        content()
    }
}

/** Content that is not a list row keeps the page margins. */
@Composable
private fun Padded(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.m),
    ) { content() }
}

@Composable
private fun HoursStep(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    var editing by rememberSaveable { mutableStateOf<HoursField?>(null) }
    StepPage(
        icon = Icons.Outlined.Schedule,
        title = stringResource(R.string.onboarding_hours_title),
        body = stringResource(R.string.onboarding_hours_body),
    ) {
        Column {
            DaysRow(settings.workDays) { days -> update { it.copy(workDays = days) } }
            ValueRow(stringResource(R.string.settings_work_start), timeText(settings.workStartMinutes), { editing = HoursField.START })
            ValueRow(stringResource(R.string.settings_work_end), timeText(settings.workEndMinutes), { editing = HoursField.END })
            SwitchRow(
                title = stringResource(R.string.onboarding_plan_question, timeText(settings.effectivePlanTimeMinutes)),
                subtitle = stringResource(R.string.onboarding_plan_note),
                checked = settings.notifyPlan,
                onChange = { on -> update { it.copy(notifyPlan = on) } },
            )
        }
    }
    editing?.let { field ->
        TimeDialog(
            initial = (if (field == HoursField.START) settings.workStartMinutes else settings.workEndMinutes).toLocalTime(),
            onDismiss = { editing = null },
            onConfirm = { time ->
                editing = null
                update {
                    if (field ==
                        HoursField.START
                    ) {
                        it.copy(workStartMinutes = time.minutesOfDay())
                    } else {
                        it.copy(workEndMinutes = time.minutesOfDay())
                    }
                }
            },
        )
    }
}

private enum class HoursField { START, END }

@Composable
private fun CalendarStep(granted: Boolean, denied: Boolean) {
    StepPage(
        icon = Icons.Outlined.CalendarMonth,
        title = stringResource(R.string.onboarding_calendar_title),
        body = stringResource(R.string.onboarding_calendar_body),
    ) {
        Padded {
            when {
                granted -> Note(Icons.Outlined.CheckCircle, stringResource(R.string.onboarding_calendar_granted))
                denied -> Text(stringResource(R.string.onboarding_calendar_denied), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TaskStep(saved: String?, capture: @Composable () -> Unit) {
    StepPage(
        icon = Icons.Outlined.EditNote,
        title = stringResource(R.string.onboarding_task_title),
        body = stringResource(R.string.onboarding_task_body),
    ) {
        Padded {
            capture()
            saved?.let { Note(Icons.Outlined.CheckCircle, it) }
        }
    }
}

@Composable
private fun Note(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StepBar(
    step: Int,
    onBack: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String?,
    onSecondary: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s),
    ) {
        if (step > 0) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.onboarding_back)) }
        }
        Text(
            text = stringResource(R.string.onboarding_step, step + 1, STEPS),
            style = MaterialTheme.typography.labelLarge,
            color = TaskerTheme.colors.muted,
        )
        Spacer(Modifier.weight(1f))
        secondaryLabel?.let { TextButton(onClick = onSecondary) { Text(it) } }
        Button(onClick = onPrimary) { Text(primaryLabel) }
    }
}

private fun needsNotificationPermission(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

private fun calendarGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

private const val STEP_HOURS = 0
private const val STEP_CALENDAR = 1
private const val STEP_TASK = 2
private const val STEPS = 3
private const val ICON_SIZE = 48
