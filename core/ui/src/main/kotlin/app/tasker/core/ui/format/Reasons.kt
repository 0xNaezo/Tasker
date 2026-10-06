package app.tasker.core.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.tasker.core.domain.plan.CandidateReason
import app.tasker.core.domain.review.ReviewReason
import app.tasker.core.model.Bucket
import app.tasker.core.model.EventType
import app.tasker.core.model.FieldChange
import app.tasker.core.model.Reason
import app.tasker.core.model.ReasonCode
import app.tasker.core.model.TaskField
import app.tasker.core.model.TaskStatus
import app.tasker.core.ui.LocalDayContext
import app.tasker.core.ui.R
import java.time.LocalDate

/** Human-readable reasons: every automatic decision and every candidate explains itself (principle 7). */
object Reasons {
    @Composable
    @ReadOnlyComposable
    fun candidate(reason: CandidateReason): String = when (reason) {
        is CandidateReason.Overdue -> stringResource(R.string.reason_overdue_since, Formats.day(reason.deadline.date))
        is CandidateReason.DeadlineSoon -> stringResource(R.string.reason_deadline_on, Formats.deadline(reason.deadline))
        CandidateReason.InProgress -> stringResource(R.string.reason_in_progress)
        CandidateReason.Paused -> stringResource(R.string.reason_paused)
        is CandidateReason.CarriedOver -> stringResource(R.string.reason_carried_over, Formats.day(reason.since))
        is CandidateReason.PlanDatePassed -> stringResource(R.string.reason_carried_over, Formats.day(reason.planDate))
        CandidateReason.ForToday -> stringResource(R.string.reason_for_today)
        CandidateReason.FromWeek -> stringResource(R.string.reason_from_week)
    }

    @Composable
    @ReadOnlyComposable
    fun review(reason: ReviewReason): String = when (reason) {
        is ReviewReason.NotTouched -> pluralStringResource(
            R.plurals.auto_ttl_expired,
            reason.days.toInt(),
            reason.days.toInt(),
            Formats.bucket(if (reason.inbox) null else reason.bucket),
        )
        is ReviewReason.ProjectInactive ->
            pluralStringResource(R.plurals.auto_project_inactive, reason.days.toInt(), reason.days.toInt())
        ReviewReason.NoNextStep -> stringResource(R.string.review_no_next_step)
    }

    /** Text of an automation reason with its parameters (journal and task history). */
    @Composable
    @ReadOnlyComposable
    fun automation(reason: Reason): String {
        val params = reason.params
        fun int(key: String) = params[key]?.toIntOrNull() ?: 0
        return when (reason.code) {
            ReasonCode.TTL_EXPIRED -> pluralStringResource(
                R.plurals.auto_ttl_expired,
                int("days"),
                int("days"),
                Formats.bucket(params["bucket"]?.let { name -> Bucket.entries.firstOrNull { it.name == name } }),
            )
            ReasonCode.REVIEW_SKIPPED -> pluralStringResource(R.plurals.auto_review_skipped, int("streak"), int("streak"))
            ReasonCode.TTL_SKIPS -> pluralStringResource(R.plurals.auto_ttl_skips, int("skips"), int("skips"))
            ReasonCode.PLAN_DATE_PASSED -> stringResource(R.string.auto_plan_date_passed, dayParam(params["date"]))
            ReasonCode.PLAN_NOT_DONE -> stringResource(R.string.auto_plan_not_done, dayParam(params["date"]))
            ReasonCode.TODAY_NOT_DONE -> stringResource(R.string.auto_today_not_done, dayParam(params["date"]))
            ReasonCode.PROJECT_INACTIVE -> pluralStringResource(R.plurals.auto_project_inactive, int("days"), int("days"))
            ReasonCode.AI_ENRICHED -> {
                val fields = params["fields"].orEmpty().split(',').filter { it.isNotBlank() }
                    .mapNotNull { name -> TaskField.entries.firstOrNull { it.name == name } }
                if (fields.isEmpty()) {
                    stringResource(R.string.auto_ai_nothing)
                } else {
                    stringResource(R.string.auto_ai_enriched, fields.map { field(it) }.joinToString(", "))
                }
            }
            ReasonCode.INTEGRATION_CLOSED -> stringResource(R.string.auto_integration_closed)
            ReasonCode.INTEGRATION_MERGED -> stringResource(R.string.auto_integration_merged)
            ReasonCode.INTEGRATION_TITLE_SYNC -> stringResource(R.string.auto_integration_title)
        }
    }

    @Composable
    @ReadOnlyComposable
    private fun dayParam(value: String?): String =
        value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { Formats.day(it, LocalDayContext.current.today) }
            ?: value.orEmpty()

    @Composable
    @ReadOnlyComposable
    fun field(field: TaskField): String = stringResource(
        when (field) {
            TaskField.TITLE -> R.string.field_title
            TaskField.NOTE -> R.string.field_note
            TaskField.BUCKET -> R.string.field_bucket
            TaskField.DEADLINE -> R.string.field_deadline
            TaskField.PLAN_DATE -> R.string.field_plan_date
            TaskField.ESTIMATE -> R.string.field_estimate
            TaskField.PROJECT -> R.string.field_project
            TaskField.TAGS -> R.string.field_tags
        },
    )

    /** Names of user-visible fields among event changes (service columns are left out). */
    @Composable
    @ReadOnlyComposable
    fun changedFields(changes: List<FieldChange>): String {
        val names = changes.mapNotNull { change ->
            when (change.field) {
                "title" -> field(TaskField.TITLE)
                "note" -> field(TaskField.NOTE)
                "bucket" -> field(TaskField.BUCKET)
                "deadline" -> field(TaskField.DEADLINE)
                "plan_date" -> field(TaskField.PLAN_DATE)
                "estimate" -> field(TaskField.ESTIMATE)
                "project_id" -> field(TaskField.PROJECT)
                "tags" -> field(TaskField.TAGS)
                "status" -> stringResource(R.string.field_status)
                else -> null
            }
        }.distinct()
        return names.joinToString(", ")
    }

    /** One line of task history. */
    @Composable
    @ReadOnlyComposable
    fun history(type: EventType, changes: List<FieldChange>): String = when (type) {
        EventType.CREATED -> stringResource(R.string.history_created)
        EventType.UPDATED -> changedFields(changes).let {
            if (it.isEmpty()) stringResource(R.string.history_updated, "…") else stringResource(R.string.history_updated, it)
        }
        EventType.STATUS_CHANGED -> {
            val status = changes.firstOrNull { it.field == "status" }?.after?.toString()?.trim('"')
                ?.let { name -> TaskStatus.entries.firstOrNull { it.name == name } }
            if (status ==
                null
            ) {
                stringResource(R.string.history_updated, "…")
            } else {
                stringResource(R.string.history_status, Formats.status(status))
            }
        }
        EventType.POSTPONED -> stringResource(R.string.history_postponed)
        EventType.ROLLED_OVER -> stringResource(R.string.history_rolled_over)
        EventType.MOVED -> stringResource(R.string.history_moved, changedFields(changes))
        EventType.REORDERED -> stringResource(R.string.history_reordered)
        EventType.SNAPSHOT_ADDED -> stringResource(R.string.history_snapshot)
        EventType.REVIEW_ENTERED -> stringResource(R.string.history_review_entered)
        EventType.REVIEW_DECIDED -> stringResource(R.string.history_review_decided)
        EventType.REVIEW_SKIPPED -> stringResource(R.string.history_review_skipped)
        EventType.AI_FILLED -> stringResource(R.string.history_ai_filled)
        EventType.SPLIT -> stringResource(R.string.history_split)
        EventType.MERGED -> stringResource(R.string.history_merged)
        EventType.ARCHIVED -> stringResource(R.string.history_archived)
        EventType.RESTORED -> stringResource(R.string.history_restored)
        EventType.PLAN_CHANGED -> stringResource(R.string.history_plan)
        EventType.UNDONE -> stringResource(R.string.history_undone)
    }
}
