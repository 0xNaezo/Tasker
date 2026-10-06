package app.tasker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.tasker.platform.AppStartup
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** WorkManager uses Hilt's worker factory; its default initializer is removed in the manifests. */
@HiltAndroidApp
class TaskerApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var startup: AppStartup

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        startup.run()
    }
}
