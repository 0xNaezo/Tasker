package app.tasker.feature.capture

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tasker.core.data.command.CaptureOverrides
import app.tasker.core.data.command.FieldUpdate
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.Bucket
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectId
import app.tasker.core.parser.LinkKind
import app.tasker.core.parser.ParseResult
import app.tasker.core.parser.ParsedField
import app.tasker.core.parser.ParsedValue
import app.tasker.core.ui.LocalDayContext
import app.tasker.core.ui.component.DateDialog
import app.tasker.core.ui.format.Formats
import java.net.URI
import java.time.LocalDate

/** What the chips can do with the draft (CAP-4). */
internal interface ChipActions {
    fun returnToText(field: ParsedField)

    fun setDeadline(value: Deadline?)

    fun setPlanDate(value: LocalDate?)

    fun setEstimate(value: Estimate?)

    fun setBucket(value: Bucket?)

    fun setProject(value: ProjectId?)

    fun clearOverride(kind: OverrideKind)
}

private enum class DateTarget { DEADLINE, PLAN_DATE }

/**
 * Chips of recognized fields under the input line (§9.5): a tap opens a small menu to correct the value or return the
 * fragment to the text. Values picked by hand win over parsed ones. Links get their own chip and go to the card.
 */
@Composable
internal fun CaptureChips(
    parse: ParseResult?,
    draft: CaptureDraft,
    projects: List<Project>,
    actions: ChipActions,
    modifier: Modifier = Modifier,
) {
    var dateTarget by remember { mutableStateOf<DateTarget?>(null) }
    val overrides = draft.overrides
    val fields = parse?.fields.orEmpty()
    val links = parse?.links.orEmpty().filter { it.kind == LinkKind.URL }
    val hasKind = { kind: OverrideKind -> fields.any { it.value.overrideKind() == kind } }
    if (fields.isEmpty() && links.isEmpty() && !overrides.anySet()) return

    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = TaskerTheme.spacing.s),
        horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s),
    ) {
        fields.forEach { field ->
            ParsedChip(
                field = field,
                draft = draft,
                projects = projects,
                actions = actions,
                onPickDate = { dateTarget = it },
            )
        }
        // Values chosen by hand for fields that are not in the text.
        (overrides.deadline as? FieldUpdate.Set)?.value?.takeIf { !hasKind(OverrideKind.DEADLINE) }?.let { deadline ->
            OverrideChip(deadlineLabel(deadline), Icons.Outlined.Flag) { actions.clearOverride(OverrideKind.DEADLINE) }
        }
        (overrides.planDate as? FieldUpdate.Set)?.value?.takeIf { !hasKind(OverrideKind.PLAN_DATE) }?.let { date ->
            OverrideChip(Formats.day(date), Icons.Outlined.Event) { actions.clearOverride(OverrideKind.PLAN_DATE) }
        }
        (overrides.estimate as? FieldUpdate.Set)?.value?.takeIf { !hasKind(OverrideKind.ESTIMATE) }?.let { estimate ->
            OverrideChip(Formats.estimate(estimate), Icons.Outlined.Timer) { actions.clearOverride(OverrideKind.ESTIMATE) }
        }
        (overrides.bucket as? FieldUpdate.Set)?.takeIf { !hasKind(OverrideKind.BUCKET) }?.let { set ->
            OverrideChip(Formats.bucket(set.value), Icons.Outlined.Inbox) { actions.clearOverride(OverrideKind.BUCKET) }
        }
        (overrides.projectId as? FieldUpdate.Set)?.value?.takeIf { !hasKind(OverrideKind.PROJECT) }?.let { id ->
            val name = projects.firstOrNull { it.id == id }?.name ?: return@let
            OverrideChip("#$name", Icons.Outlined.Folder) { actions.clearOverride(OverrideKind.PROJECT) }
        }
        links.forEach { link ->
            AssistChip(
                onClick = {},
                label = { Text(shortUrl(link.url)) },
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }

    val today = LocalDayContext.current.today
    when (dateTarget) {
        DateTarget.DEADLINE -> {
            val current = (overrides.deadline as? FieldUpdate.Set)?.value
                ?: parse?.deadline?.let { Deadline(it.date, it.time, if (it.time != null) LocalDayContext.current.zone else null) }
            DateDialog(
                initial = current?.date ?: today,
                minDate = today,
                onDismiss = { dateTarget = null },
                onConfirm = { date ->
                    dateTarget = null
                    actions.setDeadline(Deadline(date, current?.time, current?.zone))
                },
            )
        }
        DateTarget.PLAN_DATE -> {
            val current = (overrides.planDate as? FieldUpdate.Set)?.value ?: parse?.planDate
            DateDialog(
                initial = current ?: today,
                minDate = today,
                onDismiss = { dateTarget = null },
                onConfirm = { date ->
                    dateTarget = null
                    actions.setPlanDate(date)
                },
            )
        }
        null -> Unit
    }
}

@Composable
private fun ParsedChip(
    field: ParsedField,
    draft: CaptureDraft,
    projects: List<Project>,
    actions: ChipActions,
    onPickDate: (DateTarget) -> Unit,
) {
    val overrides = draft.overrides
    var menu by remember { mutableStateOf(false) }
    val close = { menu = false }
    val (label, icon) = when (val value = field.value) {
        is ParsedValue.Deadline -> {
            val shown = (overrides.deadline as? FieldUpdate.Set)?.value
            (if (shown != null) deadlineLabel(shown) else deadlineLabel(value)) to Icons.Outlined.Flag
        }
        is ParsedValue.PlanDate -> {
            val shown = (overrides.planDate as? FieldUpdate.Set)?.value ?: value.date
            Formats.day(shown) to Icons.Outlined.Event
        }
        is ParsedValue.Size -> {
            val shown = (overrides.estimate as? FieldUpdate.Set)?.value ?: value.estimate
            Formats.estimate(shown) to Icons.Outlined.Timer
        }
        is ParsedValue.Horizon -> {
            val shown = (overrides.bucket as? FieldUpdate.Set)?.value ?: value.bucket
            Formats.bucket(shown) to Icons.Outlined.Inbox
        }
        is ParsedValue.Project -> {
            val picked = (overrides.projectId as? FieldUpdate.Set)?.value?.let { id -> projects.firstOrNull { it.id == id }?.name }
            val text = when {
                picked != null -> "#$picked"
                value.isNew -> stringResource(R.string.capture_chip_new_project, value.name)
                else -> "#${value.name}"
            }
            text to Icons.Outlined.Folder
        }
        is ParsedValue.Tag -> "@${value.name}" to Icons.Outlined.AlternateEmail
        ParsedValue.AlreadyDone -> stringResource(R.string.capture_chip_done) to Icons.Outlined.CheckCircle
    }
    Box {
        InputChip(
            selected = false,
            onClick = { menu = true },
            label = { Text(label, maxLines = 1) },
            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = menu, onDismissRequest = close) {
            when (field.value) {
                is ParsedValue.Deadline -> MenuItem(stringResource(R.string.capture_change_date)) {
                    close()
                    onPickDate(DateTarget.DEADLINE)
                }
                is ParsedValue.PlanDate -> MenuItem(stringResource(R.string.capture_change_date)) {
                    close()
                    onPickDate(DateTarget.PLAN_DATE)
                }
                is ParsedValue.Size -> Estimate.entries.forEach { estimate ->
                    MenuItem(Formats.estimate(estimate)) {
                        close()
                        actions.setEstimate(estimate)
                    }
                }
                is ParsedValue.Horizon -> (listOf<Bucket?>(null) + Bucket.entries).forEach { bucket ->
                    MenuItem(Formats.bucket(bucket)) {
                        close()
                        actions.setBucket(bucket)
                    }
                }
                is ParsedValue.Project -> projects.take(MAX_PROJECTS_IN_MENU).forEach { project ->
                    MenuItem("#${project.name}") {
                        close()
                        actions.setProject(project.id)
                    }
                }
                is ParsedValue.Tag, ParsedValue.AlreadyDone -> Unit
            }
            if (field.value !is ParsedValue.Tag && field.value != ParsedValue.AlreadyDone) HorizontalDivider()
            MenuItem(stringResource(R.string.capture_return_to_text)) {
                close()
                actions.returnToText(field)
            }
        }
    }
}

@Composable
private fun OverrideChip(label: String, icon: ImageVector, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        InputChip(
            selected = true,
            onClick = { menu = true },
            label = { Text(label, maxLines = 1) },
            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuItem(stringResource(R.string.capture_remove)) {
                menu = false
                onRemove()
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}

@Composable
private fun deadlineLabel(value: ParsedValue.Deadline): String =
    deadlineLabel(Deadline(value.date, value.time, if (value.time != null) LocalDayContext.current.zone else null))

@Composable
private fun deadlineLabel(deadline: Deadline): String = stringResource(R.string.capture_chip_deadline, Formats.deadline(deadline))

private fun CaptureOverrides.anySet(): Boolean =
    listOf(deadline, planDate, estimate, bucket, projectId).any { it is FieldUpdate.Set }

/** "github.com/…/42" for a long link. */
internal fun shortUrl(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url.take(MAX_LINK_LENGTH)
    val host = uri.host?.removePrefix("www.") ?: return url.take(MAX_LINK_LENGTH)
    val path = uri.path.orEmpty().trimEnd('/')
    val last = path.substringAfterLast('/', "")
    return when {
        path.isEmpty() -> host
        path.count { it == '/' } <= 1 -> host + path
        else -> "$host/…/$last"
    }.take(MAX_LINK_LENGTH)
}

private const val MAX_PROJECTS_IN_MENU = 12
private const val MAX_LINK_LENGTH = 40
