package app.tasker.feature.inbox

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The "Inbox" tab (CAP-5). */
@Serializable
data object InboxKey : NavKey

/** Quick Inbox triage, one card at a time (TTL-9). */
@Serializable
data object InboxTriageKey : NavKey
