package app.tasker.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * Back stacks of the tabs (§14.1): each tab keeps its own history, Back on a tab root returns to [Tab.TODAY], and
 * tapping the current tab again returns to its root.
 */
@Stable
class AppNavigator internal constructor(
    private val stacks: Map<Tab, NavBackStack<NavKey>>,
    tabState: MutableState<Tab>,
) {
    var tab: Tab by tabState
        private set

    fun stack(tab: Tab): NavBackStack<NavKey> = stacks.getValue(tab)

    /** Tabs whose screens are on screen or under it: the start tab stays below, so Back leads there. */
    val visibleTabs: List<Tab>
        get() = if (tab == Tab.START) listOf(Tab.START) else listOf(Tab.START, tab)

    /** The current screen is the root of its tab: the capture line and the tab bar are shown. */
    val atTabRoot: Boolean
        get() = stack(tab).size <= 1

    /** In two panes the current screen is a list or a card beside it, so the tab's frame stays (§14.1). */
    fun inListDetail(twoPane: Boolean): Boolean = twoPane && tab.listDetail && ListDetail.isPane(stack(tab).last())

    fun select(target: Tab) {
        if (target == tab) popToRoot(target) else tab = target
    }

    fun navigate(key: NavKey) {
        val stack = stack(tab)
        if (stack.lastOrNull() != key) stack.add(key)
    }

    /** Opens a task card; beside a list, a new card takes the place of the shown one instead of stacking up. */
    fun openDetail(key: NavKey, besideList: Boolean) {
        val stack = stack(tab)
        if (besideList && stack.size > 1 && ListDetail.isDetail(stack.last())) stack[stack.lastIndex] = key else navigate(key)
    }

    fun back() {
        val stack = stack(tab)
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            tab != Tab.START -> tab = Tab.START
        }
    }

    /** Opens [keys] on top of the root of [target], as a deep link does: Back then leads to that tab's root. */
    fun open(target: Tab, keys: List<NavKey>) {
        popToRoot(target)
        stack(target).addAll(keys)
        tab = target
    }

    private fun popToRoot(target: Tab) {
        val stack = stack(target)
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

@Composable
fun rememberAppNavigator(): AppNavigator {
    // One saved back stack per tab, always created in the same order.
    val stacks = Tab.entries.map { rememberNavBackStack(it.root) }
    val tab = rememberSaveable { mutableStateOf(Tab.START) }
    return remember(tab, *stacks.toTypedArray()) { AppNavigator(Tab.entries.zip(stacks).toMap(), tab) }
}
