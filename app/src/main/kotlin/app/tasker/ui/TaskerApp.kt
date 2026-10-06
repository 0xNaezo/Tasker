package app.tasker.ui

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.AppStart
import app.tasker.AppViewModel
import app.tasker.R
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.format.Formats
import app.tasker.core.ui.message.UserMessage
import app.tasker.core.ui.text.resolve
import app.tasker.feature.capture.CaptureBar
import app.tasker.feature.settings.OnboardingScreen
import kotlinx.coroutines.flow.collectLatest

/** The app's root: lock, restore offer, onboarding or the main frame (tech plan §14.1, §16, NFR "Безопасность"). */
@Composable
fun TaskerApp(viewModel: AppViewModel, onUnlock: () -> Unit, versionName: String) {
    val start by viewModel.start.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // The readiness mark of the startup benchmark (tech plan §20): the first frame with the input line, or with the
    // lock, the restore question or onboarding in its place. The lists fill in after it.
    ReportDrawnWhen { start != AppStart.Loading }
    Messages(viewModel, snackbar)
    when (val current = start) {
        // The splash screen stays on top meanwhile.
        AppStart.Loading -> Surface(Modifier.fillMaxSize()) {}
        AppStart.Locked -> LockScreen(onUnlock)
        is AppStart.Restore -> {
            Surface(Modifier.fillMaxSize()) {}
            RestoreDialog(current, onRestore = viewModel::acceptRestore, onDecline = viewModel::declineRestore)
        }
        AppStart.Onboarding -> WithSnackbar(snackbar) {
            OnboardingScreen(
                firstTask = { onSaved -> CaptureBar(announce = false, onSaved = { _, text -> onSaved(text) }) },
            )
        }
        AppStart.Main -> MainScreen(viewModel, snackbar, versionName)
    }
}

/** One snackbar at a time; a newer message replaces the older one, whose undo stays in the task's history. */
@Composable
private fun Messages(viewModel: AppViewModel, host: SnackbarHostState) {
    val context = LocalContext.current
    val undoLabel = stringResource(UiR.string.action_undo)
    LaunchedEffect(viewModel, host) {
        viewModel.messages.collectLatest { message ->
            val text = message.text.resolve(context)
            when (message) {
                is UserMessage.Undoable -> {
                    val result = host.showSnackbar(text, actionLabel = undoLabel, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) viewModel.undo(message.batchId)
                }
                is UserMessage.Info -> host.showSnackbar(text)
            }
        }
    }
}

@Composable
private fun WithSnackbar(host: SnackbarHostState, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
    }
}

/** Shown until the user unlocks; nothing of the task list is composed meanwhile. */
@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(TaskerTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.l, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(LOCK_ICON_SIZE.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Button(onClick = onUnlock) { Text(stringResource(R.string.lock_unlock)) }
        }
    }
}

@Composable
private fun RestoreDialog(offer: AppStart.Restore, onRestore: () -> Unit, onDecline: () -> Unit) {
    val candidate = offer.candidate
    AlertDialog(
        // The question needs an answer: tapping outside does not dismiss it.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.app_restore_title)) },
        text = {
            Text(
                if (offer.restoring) {
                    stringResource(R.string.app_restoring)
                } else {
                    pluralStringResource(
                        R.plurals.app_restore_body,
                        candidate.taskCount,
                        Formats.weekdayDate(candidate.day),
                        candidate.taskCount,
                    )
                },
            )
        },
        confirmButton = { TextButton(onClick = onRestore, enabled = !offer.restoring) { Text(stringResource(R.string.app_restore)) } },
        dismissButton = {
            TextButton(onClick = onDecline, enabled = !offer.restoring) { Text(stringResource(R.string.app_restore_decline)) }
        },
    )
}

private const val LOCK_ICON_SIZE = 56
