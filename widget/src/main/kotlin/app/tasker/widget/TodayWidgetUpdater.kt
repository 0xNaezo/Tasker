package app.tasker.widget

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import app.tasker.core.data.di.ApplicationScope
import app.tasker.core.data.effects.CommitInfo
import app.tasker.core.data.effects.CommitListener
import app.tasker.core.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Redraws the widget after every committed command (post-commit effect, tech plan §4.3), when the app lock is
 * switched (a setting rather than a command) and when the app language changes.
 */
@Singleton
class TodayWidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) : CommitListener {
    private var locales: LocaleList? = null

    override suspend fun onCommitted(info: CommitInfo) = update()

    /** Follows the app lock for the life of the process; called once on start. */
    fun start() {
        locales = context.resources.configuration.locales
        scope.launch {
            settings.settings.map { it.biometricLock }.distinctUntilChanged().drop(1).collect { update() }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) scope.launch { publishPreviews() }
    }

    /** Called by the application: a new per-app language (Android 13+) reaches the widget's texts at once. */
    fun onConfigurationChanged(config: Configuration) {
        if (config.locales == locales) return
        locales = config.locales
        scope.launch { update() }
    }

    suspend fun update() {
        runCatching {
            if (GlanceAppWidgetManager(context).getGlanceIds(TodayWidget::class.java).isNotEmpty()) TodayWidget().updateAll(context)
        }.onFailure { Log.w(TAG, "Widget update failed", it) }
    }

    /** Generated picker previews (Android 15+): once per app version and language, as the system rate-limits them. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun publishPreviews() {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            val key = "${info.longVersionCode}/${context.resources.configuration.locales.toLanguageTags()}"
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_PREVIEWS, null) == key) return
            val result = GlanceAppWidgetManager(context).setWidgetPreviews(TodayWidgetReceiver::class)
            if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) prefs.edit { putString(KEY_PREVIEWS, key) }
        }.onFailure { Log.w(TAG, "Widget previews were not published", it) }
    }

    private companion object {
        const val TAG = "TodayWidgetUpdater"
        const val PREFS = "widget"
        const val KEY_PREVIEWS = "previews"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WidgetModule {
    @Binds
    @IntoSet
    abstract fun widgetListener(updater: TodayWidgetUpdater): CommitListener
}
