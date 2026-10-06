package app.tasker.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.core.ai.AiState
import app.tasker.core.ai.ApiKeyStore
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.AiMode
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.component.ConfirmDialog
import app.tasker.core.ui.component.SectionHeader

/**
 * AI help (SET-3, tech plan §17): the page itself is the consent — it lists what is sent before anything is (§17.3),
 * then holds the one switch, the mode, the user's API key for the direct mode and a connection check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: AiSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    var editingKey by rememberSaveable { mutableStateOf(false) }
    var removingKey by rememberSaveable { mutableStateOf(false) }
    var choosingMode by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_ai)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        val ai = state
        if (ai == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item(key = "intro") { Paragraph(stringResource(R.string.settings_ai_intro)) }
            when {
                ai.availableModes.isEmpty() -> item(key = "unavailable") { Paragraph(stringResource(R.string.settings_ai_unavailable)) }
                // What is sent comes first: the button below it is the consent.
                !ai.hasConsent -> {
                    sent(ai.availableModes.singleOrNull())
                    consent(ai, viewModel::giveConsent)
                }
                else -> controls(
                    ai = ai,
                    checking = checking,
                    actions = AiActions(
                        setEnabled = viewModel::setEnabled,
                        chooseMode = { choosingMode = true },
                        editKey = { editingKey = true },
                        removeKey = { removingKey = true },
                        check = viewModel::checkConnection,
                    ),
                )
            }
            if (ai.hasConsent) sent(ai.mode)
        }
    }
    if (editingKey) KeyDialog(onDismiss = { editingKey = false }, onSave = viewModel::saveKey)
    if (removingKey) {
        ConfirmDialog(
            title = stringResource(R.string.settings_ai_key_remove_title),
            text = stringResource(R.string.settings_ai_key_remove_body),
            confirmLabel = stringResource(R.string.settings_ai_key_remove),
            onConfirm = {
                removingKey = false
                viewModel.clearKey()
            },
            onDismiss = { removingKey = false },
        )
    }
    val current = state
    if (choosingMode && current != null) {
        SingleChoiceDialog<AiMode?>(
            title = stringResource(R.string.settings_ai_mode),
            options = modesOf(current).map { modeName(it) to it },
            selected = current.mode,
            onDismiss = { choosingMode = false },
            onPick = { mode ->
                choosingMode = false
                // Another mode sends data along another path: the consent is recorded again for it.
                if (mode != null && mode != current.mode) viewModel.giveConsent(mode)
            },
        )
    }
}

private class AiActions(
    val setEnabled: (Boolean) -> Unit,
    val chooseMode: () -> Unit,
    val editKey: () -> Unit,
    val removeKey: () -> Unit,
    val check: () -> Unit,
)

private fun LazyListScope.consent(ai: AiState, onAccept: (AiMode) -> Unit) {
    item(key = "consent") {
        val modes = modesOf(ai)
        var chosen by rememberSaveable { mutableStateOf(modes.first()) }
        Column(verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s)) {
            if (modes.size > 1) {
                SectionHeader(stringResource(R.string.settings_ai_mode))
                modes.forEach { mode ->
                    ListItem(
                        headlineContent = { Text(modeName(mode)) },
                        supportingContent = { Text(modeNote(mode)) },
                        leadingContent = { RadioButton(selected = mode == chosen, onClick = null) },
                        modifier = Modifier.selectable(selected = mode == chosen, role = Role.RadioButton) { chosen = mode },
                    )
                }
            }
            Button(
                onClick = { onAccept(chosen) },
                modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l),
            ) { Text(stringResource(R.string.settings_ai_accept)) }
        }
    }
}

private fun LazyListScope.controls(ai: AiState, checking: Boolean, actions: AiActions) {
    item(key = "switch") {
        SwitchRow(
            title = stringResource(R.string.settings_ai_switch),
            subtitle = statusText(ai),
            checked = ai.enabled,
            onChange = actions.setEnabled,
        )
    }
    if (modesOf(ai).size > 1) {
        item(key = "mode") {
            ValueRow(
                stringResource(R.string.settings_ai_mode),
                ai.mode?.let { modeName(it) } ?: stringResource(R.string.settings_ai_mode_none),
                actions.chooseMode,
            )
        }
    }
    if (ai.mode == AiMode.DIRECT) {
        item(key = "key") {
            ValueRow(
                title = stringResource(R.string.settings_ai_key),
                value = stringResource(if (ai.hasApiKey) R.string.settings_ai_key_set else R.string.settings_ai_key_none),
                onClick = actions.editKey,
                icon = Icons.Outlined.Key,
            )
        }
        if (ai.hasApiKey) {
            item(key = "key-remove") {
                ValueRow(
                    title = stringResource(R.string.settings_ai_key_remove),
                    value = stringResource(R.string.settings_ai_key_remove_note),
                    onClick = actions.removeKey,
                    icon = Icons.Outlined.Delete,
                )
            }
        }
    }
    item(key = "check") {
        ValueRow(
            title = stringResource(R.string.settings_ai_check),
            value = stringResource(if (checking) R.string.settings_ai_checking else R.string.settings_ai_check_note),
            onClick = actions.check,
            icon = Icons.Outlined.NetworkCheck,
            enabled = !checking && ai.mode != null,
        )
    }
}

private fun LazyListScope.sent(mode: AiMode?) {
    item(key = "sent-header") { SectionHeader(stringResource(R.string.settings_ai_sent_title)) }
    item(key = "sent") {
        Paragraph(stringResource(R.string.settings_ai_sent))
        mode?.let { Paragraph(modeNote(it)) }
    }
}

@Composable
private fun statusText(ai: AiState): String = when {
    !ai.enabled -> stringResource(R.string.settings_ai_off)
    ai.mode == null -> stringResource(R.string.settings_ai_mode_none)
    ai.mode == AiMode.DIRECT && !ai.hasApiKey -> stringResource(R.string.settings_ai_needs_key)
    ai.pendingCount > 0 -> pluralStringResource(R.plurals.settings_ai_pending, ai.pendingCount, ai.pendingCount)
    else -> stringResource(R.string.settings_ai_on)
}

/** Modes this build offers, the direct one first. */
private fun modesOf(ai: AiState): List<AiMode> = AiMode.entries.filter { it in ai.availableModes }

@Composable
private fun modeName(mode: AiMode): String = stringResource(
    when (mode) {
        AiMode.DIRECT -> R.string.settings_ai_mode_direct
        AiMode.PROXY -> R.string.settings_ai_mode_proxy
    },
)

@Composable
private fun modeNote(mode: AiMode): String = stringResource(
    when (mode) {
        AiMode.DIRECT -> R.string.settings_ai_mode_direct_note
        AiMode.PROXY -> R.string.settings_ai_mode_proxy_note
    },
)

@Composable
private fun Paragraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.s),
    )
}

/** The key is kept only in memory while typing: it never goes into saved state. */
@Composable
private fun KeyDialog(onDismiss: () -> Unit, onSave: (String, (KeyError?) -> Unit) -> Unit) {
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<KeyError?>(null) }
    var saving by remember { mutableStateOf(false) }
    val warning = key.isNotBlank() && !ApiKeyStore.looksLikeOpenRouterKey(key)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_ai_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s)) {
                Text(stringResource(R.string.settings_ai_key_note), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        key = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("sk-or-…") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = error != null,
                    supportingText = when {
                        error == KeyError.INVALID -> {
                            { Text(stringResource(R.string.settings_ai_key_invalid)) }
                        }
                        error == KeyError.NOT_STORED -> {
                            { Text(stringResource(R.string.settings_ai_key_failed)) }
                        }
                        warning -> {
                            { Text(stringResource(R.string.settings_ai_key_warning)) }
                        }
                        else -> null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = key.isNotBlank() && !saving,
                onClick = {
                    saving = true
                    onSave(key) { result ->
                        saving = false
                        if (result == null) onDismiss() else error = result
                    }
                },
            ) { Text(stringResource(R.string.settings_ai_key_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}
