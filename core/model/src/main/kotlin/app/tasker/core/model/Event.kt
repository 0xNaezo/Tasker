package app.tasker.core.model

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** One field change inside an event: the basis of task history and undo (tech plan §7.5). */
@Serializable
data class FieldChange(
    val field: String,
    val before: JsonElement? = null,
    val after: JsonElement? = null,
)

/** Why the system did something (principle 7). Mandatory for every non-user event. */
data class Reason(
    val code: ReasonCode,
    val params: Map<String, String> = emptyMap(),
)

@Serializable
enum class ReasonCode {
    /** R3: not touched longer than the bucket TTL. Params: days, bucket. */
    TTL_EXPIRED,

    /** R4: card was shown but not decided during the day. Params: streak. */
    REVIEW_SKIPPED,

    /** R4: skipped twice in a row in the review queue. Params: skips. */
    TTL_SKIPS,

    /** R2: soft plan date passed. Params: date. */
    PLAN_DATE_PASSED,

    /** R1: item of an accepted plan was not done. Params: date. */
    PLAN_NOT_DONE,

    /** R1: task stayed in the Today bucket on an active working day. Params: date. */
    TODAY_NOT_DONE,

    /** R5: active project without activity. Params: days. */
    PROJECT_INACTIVE,

    /** R10: AI filled empty fields. Params: fields, confidence. */
    AI_ENRICHED,

    /** Integrations (next version). */
    INTEGRATION_CLOSED,
    INTEGRATION_MERGED,
    INTEGRATION_TITLE_SYNC,
}

data class Event(
    val id: String,
    val batchId: BatchId,
    val entityType: EntityType,
    val entityId: String,
    val actor: Actor,
    val type: EventType,
    val changes: List<FieldChange>,
    val reason: Reason?,
    val createdAt: Instant,
    val undoUntil: Instant? = null,
    val undoneAt: Instant? = null,
    val undoneBy: BatchId? = null,
) {
    init {
        require(actor == Actor.USER || reason != null) {
            "Automatic events must carry a reason code (principle 7)"
        }
    }

    val isAutomatic: Boolean get() = actor != Actor.USER
    val isUndone: Boolean get() = undoneAt != null

    fun canUndo(now: Instant): Boolean = !isUndone && undoUntil != null && now.isBefore(undoUntil)
}
