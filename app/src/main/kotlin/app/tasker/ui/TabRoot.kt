package app.tasker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import app.tasker.R
import app.tasker.feature.journal.JournalKey
import app.tasker.feature.review.ReviewKey
import app.tasker.feature.search.ArchiveKey
import app.tasker.feature.search.SearchKey
import app.tasker.feature.settings.SettingsKey
import app.tasker.navigation.AppNavigator
import app.tasker.navigation.Tab

/** A tab's root: the tab name, search (§14.1) and the secondary screens in the overflow menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabRoot(tab: Tab, navigator: AppNavigator, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(tab.label)) },
                actions = {
                    IconButton(onClick = { navigator.navigate(SearchKey) }) {
                        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.app_search))
                    }
                    OverflowMenu(navigator::navigate)
                },
            )
        },
    ) { padding -> content(padding) }
}

@Composable
private fun OverflowMenu(open: (NavKey) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.app_menu))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                MenuEntry(R.string.app_menu_review, Icons.AutoMirrored.Outlined.FactCheck, ReviewKey),
                MenuEntry(R.string.app_menu_journal, Icons.Outlined.History, JournalKey),
                MenuEntry(R.string.app_menu_archive, Icons.Outlined.Archive, ArchiveKey),
                MenuEntry(R.string.app_menu_settings, Icons.Outlined.Settings, SettingsKey),
            ).forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(entry.label)) },
                    leadingIcon = { Icon(entry.icon, contentDescription = null) },
                    onClick = {
                        expanded = false
                        open(entry.key)
                    },
                )
            }
        }
    }
}

private class MenuEntry(val label: Int, val icon: ImageVector, val key: NavKey)
