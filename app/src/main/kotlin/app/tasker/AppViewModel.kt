package app.tasker

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.backup.ImportResult
import app.tasker.core.backup.RestoreCandidate
import app.tasker.core.backup.RestoreService
import app.tasker.core.calendar.CalendarRepository
import app.tasker.core.data.command.UndoService
import app.tasker.core.data.maintenance.MaintenanceRunner
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.BatchId
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.notifications.DeepLink
import app.tasker.core.notifications.NotificationGate
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.action.TaskActions
import app.tasker.core.ui.message.Messenger
import app.tasker.core.ui.message.UserMessage
import app.tasker.core.ui.text.UiText
import app.tasker.core.ui.util.attempt
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the app shows first. */
@Immutable
sealed interface AppStart {
    /** Settings or the backup check are still being read: the splash screen stays. */
    data object Loading : AppStart

    /** App lock is on and the user has not unlocked yet (NFR "Безопасность"). */
    data object Locked : AppStart

    /** The database is empty and a backup was found (tech plan §16). */
    data class Restore(val candidate: RestoreCandidate, val restoring: Boolean) : AppStart

    data object Onboarding : AppStart

    data object Main : AppStart
}

private sealed interface RestoreStep {
    data object Checking : RestoreStep

    data class Offer(val candidate: RestoreCandidate, val restoring: Boolean = false) : RestoreStep

    data object Done : RestoreStep
}

/**
 * The app frame: lock, restore offer, onboarding, links from notifications, the undo snackbar for every command and
 * the catch-up pass on start and on return (tech plan §7.6).
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val maintenance: MaintenanceRunner,
    private val restore: RestoreService,
    private val undo: UndoService,
    private val tasks: TaskRepository,
    private val actions: TaskActions,
    private val notifications: NotificationGate,
    private val calendar: CalendarRepository,
    private val messenger: Messenger,
    private val clock: DayClock,
) : ViewModel() {
    private val restoreStep = MutableStateFlow<RestoreStep>(RestoreStep.Checking)
    private val unlocked = MutableStateFlow(false)
    private val pendingLink = MutableStateFlow<DeepLink?>(null)
    private val postponing = MutableStateFlow<Task?>(null)
    private var stoppedAt: Instant? = null

    /** Messages of all screens for the one snackbar host. */
    val messages: Flow<UserMessage> = messenger.messages

    val start: StateFlow<AppStart> = combine(settings.settings, restoreStep, unlocked) { s, step, isUnlocked ->
        when {
            s.biometricLock && !isUnlocked -> AppStart.Locked
            step is RestoreStep.Checking -> AppStart.Loading
            step is RestoreStep.Offer -> AppStart.Restore(step.candidate, step.restoring)
            !s.onboardingDone -> AppStart.Onboarding
            else -> AppStart.Main
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppStart.Loading)

    /** App lock is on: the window content is hidden in the list of recent apps. */
    val lockEnabled: StateFlow<Boolean> = settings.settings.map { it.biometricLock }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** A link waiting for the main screen (it may arrive while the app is locked). */
    val link: StateFlow<DeepLink?> = pendingLink.asStateFlow()

    /** The task whose postpone choice is shown, from a reminder's "Postpone" (NTF-5). */
    val postponeTask: StateFlow<Task?> = postponing.asStateFlow()

    init {
        viewModelScope.launch {
            val candidate = attempt { restore.findRestorable() }.getOrNull()
            if (candidate != null) {
                restoreStep.value = RestoreStep.Offer(candidate)
            } else {
                restoreStep.value = RestoreStep.Done
                catchUp()
            }
        }
    }

    fun acceptRestore() {
        val offer = restoreStep.value as? RestoreStep.Offer ?: return
        if (offer.restoring) return
        restoreStep.value = offer.copy(restoring = true)
        viewModelScope.launch {
            val restored = attempt { restore.restore(offer.candidate) }.getOrNull()
            when (restored) {
                is ImportResult.Imported -> messenger.info(
                    UiText.Plural(R.plurals.app_restored, restored.summary.tasks, restored.summary.tasks),
                )
                // Data appeared meanwhile (e.g. through "Share"): nothing is replaced silently.
                is ImportResult.NotEmpty -> Unit
                else -> messenger.info(UiText.Res(R.string.app_restore_failed))
            }
            restoreStep.value = RestoreStep.Done
            catchUp()
        }
    }

    fun declineRestore() {
        if (restoreStep.value !is RestoreStep.Offer) return
        restoreStep.value = RestoreStep.Done
        viewModelScope.launch { catchUp() }
    }

    fun unlock() {
        unlocked.value = true
    }

    fun openLink(link: DeepLink) {
        if (link is DeepLink.Postpone) requestPostpone(link.taskId) else pendingLink.value = link
    }

    fun linkHandled() {
        pendingLink.value = null
    }

    fun postpone(option: PostponeOption) {
        val task = postponing.value ?: return
        postponing.value = null
        viewModelScope.launch {
            actions.postpone(task, option)
            notifications.dismiss(task.id)
        }
    }

    fun cancelPostpone() {
        postponing.value = null
    }

    /** The main window became visible: lock again after a pause, catch up on a new day, count the active day. */
    fun onStarted() {
        val since = stoppedAt
        if (since != null && Duration.between(since, clock.now()) >= LOCK_AFTER) unlocked.value = false
        stoppedAt = null
        // Access may have been granted in system settings meanwhile; this also starts the calendar observer.
        calendar.hasPermission()
        if (restoreStep.value == RestoreStep.Done) viewModelScope.launch { catchUp() }
    }

    fun onStopped() {
        stoppedAt = clock.now()
    }

    fun undo(batchId: BatchId) {
        viewModelScope.launch {
            attempt { undo.undoBatch(batchId).value }
                .onSuccess { outcome ->
                    when {
                        outcome.nothingToUndo -> messenger.info(UiText.Res(UiR.string.undo_expired))
                        outcome.conflicts.isNotEmpty() -> messenger.info(UiText.Res(UiR.string.undo_conflicts))
                    }
                }
                .onFailure { messenger.info(UiText.Res(UiR.string.error_generic)) }
        }
    }

    private fun requestPostpone(taskId: TaskId) {
        viewModelScope.launch {
            val task = attempt { tasks.task(taskId) }.getOrNull()
            if (task != null && task.archivedAt == null && task.status.isActive) {
                postponing.value = task
            } else {
                // Done or deleted since the reminder was shown: nothing to postpone.
                notifications.dismiss(taskId)
                messenger.info(UiText.Res(R.string.app_task_gone))
            }
        }
    }

    private suspend fun catchUp() {
        attempt {
            maintenance.recordActivity()
            maintenance.catchUp()
        }.onFailure { Log.w(TAG, "Catch-up failed", it) }
    }

    private companion object {
        const val TAG = "AppViewModel"

        /** A pause longer than this asks to unlock again; a quick switch to another app does not. */
        val LOCK_AFTER: Duration = Duration.ofMinutes(1)
    }
}
