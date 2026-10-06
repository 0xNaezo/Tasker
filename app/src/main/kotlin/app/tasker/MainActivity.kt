package app.tasker

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.domain.time.DayClock
import app.tasker.core.notifications.DeepLinks
import app.tasker.core.ui.ProvideDayContext
import app.tasker.feature.capture.CaptureShortcuts
import app.tasker.ui.TaskerApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The single activity: edge-to-edge and predictive back (Android 16), the app lock prompt and links from
 * notifications and other apps (`tasker://…`). AppCompat provides per-app languages on Android 8–12.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var clock: DayClock

    private val viewModel: AppViewModel by viewModels()

    private lateinit var unlockPrompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.start.value == AppStart.Loading }
        // Created in onCreate, so the result reaches the current activity after a configuration change.
        unlockPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = viewModel.unlock()
            },
        )
        if (savedInstanceState == null) follow(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) { viewModel.lockEnabled.collect(::hideInRecents) }
        }
        CaptureShortcuts.publish(this)
        setContent {
            TaskerTheme {
                ProvideDayContext(clock) {
                    TaskerApp(viewModel = viewModel, onUnlock = ::requestUnlock, versionName = BuildConfig.VERSION_NAME)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        follow(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStarted()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onStopped()
    }

    private fun follow(intent: Intent?) {
        DeepLinks.parse(intent?.data)?.let(viewModel::openLink)
    }

    private fun requestUnlock() {
        // Without a screen lock there is nothing to unlock with; locking the user out of their data would be worse.
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            viewModel.unlock()
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_prompt_title))
            .setAllowedAuthenticators(authenticators)
            .build()
        unlockPrompt.authenticate(info)
    }

    /** With the app lock on, the window content is hidden in the list of recent apps (tech plan §21). */
    private fun hideInRecents(locked: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!locked)
        } else if (locked) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
