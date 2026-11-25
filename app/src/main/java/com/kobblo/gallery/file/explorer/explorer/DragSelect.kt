package com.kobblo.gallery.file.explorer.explorer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.gallery.rangeSelection
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A row being held. Holding a file still opens its menu, as it does everywhere in the app, but the
 * menu waits for the finger to lift, because moving instead means selecting a run of files.
 */
class RowHold {
    internal var fingerDown = false
    internal var onRelease: (() -> Unit)? = null

    /**
     * Called from a row when a press becomes a hold. False means no finger is being tracked, as with an
     * accessibility service's long click, so the row should just open its menu.
     */
    fun arm(onRelease: () -> Unit): Boolean {
        if (!fingerDown) return false
        this.onRelease = onRelease
        return true
    }
}

/** The list a row is in, when that list can drag-select. */
val LocalRowHold = staticCompositionLocalOf<RowHold?> { null }

@Composable
fun rememberRowHold(): RowHold = remember { RowHold() }

/**
 * Selecting a run of files by dragging. There are two ways in. Holding a file and moving starts from
 * that file, with no need to be selecting already. While selecting, a drag that starts sideways does
 * the same, as in the photo grid, while one that starts up or down still scrolls. Near the top or
 * bottom the list scrolls by itself to carry the selection further.
 *
 * @param indexAt the position in [files] under a point in this list, or -1.
 * @param bottomObstruction how far up from the bottom the floating bars reach, so scrolling starts
 * before the finger is underneath them.
 */
@Composable
fun Modifier.dragSelect(
    hold: RowHold,
    files: () -> List<FileItem>,
    selected: () -> Set<Int>,
    selecting: () -> Boolean,
    indexAt: (Offset) -> Int,
    scrollBy: suspend (Float) -> Unit,
    onSelection: (Set<Int>) -> Unit,
    onSecondaryClick: (Int) -> Unit = {},
    bottomObstruction: androidx.compose.ui.unit.Dp = 150.dp
): Modifier {
    val scope = rememberCoroutineScope()
    val currentFiles by rememberUpdatedState(files)
    val currentSelected by rememberUpdatedState(selected)
    val currentSelecting by rememberUpdatedState(selecting)
    val currentIndexAt by rememberUpdatedState(indexAt)
    val currentScrollBy by rememberUpdatedState(scrollBy)
    val currentOnSelection by rememberUpdatedState(onSelection)
    val currentOnSecondary by rememberUpdatedState(onSecondaryClick)

    return this.pointerInput(hold) {
        val edge = 72.dp.toPx()
        val obstruction = bottomObstruction.toPx()
        val slop = viewConfiguration.touchSlop

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // A mouse's second button is a context menu, whatever's under it.
            if (down.type == PointerType.Mouse && currentEvent.buttons.isSecondaryPressed) {
                val index = currentIndexAt(down.position)
                if (index >= 0) currentOnSecondary(index)
                // The whole click, or the row underneath would take its release as a tap and open the file.
                down.consume()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                return@awaitEachGesture
            }
            hold.fingerDown = true
            hold.onRelease = null
            try {
                var started = false
                var heldAt: Offset? = null
                var pointer: PointerInputChange = down
                val startedFromHold: Boolean
                while (!started) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // A second finger is a pinch or a mistake, and either way not a selection.
                    if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
                    pointer = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    // Someone above already has this finger, which is a selection being carried off.
                    if (pointer.isConsumed) return@awaitEachGesture
                    if (!pointer.pressed) {
                        hold.onRelease?.invoke()
                        return@awaitEachGesture
                    }
                    if (hold.onRelease != null) {
                        // The list mustn't scroll from under a held row.
                        pointer.consume()
                        if ((pointer.position - down.position).getDistance() > slop) started = true
                    } else {
                        val moved = pointer.position - down.position
                        when {
                            currentSelecting() && abs(moved.x) > slop && abs(moved.x) > abs(moved.y) -> started = true
                            moved.getDistance() > slop * 2 -> return@awaitEachGesture
                        }
                    }
                }
                startedFromHold = hold.onRelease != null
                hold.onRelease = null

                val anchor = currentIndexAt(down.position)
                val list = currentFiles()
                if (anchor < 0 || anchor >= list.size) return@awaitEachGesture
                val base = HashSet(currentSelected())
                // Dragging from a chosen file while selecting clears the run, the way the photo grid does.
                // A hold is always adding: nothing about holding a chosen file says to remove it.
                val adding = startedFromHold || list[anchor].id !in base
                var last = anchor
                currentOnSelection(rangeSelection(base, list, anchor, anchor, adding))
                pointer.consume()

                var position = pointer.position
                var autoScroll: Job? = null
                fun selectUnderFinger() {
                    val index = currentIndexAt(position)
                    if (index >= 0 && index != last) {
                        last = index
                        currentOnSelection(rangeSelection(base, list, anchor, index, adding))
                    }
                }
                selectUnderFinger()
                fun scrollRate(y: Float): Float {
                    val bottomEdge = size.height - obstruction
                    return when {
                        y < edge -> -(edge - y) / edge
                        y > bottomEdge -> ((y - bottomEdge) / edge).coerceAtMost(1.5f)
                        else -> 0f
                    }
                }
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        change.consume()
                        position = change.position
                        selectUnderFinger()
                        if (scrollRate(position.y) == 0f) {
                            autoScroll?.cancel()
                            autoScroll = null
                        } else if (autoScroll == null) {
                            autoScroll = scope.launch {
                                while (isActive) {
                                    currentScrollBy(scrollRate(position.y) * 28.dp.toPx())
                                    selectUnderFinger()
                                    delay(16)
                                }
                            }
                        }
                    }
                } finally {
                    autoScroll?.cancel()
                }
            } finally {
                hold.fingerDown = false
                hold.onRelease = null
            }
        }
    }
}
