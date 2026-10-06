package app.tasker.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.core.ai.AiState
import app.tasker.core.backup.ExportService
import app.tasker.core.model.AiMode
import app.tasker.core.model.AppSettings
import app.tasker.core.ui.component.Banner
import app.tasker.core.ui.component.ConfirmDialog
import app.tasker.core.ui.component.SectionHeader
import app.tasker.core.ui.component.TimeDialog
import app.tasker.core.ui.format.Formats

/** Settings (SET-1…SET-3, DATA-1, §14.2). AI help has its own page ([onOpenAi]): it carries the consent text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAi: () -> Unit,
    versionName: String,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    var editor by rememberSaveable { mutableStateOf<Editor?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val settings = state.settings
        val open: (Editor) -> Unit = { editor = it }
        val data = rememberDataActions(viewModel)
        val notificationsAllowed = rememberNotificationPermission()
        val canLock = rememberCanLock()
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            workSection(settings, viewModel::update, open)
            planningSection(settings, open)
            aiSection(state.ai, onOpenAi)
            relevanceSection(settings, open)
            notificationSection(settings, viewModel::update, open, notificationsAllowed)
            calendarSection(settings, viewModel::update)
            languageSection(settings, open)
            privacySection(settings, viewModel::update, canLock)
            dataSection(state.lastBackup, settings.backupTreeUri, data)
            item(key = "about") {
                ValueRow(title = stringResource(R.string.settings_version), value = versionName, onClick = {}, enabled = false)
            }
        }
    }
    editor?.let { current ->
        EditorDialog(
            editor = current,
            settings = state.settings,
            onDismiss = { editor = null },
            onSave = { transform ->
                editor = null
                viewModel.update(transform)
            },
        )
    }
    pendingImport?.let { pending ->
        ConfirmDialog(
            title = stringResource(R.string.settings_import_replace_title),
            text = pluralStringResource(
                R.plurals.settings_import_replace_body,
                pending.summary.tasks,
                Formats.weekdayDate(pending.summary.day),
                pending.summary.tasks,
            ),
            confirmLabel = stringResource(R.string.settings_import_replace),
            onConfirm = { viewModel.importFrom(pending.uri, replace = true) },
            onDismiss = viewModel::cancelImport,
        )
    }
}

internal enum class Editor {
    WORK_START,
    WORK_END,
    DAY_BOUNDARY,
    BUFFER,
    ESTIMATE_S,
    ESTIMATE_M,
    ESTIMATE_L,
    HORIZON,
    WIP,
    POSTPONE,
    INBOX_TRIAGE,
    TTL_INBOX,
    TTL_TODAY_WEEK,
    TTL_SOMEDAY,
    TTL_PROJECT,
    PLAN_TIME,
    REMINDERS,
    REVIEW_DAY,
    REVIEW_TIME,
    QUIET_START,
    QUIET_END,
    DAILY_LIMIT,
    APP_LANGUAGE,
    PARSER_LANGUAGES,
}

private fun LazyListScope.workSection(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, open: (Editor) -> Unit) {
    item(key = "work-header") { SectionHeader(stringResource(R.string.settings_work)) }
    item(key = "work-days") { DaysRow(settings.workDays) { days -> update { it.copy(workDays = days) } } }
    item(key = "work-start") {
        ValueRow(stringResource(R.string.settings_work_start), timeText(settings.workStartMinutes), { open(Editor.WORK_START) })
    }
    item(key = "work-end") {
        ValueRow(stringResource(R.string.settings_work_end), timeText(settings.workEndMinutes), { open(Editor.WORK_END) })
    }
    item(key = "buffer") {
        ValueRow(stringResource(R.string.settings_buffer), stringResource(R.string.settings_percent, settings.bufferPercent), {
            open(Editor.BUFFER)
        })
    }
    item(key = "boundary") {
        ValueRow(
            stringResource(R.string.settings_day_boundary),
            stringResource(R.string.settings_day_boundary_value, timeText(settings.dayBoundaryMinutes)),
            { open(Editor.DAY_BOUNDARY) },
        )
    }
}

private fun LazyListScope.planningSection(settings: AppSettings, open: (Editor) -> Unit) {
    item(key = "planning-header") { SectionHeader(stringResource(R.string.settings_planning)) }
    item(key = "est-s") {
        ValueRow(stringResource(R.string.settings_estimate, "S"), Formats.duration(settings.estimateMinutes.s), { open(Editor.ESTIMATE_S) })
    }
    item(key = "est-m") {
        ValueRow(stringResource(R.string.settings_estimate, "M"), Formats.duration(settings.estimateMinutes.m), { open(Editor.ESTIMATE_M) })
    }
    item(key = "est-l") {
        ValueRow(stringResource(R.string.settings_estimate, "L"), Formats.duration(settings.estimateMinutes.l), { open(Editor.ESTIMATE_L) })
    }
    item(key = "horizon") {
        ValueRow(
            stringResource(
                R.string.settings_horizon,
            ),
            pluralStringResource(R.plurals.settings_days, settings.deadlineHorizonDays, settings.deadlineHorizonDays),
            {
                open(Editor.HORIZON)
            },
        )
    }
    item(key = "wip") { ValueRow(stringResource(R.string.settings_wip), settings.wipLimit.toString(), { open(Editor.WIP) }) }
    item(key = "postpone") {
        ValueRow(stringResource(R.string.settings_postpone_threshold), settings.postponeThreshold.toString(), { open(Editor.POSTPONE) })
    }
    item(key = "triage") {
        ValueRow(stringResource(R.string.settings_inbox_threshold), settings.inboxTriageThreshold.toString(), { open(Editor.INBOX_TRIAGE) })
    }
}

private fun LazyListScope.aiSection(ai: AiState?, onOpenAi: () -> Unit) {
    item(key = "ai-header") { SectionHeader(stringResource(R.string.settings_ai)) }
    item(key = "ai") {
        val value = when {
            ai == null -> ""
            ai.availableModes.isEmpty() -> stringResource(R.string.settings_ai_unavailable)
            !ai.hasConsent -> stringResource(R.string.settings_ai_not_set_up)
            !ai.enabled -> stringResource(R.string.settings_ai_off)
            ai.mode == AiMode.DIRECT && !ai.hasApiKey -> stringResource(R.string.settings_ai_needs_key)
            else -> stringResource(R.string.settings_ai_on)
        }
        ValueRow(stringResource(R.string.settings_ai_switch), value, onOpenAi, Icons.Outlined.AutoAwesome)
    }
}

private fun LazyListScope.relevanceSection(settings: AppSettings, open: (Editor) -> Unit) {
    item(key = "ttl-header") { SectionHeader(stringResource(R.string.settings_relevance)) }
    item(key = "ttl-note") {
        Text(
            stringResource(R.string.settings_relevance_note),
            modifier = Modifier.padding(horizontal = app.tasker.core.designsystem.theme.TaskerTheme.spacing.l),
        )
    }
    listOf(
        Triple("ttl-inbox", R.string.settings_ttl_inbox, settings.ttl.inbox to Editor.TTL_INBOX),
        Triple("ttl-today", R.string.settings_ttl_today_week, settings.ttl.todayWeek to Editor.TTL_TODAY_WEEK),
        Triple("ttl-someday", R.string.settings_ttl_someday, settings.ttl.someday to Editor.TTL_SOMEDAY),
        Triple("ttl-project", R.string.settings_ttl_project, settings.ttl.project to Editor.TTL_PROJECT),
    ).forEach { (key, title, value) ->
        item(key = key) {
            ValueRow(stringResource(title), pluralStringResource(R.plurals.settings_days, value.first, value.first), { open(value.second) })
        }
    }
}

private fun LazyListScope.notificationSection(
    settings: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
    open: (Editor) -> Unit,
    permission: NotificationPermission,
) {
    item(key = "notify-header") { SectionHeader(stringResource(R.string.settings_notifications)) }
    if (!permission.granted) {
        item(key = "notify-permission") {
            Banner(
                text = stringResource(R.string.settings_notifications_denied),
                actionLabel = stringResource(R.string.settings_allow),
                onAction = permission.request,
                modifier = Modifier.padding(horizontal = app.tasker.core.designsystem.theme.TaskerTheme.spacing.l),
            )
        }
    }
    item(key = "notify-plan") {
        SwitchRow(
            title = stringResource(R.string.settings_notify_plan),
            subtitle = stringResource(R.string.settings_at_time, timeText(settings.effectivePlanTimeMinutes)),
            checked = settings.notifyPlan,
            onChange = { on -> update { it.copy(notifyPlan = on) } },
        )
    }
    if (settings.notifyPlan) {
        item(key = "plan-time") {
            ValueRow(stringResource(R.string.settings_plan_time), timeText(settings.effectivePlanTimeMinutes), { open(Editor.PLAN_TIME) })
        }
    }
    item(key = "notify-deadlines") {
        SwitchRow(stringResource(R.string.settings_notify_deadlines), settings.notifyDeadlines, { on ->
            update { it.copy(notifyDeadlines = on) }
        })
    }
    if (settings.notifyDeadlines) {
        item(key = "reminders") {
            ValueRow(stringResource(R.string.settings_reminders), remindersText(settings.deadlineReminderMinutes), {
                open(Editor.REMINDERS)
            })
        }
    }
    item(key = "notify-review") {
        SwitchRow(
            title = stringResource(R.string.settings_notify_review),
            subtitle = stringResource(R.string.settings_review_when, dayName(settings.reviewDay), timeText(settings.reviewTimeMinutes)),
            checked = settings.notifyWeeklyReview,
            onChange = { on -> update { it.copy(notifyWeeklyReview = on) } },
        )
    }
    if (settings.notifyWeeklyReview) {
        item(key = "review-day") {
            ValueRow(stringResource(R.string.settings_review_day), dayName(settings.reviewDay), { open(Editor.REVIEW_DAY) })
        }
        item(key = "review-time") {
            ValueRow(stringResource(R.string.settings_review_time), timeText(settings.reviewTimeMinutes), { open(Editor.REVIEW_TIME) })
        }
    }
    item(key = "quiet-start") {
        ValueRow(stringResource(R.string.settings_quiet_start), timeText(settings.quietStartMinutes), { open(Editor.QUIET_START) })
    }
    item(key = "quiet-end") {
        ValueRow(stringResource(R.string.settings_quiet_end), timeText(settings.quietEndMinutes), { open(Editor.QUIET_END) })
    }
    item(key = "daily-limit") {
        ValueRow(stringResource(R.string.settings_daily_limit), settings.dailyNotificationLimit.toString(), { open(Editor.DAILY_LIMIT) })
    }
}

private fun LazyListScope.calendarSection(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    item(key = "calendar-header") { SectionHeader(stringResource(R.string.settings_calendar)) }
    item(key = "tentative") {
        SwitchRow(
            title = stringResource(R.string.settings_tentative_busy),
            checked = settings.tentativeIsBusy,
            onChange = { on -> update { it.copy(tentativeIsBusy = on) } },
        )
    }
}

private fun LazyListScope.languageSection(settings: AppSettings, open: (Editor) -> Unit) {
    item(key = "language-header") { SectionHeader(stringResource(R.string.settings_language)) }
    item(key = "app-language") {
        ValueRow(stringResource(R.string.settings_app_language), languageName(settings.appLanguage), { open(Editor.APP_LANGUAGE) })
    }
    item(key = "parser-languages") {
        val value =
            settings.parserLanguages?.sorted()?.map { languageName(it) }?.joinToString(", ")
                ?: stringResource(R.string.settings_parser_auto)
        ValueRow(stringResource(R.string.settings_parser_languages), value, { open(Editor.PARSER_LANGUAGES) })
    }
}

private fun LazyListScope.privacySection(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, canLock: Boolean) {
    item(key = "privacy-header") { SectionHeader(stringResource(R.string.settings_privacy)) }
    item(key = "lock") {
        SwitchRow(
            title = stringResource(R.string.settings_biometric),
            subtitle = stringResource(if (canLock) R.string.settings_biometric_note else R.string.settings_biometric_unavailable),
            checked = settings.biometricLock,
            enabled = canLock || settings.biometricLock,
            onChange = { on -> update { it.copy(biometricLock = on) } },
        )
    }
    item(key = "telemetry") {
        SwitchRow(
            title = stringResource(R.string.settings_crash_reports),
            subtitle = stringResource(R.string.settings_crash_reports_note),
            checked = settings.telemetryConsent,
            onChange = { on -> update { it.copy(telemetryConsent = on) } },
        )
    }
}

private fun LazyListScope.dataSection(lastBackup: java.time.LocalDate?, folder: String?, data: DataActions) {
    item(key = "data-header") { SectionHeader(stringResource(R.string.settings_data)) }
    item(key = "export") {
        ValueRow(
            stringResource(R.string.settings_export),
            stringResource(R.string.settings_export_note),
            data.export,
            Icons.Outlined.Upload,
        )
    }
    item(key = "import") {
        ValueRow(
            stringResource(R.string.settings_import),
            stringResource(R.string.settings_import_note),
            data.import,
            Icons.Outlined.Download,
        )
    }
    item(key = "backup") {
        ValueRow(
            title = stringResource(R.string.settings_backup_now),
            value =
                lastBackup?.let { stringResource(R.string.settings_last_backup, Formats.weekdayDate(it)) }
                    ?: stringResource(R.string.settings_no_backup),
            onClick = data.backUpNow,
            icon = Icons.Outlined.Backup,
        )
    }
    item(key = "folder") {
        ValueRow(
            title = stringResource(R.string.settings_backup_folder),
            value = folder?.let { folderName(it) } ?: stringResource(R.string.settings_backup_folder_none),
            onClick = data.pickFolder,
            icon = Icons.Outlined.Folder,
        )
    }
}

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun EditorDialog(editor: Editor, settings: AppSettings, onDismiss: () -> Unit, onSave: ((AppSettings) -> AppSettings) -> Unit) {
    @Composable
    fun time(initial: Int, apply: AppSettings.(Int) -> AppSettings) = TimeDialog(
        initial = initial.toLocalTime(),
        onDismiss = onDismiss,
        onConfirm = { time -> onSave { it.apply(time.minutesOfDay()) } },
    )

    @Composable
    fun number(
        title: Int,
        value: Int,
        range: IntRange,
        step: Int = 1,
        format: @Composable (Int) -> String = { it.toString() },
        apply: AppSettings.(Int) -> AppSettings,
    ) =
        NumberDialog(stringResource(title), value, range, step, format, onDismiss) { v -> onSave { it.apply(v) } }

    val days: @Composable (Int) -> String = { pluralStringResource(R.plurals.settings_days, it, it) }
    val minutes: @Composable (Int) -> String = { Formats.duration(it) }
    when (editor) {
        Editor.WORK_START -> time(settings.workStartMinutes) { copy(workStartMinutes = it) }
        Editor.WORK_END -> time(settings.workEndMinutes) { copy(workEndMinutes = it) }
        Editor.DAY_BOUNDARY -> time(settings.dayBoundaryMinutes) { copy(dayBoundaryMinutes = it) }
        Editor.PLAN_TIME -> time(settings.effectivePlanTimeMinutes) { copy(planTimeMinutes = it) }
        Editor.REVIEW_TIME -> time(settings.reviewTimeMinutes) { copy(reviewTimeMinutes = it) }
        Editor.QUIET_START -> time(settings.quietStartMinutes) { copy(quietStartMinutes = it) }
        Editor.QUIET_END -> time(settings.quietEndMinutes) { copy(quietEndMinutes = it) }
        Editor.BUFFER -> number(R.string.settings_buffer, settings.bufferPercent, 0..MAX_BUFFER, BUFFER_STEP, {
            stringResource(R.string.settings_percent, it)
        }) { copy(bufferPercent = it) }
        Editor.ESTIMATE_S -> number(R.string.settings_estimate_title, settings.estimateMinutes.s, 5..120, 5, minutes) {
            copy(estimateMinutes = estimateMinutes.copy(s = it))
        }
        Editor.ESTIMATE_M -> number(R.string.settings_estimate_title, settings.estimateMinutes.m, 15..480, 15, minutes) {
            copy(estimateMinutes = estimateMinutes.copy(m = it))
        }
        Editor.ESTIMATE_L -> number(R.string.settings_estimate_title, settings.estimateMinutes.l, 30..960, 30, minutes) {
            copy(estimateMinutes = estimateMinutes.copy(l = it))
        }
        Editor.HORIZON -> number(R.string.settings_horizon, settings.deadlineHorizonDays, 0..14, format = days) {
            copy(deadlineHorizonDays = it)
        }
        Editor.WIP -> number(R.string.settings_wip, settings.wipLimit, 1..10) { copy(wipLimit = it) }
        Editor.POSTPONE -> number(R.string.settings_postpone_threshold, settings.postponeThreshold, 2..10) { copy(postponeThreshold = it) }
        Editor.INBOX_TRIAGE -> number(R.string.settings_inbox_threshold, settings.inboxTriageThreshold, 5..100, 5) {
            copy(inboxTriageThreshold = it)
        }
        Editor.TTL_INBOX -> number(R.string.settings_ttl_inbox, settings.ttl.inbox, 1..60, format = days) {
            copy(ttl = ttl.copy(inbox = it))
        }
        Editor.TTL_TODAY_WEEK -> number(R.string.settings_ttl_today_week, settings.ttl.todayWeek, 1..90, format = days) {
            copy(ttl = ttl.copy(todayWeek = it))
        }
        Editor.TTL_SOMEDAY -> number(R.string.settings_ttl_someday, settings.ttl.someday, 7..365, 7, days) {
            copy(ttl = ttl.copy(someday = it))
        }
        Editor.TTL_PROJECT -> number(R.string.settings_ttl_project, settings.ttl.project, 7..180, 7, days) {
            copy(ttl = ttl.copy(project = it))
        }
        Editor.DAILY_LIMIT -> number(R.string.settings_daily_limit, settings.dailyNotificationLimit, 1..10) {
            copy(dailyNotificationLimit = it)
        }
        Editor.REVIEW_DAY -> SingleChoiceDialog(
            title = stringResource(R.string.settings_review_day),
            options = (1..DAYS_IN_WEEK).map { dayName(it) to it },
            selected = settings.reviewDay,
            onDismiss = onDismiss,
            onPick = { day -> onSave { it.copy(reviewDay = day) } },
        )
        Editor.REMINDERS -> MultiChoiceDialog(
            title = stringResource(R.string.settings_reminders),
            options = REMINDER_PRESETS.map { reminderText(it) to it },
            selected = settings.deadlineReminderMinutes.toSet(),
            onDismiss = onDismiss,
            onSave = { chosen -> onSave { it.copy(deadlineReminderMinutes = chosen.sortedDescending()) } },
        )
        Editor.APP_LANGUAGE -> SingleChoiceDialog(
            title = stringResource(R.string.settings_app_language),
            options = listOf<String?>(null, "en", "ru", "uk").map { languageName(it) to it },
            selected = settings.appLanguage,
            onDismiss = onDismiss,
            onPick = { language ->
                onSave { it.copy(appLanguage = language) }
                AppCompatDelegate.setApplicationLocales(
                    if (language == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language),
                )
            },
        )
        Editor.PARSER_LANGUAGES -> MultiChoiceDialog(
            title = stringResource(R.string.settings_parser_languages),
            options = listOf("en", "ru", "uk").map { languageName(it) to it },
            selected = settings.parserLanguages ?: emptySet(),
            onDismiss = onDismiss,
            onSave = { chosen -> onSave { it.copy(parserLanguages = chosen.ifEmpty { null }) } },
        )
    }
}

@Composable
private fun languageName(tag: String?): String = when (tag) {
    null -> stringResource(R.string.settings_language_system)
    "en" -> "English"
    "ru" -> "Русский"
    "uk" -> "Українська"
    else -> tag
}

@Composable
private fun remindersText(minutes: List<Int>): String =
    minutes.sortedDescending().map { reminderText(it) }.joinToString(", ").ifEmpty { stringResource(R.string.settings_reminders_none) }

@Composable
private fun reminderText(minutes: Int): String =
    if (minutes ==
        0
    ) {
        stringResource(R.string.settings_reminder_at)
    } else {
        stringResource(R.string.settings_reminder_before, Formats.duration(minutes))
    }

private fun folderName(uri: String): String = uri.toUri().lastPathSegment?.substringAfterLast(':')?.ifEmpty { null } ?: uri

/** Launchers of the Data section. */
private class DataActions(val export: () -> Unit, val import: () -> Unit, val pickFolder: () -> Unit, val backUpNow: () -> Unit)

@Composable
private fun rememberDataActions(viewModel: SettingsViewModel): DataActions {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportService.MIME_TYPE)) { uri ->
        uri?.let(viewModel::exportTo)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importFrom(it) }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.setBackupFolder(uri.toString())
        }
    }
    return remember(viewModel) {
        DataActions(
            export = { exportLauncher.launch(viewModel.exportFileName()) },
            import = { importLauncher.launch(IMPORT_TYPES) },
            pickFolder = { folderLauncher.launch(null) },
            backUpNow = viewModel::backUpNow,
        )
    }
}

internal class NotificationPermission(val granted: Boolean, val request: () -> Unit)

/** POST_NOTIFICATIONS on Android 13+; re-checked when the screen resumes. */
@Composable
internal fun rememberNotificationPermission(): NotificationPermission {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(notificationsGranted(context)) }
    LifecycleResumeEffect(Unit) {
        granted = notificationsGranted(context)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    return NotificationPermission(granted) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun notificationsGranted(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun rememberCanLock(): Boolean {
    val context = LocalContext.current
    return remember {
        BiometricManager.from(context).canAuthenticate(LOCK_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }
}

/** Biometrics or the device screen lock (the fallback when a fingerprint fails). */
const val LOCK_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

private val IMPORT_TYPES = arrayOf("application/zip", "application/json", "application/gzip", "application/octet-stream")
private val REMINDER_PRESETS = listOf(24 * 60, 2 * 60, 60, 30, 0)
private const val MAX_BUFFER = 80
private const val BUFFER_STEP = 5
private const val DAYS_IN_WEEK = 7
