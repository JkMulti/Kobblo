package com.kobblo.gallery.file.explorer.music

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drag to reorder in a lazy list. The row follows the finger from its handle, its neighbours make way
 * as it crosses their middles, and the list scrolls by itself when the finger nears an edge.
 *
 * [canMoveOver] says which list items are songs rather than headers; [onMove] gets list indices.
 */
@Stable
class ReorderState(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    private val canMoveOver: (LazyListItemInfo) -> Boolean,
    private val onMove: (from: Int, to: Int) -> Unit
) {
    var draggedKey by mutableStateOf<Any?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    private var autoScroll: Job? = null

    // Where the dragged row should end up after a swap. Until the list has laid it out there, what
    // the list reports is out of date, and acting on it would swap the row straight back.
    private var settlingAt = -1

    fun start(key: Any) {
        draggedKey = key
        offset = 0f
        settlingAt = -1
        autoScroll?.cancel()
        autoScroll = scope.launch {
            while (isActive) {
                withFrameNanos { }
                val speed = edgeSpeed()
                if (speed != 0f) {
                    val scrolled = list.dispatchRawDelta(speed)
                    offset += scrolled
                    swapIfCrossed()
                }
            }
        }
    }

    fun drag(deltaY: Float) {
        offset += deltaY
        swapIfCrossed()
    }

    fun end() {
        autoScroll?.cancel()
        autoScroll = null
        draggedKey = null
        offset = 0f
    }

    private fun draggedInfo(): LazyListItemInfo? =
        list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggedKey }

    private fun edgeSpeed(): Float {
        val dragged = draggedInfo() ?: return 0f
        val info = list.layoutInfo
        val top = dragged.offset + offset
        val bottom = top + dragged.size
        val start = info.viewportStartOffset.toFloat()
        val end = info.viewportEndOffset.toFloat()
        val zone = dragged.size * 0.8f
        return when {
            top < start + zone -> -MaxEdgeSpeed * ((start + zone - top) / zone).coerceAtMost(1f)
            bottom > end - zone -> MaxEdgeSpeed * ((bottom - end + zone) / zone).coerceAtMost(1f)
            else -> 0f
        }
    }

    private fun swapIfCrossed() {
        val dragged = draggedInfo() ?: return
        if (settlingAt >= 0 && dragged.index != settlingAt) return
        settlingAt = -1
        val middle = dragged.offset + offset + dragged.size / 2f
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != draggedKey && canMoveOver(item) && middle >= item.offset && middle <= item.offset + item.size
        } ?: return
        // A lazy list follows its first visible item by key, so moving that one would drag the whole
        // list along with it. Holding the position keeps the rows where the finger expects them.
        val firstVisible = list.firstVisibleItemIndex
        val firstOffset = list.firstVisibleItemScrollOffset
        val movesFirstVisible = dragged.index == firstVisible || target.index == firstVisible
        onMove(dragged.index, target.index)
        settlingAt = target.index
        offset -= (target.offset - dragged.offset)
        if (movesFirstVisible) {
            scope.launch { list.scrollToItem(firstVisible, firstOffset) }
        }
    }

    private companion object {
        const val MaxEdgeSpeed = 14f
    }
}

@Composable
fun rememberReorderState(
    list: LazyListState,
    canMoveOver: (LazyListItemInfo) -> Boolean,
    onMove: (from: Int, to: Int) -> Unit
): ReorderState {
    val scope = rememberCoroutineScope()
    val latestCanMove by rememberUpdatedState(canMoveOver)
    val latestOnMove by rememberUpdatedState(onMove)
    return remember(list) {
        ReorderState(list, scope, { latestCanMove(it) }, { from, to -> latestOnMove(from, to) })
    }
}

/** On a row: lifts and moves it while it's the one being dragged. */
fun Modifier.reorderRow(state: ReorderState, key: Any): Modifier =
    this
        .zIndex(if (state.draggedKey == key) 1f else 0f)
        .graphicsLayer {
            if (state.draggedKey == key) {
                translationY = state.offset
                shadowElevation = 12f
            }
        }

/** On a row's handle: the only place a drag starts, so the rest of the row still scrolls the list. */
fun Modifier.reorderHandle(state: ReorderState, key: Any, onStart: () -> Unit = {}): Modifier =
    pointerInput(key) {
        detectDragGestures(
            onDragStart = {
                onStart()
                state.start(key)
            },
            onDragEnd = { state.end() },
            onDragCancel = { state.end() },
            onDrag = { change, amount ->
                change.consume()
                state.drag(amount.y)
            }
        )
    }
