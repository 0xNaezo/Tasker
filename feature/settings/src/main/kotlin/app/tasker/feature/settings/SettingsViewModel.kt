package app.tasker.feature.settings

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.ai.AiController
import app.tasker.core.ai.AiState
import app.tasker.core.backup.BackupResult
import app.tasker.core.backup.BackupService
import app.tasker.core.backup.ExportService
import app.tasker.core.backup.ExportSummary
import app.tasker.core.backup.FolderCopy
import app.tasker.core.backup.ImportProblem
import app.tasker.core.backup.ImportResult
import app.tasker.core.calendar.CalendarRepository
import app.tasker.core.calendar.DeviceCalendar
import app.tasker.core.data.maintenance.MaintenanceRunner
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.data.settings.TelemetryOptions
import app.tasker.core.model.AppSettings
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.message.Messenger
import app.tasker.core.ui.text.UiText
import app.tasker.core.ui.util.attempt
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val lastBackup: LocalDate? = null,
    val ai: AiState? = null,
    val calendar: CalendarState = CalendarState(),
)

/** READ_CALENDAR and the device calendars to choose from (CAL-1). */
@Immutable
data class CalendarState(val granted: Boolean = false, val calendars: List<DeviceCalendar> = emptyList())

/** A file picked for import that needs "Replace all data?" first. */
@Immutable
data class PendingImport(val uri: Uri, val summary: ExportSummary)

/** Settings (SET-1, SET-2) and data (DATA-1, NFR "Сохранность"). The app works fully without opening them. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val export: ExportService,
    private val backup: BackupService,
    private val maintenance: MaintenanceRunner,
    private val messenger: Messenger,
    private val calendar: CalendarRepository,
    ai: AiController,
    /** What the telemetry switch covers in this build. */
    val telemetry: TelemetryOptions,
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val calendarState = calendar.permission.mapLatest { granted ->
        CalendarState(granted, if (granted) calendar.calendars() else emptyList())
    }

    private val lastBackup = MutableStateFlow<LocalDate?>(null)
    private val pending = MutableStateFlow<PendingImport?>(null)

    val pendingImport: StateFlow<PendingImport?> = pending.asStateFlow()

    val state: StateFlow<SettingsUiState> = combine(settings.settings, lastBackup, ai.state, calendarState) { s, last, aiState, cal ->
        SettingsUiState(loading = false, settings = s, lastBackup = last, ai = aiState, calendar = cal)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    init {
        refreshLastBackup()
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { attempt { settings.update(transform) }.onFailure { error() } }
    }

    /** The system dialog of the capture tile closed; a tile already there counts as added. */
    fun tileResult(added: Boolean) {
        if (added) messenger.info(UiText.Res(R.string.settings_tile_added))
    }

    /** After the permission dialog: starts reading busy time without a restart. */
    fun calendarPermissionChanged() {
        calendar.hasPermission()
    }

    /** All calendars chosen is stored as "all" (null), so calendars added later count too. */
    fun selectCalendars(
        keys: Set<String>,
        all: Set<String>,
    ) = update { it.copy(selectedCalendars = keys.takeIf { chosen -> chosen != all }) }

    fun exportFileName(): String = export.exportFileName()

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            attempt { export.exportTo(uri) }
                .onSuccess { messenger.info(UiText.Plural(R.plurals.settings_exported, it.tasks, it.tasks)) }
                .onFailure { error() }
        }
    }

    fun importFrom(uri: Uri, replace: Boolean = false) {
        pending.value = null
        viewModelScope.launch {
            attempt { export.importFrom(uri, replace) }
                .onSuccess { result ->
                    when (result) {
                        is ImportResult.Imported -> {
                            attempt { maintenance.catchUp() }
                            messenger.info(UiText.Plural(R.plurals.settings_imported, result.summary.tasks, result.summary.tasks))
                        }
                        is ImportResult.NotEmpty -> pending.value = PendingImport(uri, result.summary)
                        is ImportResult.Invalid -> messenger.info(UiText.Res(problemText(result.problem)))
                    }
                }
                .onFailure { error() }
        }
    }

    fun cancelImport() {
        pending.value = null
    }

    /** A folder picked with OpenDocumentTree (permission already persisted by the screen): copy right away. */
    fun setBackupFolder(uri: String?) {
        viewModelScope.launch {
            attempt { settings.update { it.copy(backupTreeUri = uri) } }
            if (uri != null) backUpNow()
        }
    }

    fun backUpNow() {
        viewModelScope.launch {
            attempt { backup.backUp() }
                .onSuccess { result ->
                    when (result) {
                        BackupResult.SkippedEmpty -> messenger.info(UiText.Res(R.string.settings_backup_empty))
                        is BackupResult.Written -> messenger.info(UiText.Res(backupText(result.folder)))
                    }
                    refreshLastBackup()
                }
                .onFailure { error() }
        }
    }

    private fun refreshLastBackup() {
        viewModelScope.launch { attempt { backup.backups().firstOrNull()?.day }.onSuccess { lastBackup.value = it } }
    }

    private fun error() = messenger.info(UiText.Res(UiR.string.error_generic))

    private fun backupText(folder: FolderCopy): Int =
        if (folder is FolderCopy.Failed) R.string.settings_backup_folder_failed else R.string.settings_backup_done

    private fun problemText(problem: ImportProblem): Int = when (problem) {
        ImportProblem.UNREADABLE -> R.string.settings_import_unreadable
        ImportProblem.NOT_AN_EXPORT -> R.string.settings_import_not_export
        ImportProblem.NEWER_VERSION -> R.string.settings_import_newer
        ImportProblem.DAMAGED, ImportProblem.TOO_LARGE -> R.string.settings_import_damaged
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
