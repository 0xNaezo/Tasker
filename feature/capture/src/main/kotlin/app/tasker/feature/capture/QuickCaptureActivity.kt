package app.tasker.feature.capture

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.Task
import app.tasker.core.ui.ProvideDayContext
import app.tasker.core.ui.text.UiText
import app.tasker.core.ui.text.resolve
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Light translucent window with only the input line (§13): the keyboard opens at once and no lists are loaded, so it
 * also works while the app is locked by biometrics without revealing tasks.
 */
@AndroidEntryPoint
class QuickCaptureActivity : ComponentActivity() {
    @Inject
    lateinit var clock: DayClock

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val channel = CaptureIntents.channelOf(intent)
        val voice = intent.getBooleanExtra(CaptureIntents.EXTRA_VOICE, false)
        if (savedInstanceState == null && channel == CaptureChannel.SHORTCUT) CaptureShortcuts.reportUsed(this, voice)
        setContent {
            TaskerTheme {
                ProvideDayContext(clock) {
                    QuickCaptureScreen(
                        channel = channel,
                        startVoice = voice,
                        onDismiss = ::finish,
                        onSaved = { _, text ->
                            Toast.makeText(this, text.resolve(this), Toast.LENGTH_SHORT).show()
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickCaptureScreen(
    channel: CaptureChannel,
    startVoice: Boolean,
    onDismiss: () -> Unit,
    onSaved: (Task, UiText) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .imePadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(Modifier.navigationBarsPadding().padding(top = TaskerTheme.spacing.l)) {
                Text(
                    text = stringResource(R.string.capture_quick_label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l),
                )
                CaptureBar(
                    channel = channel,
                    announce = false,
                    autoFocus = !startVoice,
                    startVoice = startVoice,
                    onSaved = onSaved,
                )
            }
        }
    }
}
