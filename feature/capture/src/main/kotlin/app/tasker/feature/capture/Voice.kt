package app.tasker.feature.capture

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/** Starts on-device speech recognition; `null` when the device has no recognizer. */
@Stable
fun interface VoiceInput {
    fun start()
}

/**
 * Voice input through the system recognizer with offline preferred (§13). The recognized text goes into the input line;
 * the task is saved by Enter as usual.
 */
@Composable
fun rememberVoiceInput(onResult: (String) -> Unit): VoiceInput? {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onResult)
    val intent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    val available = remember { intent.resolveActivity(context.packageManager) != null }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull { it.isNotBlank() }
                ?.let { callback.value(it) }
        }
    }
    return if (available) remember(launcher) { VoiceInput { launcher.launch(intent) } } else null
}
