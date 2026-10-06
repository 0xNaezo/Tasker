package app.tasker.core.data.command

import app.tasker.core.domain.history.Codecs
import app.tasker.core.model.CandidateGroup
import app.tasker.core.model.DayPlan
import app.tasker.core.model.DayPlanItem
import app.tasker.core.model.PlanItemOrigin
import app.tasker.core.model.PlanItemOutcome
import app.tasker.core.model.PlanState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Field keys of plan changes inside PLAN events: `state` and `item:<taskId>`. */
internal object PlanFields {
    const val STATE = "state"
    private const val ITEM_PREFIX = "item:"

    fun itemKey(taskId: String) = ITEM_PREFIX + taskId

    fun taskIdOf(key: String): String? = key.takeIf { it.startsWith(ITEM_PREFIX) }?.removePrefix(ITEM_PREFIX)

    fun encode(item: DayPlanItem?): JsonElement = item?.let {
        JsonObject(
            buildMap {
                put("position", JsonPrimitive(it.position))
                put("minutes", JsonPrimitive(it.minutes))
                put("origin", JsonPrimitive(it.origin.name))
                it.candidateGroup?.let { group -> put("group", JsonPrimitive(group.name)) }
                put("outcome", JsonPrimitive(it.outcome.name))
            },
        )
    } ?: JsonNull

    fun decode(plan: DayPlan, taskId: String, json: JsonElement): DayPlanItem? {
        if (json is JsonNull) return null
        val obj = json.jsonObject
        return DayPlanItem(
            date = plan.date,
            taskId = taskId,
            position = obj["position"]?.jsonPrimitive?.intOrNull ?: 0,
            minutes = obj["minutes"]?.jsonPrimitive?.intOrNull ?: 0,
            origin = PlanItemOrigin.valueOf(obj.getValue("origin").jsonPrimitive.content),
            candidateGroup = obj["group"]?.jsonPrimitive?.content?.let(CandidateGroup::valueOf),
            outcome = PlanItemOutcome.valueOf(obj.getValue("outcome").jsonPrimitive.content),
        )
    }

    val stateCodec = Codecs.enumCodec<PlanState>()
}

/** Plan with [item] inserted or replaced (matched by task). */
internal fun DayPlan.withItem(item: DayPlanItem): DayPlan =
    copy(items = (items.filter { it.taskId != item.taskId } + item).sortedBy { it.position })

internal fun DayPlan.withoutItem(taskId: String): DayPlan = copy(items = items.filter { it.taskId != taskId })

internal fun DayPlan.item(taskId: String): DayPlanItem? = items.firstOrNull { it.taskId == taskId }

internal fun DayPlan.nextPosition(): Int = (items.maxOfOrNull { it.position } ?: 0) + 1

/** A draft the user edited by hand is not recomputed automatically any more (tech plan §10.5). */
internal val DayPlan.isFrozenDraft: Boolean
    get() = state == PlanState.DRAFT && items.any { it.origin != PlanItemOrigin.AUTO }
