package app.tasker.core.ui.component

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Drag-and-drop reordering for a LazyColumn (DAT-5). Items are identified by their lazy-list keys: while dragging,
 * [onMove] asks the screen to move the dragged key next to the hovered one (return `false` to refuse, e.g. across
 * sections); [onDrop] commits the final order. TalkBack users get "Move up" / "Move down" row actions instead.
 */
@Stable
class DragDropState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (from: Any, to: Any) -> Boolean,
    private val onDrop: () -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    private var delta by mutableFloatStateOf(0f)
    private var initialOffset by mutableIntStateOf(0)
    internal val scrollRequests = Channel<Float>(Channel.CONFLATED)

    private val draggingItem: LazyListItemInfo?
        get() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingKey }

    /** Vertical translation of the dragged item relative to its laid-out position. */
    val draggingOffset: Float
        get() = draggingItem?.let { initialOffset + delta - it.offset } ?: 0f

    internal fun start(key: Any) {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        draggingKey = key
        initialOffset = item.offset
        delta = 0f
    }

    internal fun drag(dy: Float) {
        delta += dy
        val item = draggingItem ?: return
        val start = item.offset + draggingOffset
        val middle = (start + item.size / 2f).toInt()
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != item.key && middle in it.offset..(it.offset + it.size)
        }
        if (target != null) {
            val first = listState.firstVisibleItemIndex
            val firstOffset = listState.firstVisibleItemScrollOffset
            if (onMove(item.key, target.key) && (item.index == first || target.index == first)) {
                // Keep the viewport still when the first visible item moves.
                scope.launch { listState.scrollToItem(first, firstOffset) }
            }
        } else {
            val end = start + item.size
            val overscroll = when {
                delta > 0 -> (end - listState.layoutInfo.viewportEndOffset).coerceAtLeast(0f)
                delta < 0 -> (start - listState.layoutInfo.viewportStartOffset).coerceAtMost(0f)
                else -> 0f
            }
            if (overscroll != 0f) scrollRequests.trySend(overscroll)
        }
    }

    internal fun end() {
        if (draggingKey == null) return
        draggingKey = null
        delta = 0f
        initialOffset = 0
        onDrop()
    }
}

@Composable
fun rememberDragDropState(
    listState: LazyListState,
    onMove: (from: Any, to: Any) -> Boolean,
    onDrop: () -> Unit,
): DragDropState {
    val scope = rememberCoroutineScope()
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val state = remember(listState) {
        DragDropState(listState, scope, onMove = { from, to -> move(from, to) }, onDrop = { drop() })
    }
    LaunchedEffect(state) {
        for (dy in state.scrollRequests) listState.scrollBy(dy)
    }
    return state
}

/** Wraps a lazy item: lifts and moves it while it is dragged, animates it into place otherwise. */
@Composable
fun LazyItemScope.DraggableItem(
    state: DragDropState,
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable (dragging: Boolean) -> Unit,
) {
    val dragging = state.draggingKey == key
    val itemModifier = if (dragging) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationY = state.draggingOffset
                shadowElevation = 8f
            }
    } else {
        Modifier.animateItem()
    }
    Box(modifier.then(itemModifier)) { content(dragging) }
}

/** Drag handle: long-press and drag to reorder. Hidden from TalkBack, which uses the move actions. */
@Composable
fun DragHandle(state: DragDropState, key: Any, modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Outlined.DragHandle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(12.dp)
            .pointerInput(state, key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { state.start(key) },
                    onDrag = { change, offset ->
                        change.consume()
                        state.drag(offset.y)
                    },
                    onDragEnd = { state.end() },
                    onDragCancel = { state.end() },
                )
            },
    )
}
