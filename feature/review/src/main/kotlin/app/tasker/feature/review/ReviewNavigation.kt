package app.tasker.feature.review

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Relevance review (TTL-4, TTL-6). Deep link `tasker://review`. */
@Serializable
data object ReviewKey : NavKey
