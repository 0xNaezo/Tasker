package app.tasker.feature.capture

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.TaskId
import app.tasker.core.ui.ProvideDayContext
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.component.TaskChips
import app.tasker.core.ui.text.asString
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay

/** Receives `ACTION_SEND` text: the task is saved immediately; the sheet closes by itself (§13). */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    @Inject
    lateinit var clock: DayClock

    private val viewModel: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val shared = SharedText.from(intent, referrer?.takeIf { it.scheme == ANDROID_APP_SCHEME }?.host)
            if (shared == null) {
                finish()
                return
            }
            viewModel.save(shared)
        }
        setContent {
            TaskerTheme {
                ProvideDayContext(clock) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    ShareSheet(
                        state = state,
                        onUndo = viewModel::undo,
                        onEdit = { id ->
                            openTask(id)
                            finish()
                        },
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    private fun openTask(id: TaskId) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tasker://task/$id"))
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private companion object {
        const val ANDROID_APP_SCHEME = "android-app"
    }
}

@Composable
private fun ShareSheet(state: ShareState, onUndo: () -> Unit, onEdit: (TaskId) -> Unit, onClose: () -> Unit) {
    var touched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state, touched) {
        when (state) {
            is ShareState.Saved -> if (!touched) {
                delay(AUTO_CLOSE_MS)
                onClose()
            }
            ShareState.Undone, ShareState.Failed -> {
                delay(CLOSE_AFTER_RESULT_MS)
                onClose()
            }
            ShareState.Saving -> Unit
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { touched = true },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(TaskerTheme.spacing.l),
                verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.m),
            ) {
                when (state) {
                    ShareState.Saving -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is ShareState.Saved -> SavedContent(state, onUndo, onEdit)
                    ShareState.Undone -> Text(stringResource(R.string.capture_share_undone), style = MaterialTheme.typography.titleMedium)
                    ShareState.Failed -> Text(stringResource(R.string.capture_failed), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun SavedContent(state: ShareState.Saved, onUndo: () -> Unit, onEdit: (TaskId) -> Unit) {
    val task = state.outcome.task
    Text(
        text = savedText(task, state.projectName).asString(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 3)
    TaskChips(task = task, projectName = state.projectName)
    Row(horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s)) {
        OutlinedButton(onClick = onUndo) { Text(stringResource(UiR.string.action_undo)) }
        Button(onClick = { onEdit(task.id) }) { Text(stringResource(R.string.capture_share_edit)) }
    }
}

private const val AUTO_CLOSE_MS = 4_000L
private const val CLOSE_AFTER_RESULT_MS = 1_200L
