package app.tasker.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.tasker.AppViewModel
import app.tasker.core.ui.component.PostponeSheet
import app.tasker.feature.capture.CaptureBar
import app.tasker.navigation.Tab
import app.tasker.navigation.follow
import app.tasker.navigation.rememberAppNavigator

/**
 * The main frame (§14.1): four tabs as a bottom bar on phones and a rail on wide screens, the capture line pinned
 * above the bar on every tab root (§13), one snackbar host for every command's undo (§14.3).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MainScreen(viewModel: AppViewModel, snackbar: SnackbarHostState, versionName: String, modifier: Modifier = Modifier) {
    val navigator = rememberAppNavigator()
    val link by viewModel.link.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        link?.let {
            navigator.follow(it)
            viewModel.linkHandled()
        }
    }
    // Every tab keeps its entries (and their state) while another tab is shown.
    val entries = Tab.entries.associateWith { tab ->
        val provider = remember(tab, navigator, versionName) { tabEntries(tab, navigator, versionName) }
        rememberDecoratedNavEntries(
            navigator.stack(tab),
            listOf(rememberSaveableStateHolderNavEntryDecorator<NavKey>(), rememberViewModelStoreNavEntryDecorator<NavKey>()),
            provider,
        )
    }
    val adaptiveType = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())
    // While typing, a bottom bar would sit under the keyboard and push the capture line too high: it steps aside.
    val typingOverBottomBar = WindowInsets.isImeVisible && adaptiveType.isBottomBar()
    val atRoot = navigator.atTabRoot
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Tab.entries.forEach { tab ->
                val selected = tab == navigator.tab
                item(
                    selected = selected,
                    onClick = { navigator.select(tab) },
                    icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                )
            }
        },
        modifier = modifier,
        layoutType = if (atRoot && !typingOverBottomBar) adaptiveType else NavigationSuiteType.None,
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (atRoot) {
                    CaptureBar(
                        modifier = Modifier.windowInsetsPadding(
                            WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom),
                        ),
                    )
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            NavDisplay(
                entries = navigator.visibleTabs.flatMap { entries.getValue(it) },
                onBack = navigator::back,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            )
        }
    }
    val postponing by viewModel.postponeTask.collectAsStateWithLifecycle()
    postponing?.let { task ->
        PostponeSheet(onDismiss = viewModel::cancelPostpone, onSelect = viewModel::postpone, hasDeadline = task.deadline != null)
    }
}

private fun NavigationSuiteType.isBottomBar(): Boolean = this == NavigationSuiteType.NavigationBar ||
    this == NavigationSuiteType.ShortNavigationBarCompact ||
    this == NavigationSuiteType.ShortNavigationBarMedium
