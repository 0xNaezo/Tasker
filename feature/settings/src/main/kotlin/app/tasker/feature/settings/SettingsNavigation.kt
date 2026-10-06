package app.tasker.feature.settings

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Settings (SET-1…SET-3, DATA-1). */
@Serializable
data object SettingsKey : NavKey

/** AI help: consent, the switch, the mode and the API key (SET-3, tech plan §17). */
@Serializable
data object AiSettingsKey : NavKey
