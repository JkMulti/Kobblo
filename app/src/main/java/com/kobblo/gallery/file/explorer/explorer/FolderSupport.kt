package com.kobblo.gallery.file.explorer.explorer

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.SortMode
import com.kobblo.gallery.file.explorer.explorer.search.ExplorerSearch
import java.io.File
import kotlinx.coroutines.delay

/** Which way a folder's sort runs, in the words of that sort. */
@Composable
fun sortDirectionLabel(mode: SortMode, reversed: Boolean, location: String): String? {
    if (!location.startsWith("/")) return null
    return when (mode) {
        SortMode.ALPHABETICAL, SortMode.KIND -> stringResource(if (reversed) R.string.explorer_sort_name_down else R.string.explorer_sort_name_up)
        SortMode.DATE -> stringResource(if (reversed) R.string.explorer_sort_date_old else R.string.explorer_sort_date_new)
        SortMode.SIZE -> stringResource(if (reversed) R.string.explorer_sort_size_small else R.string.explorer_sort_size_big)
    }
}

/**
 * The setting says how many tiles fit across a phone held upright. A phone on its side or a tablet keeps
 * about that tile size and fits more, rather than showing one row of enormous folders.
 */
class FolderGridCells(private val columnsOnPhone: Int) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = FolderViewRules.columnsFor(columnsOnPhone, availableSize.toDp().value)
        val space = availableSize - spacing * (count - 1)
        val cell = space / count
        val spare = space % count
        return List(count) { if (it < spare) cell + 1 else cell }
    }
}

/** Connects [dragSelect] to the two lists a folder can be drawn as. */
object DragSelectLists {
    @Composable
    fun Modifier.ofList(
        hold: RowHold,
        state: AppState,
        onAction: (AppAction) -> Unit,
        onMenu: (FileItem) -> Unit,
        listState: LazyListState
    ): Modifier = dragSelect(
        hold = hold,
        files = { state.files },
        selected = { state.selectedFiles },
        selecting = { state.isSelectionMode },
        indexAt = { position ->
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { position.y >= it.offset && position.y < it.offset + it.size }
                ?.index?.takeIf { it < state.files.size } ?: -1
        },
        scrollBy = { listState.scrollBy(it) },
        onSelection = { onAction(AppAction.SelectAll(it.toList())) },
        onSecondaryClick = { index -> state.files.getOrNull(index)?.let(onMenu) }
    )

    @Composable
    fun Modifier.ofGrid(
        hold: RowHold,
        state: AppState,
        onAction: (AppAction) -> Unit,
        onMenu: (FileItem) -> Unit,
        gridState: LazyGridState
    ): Modifier = dragSelect(
        hold = hold,
        files = { state.files },
        selected = { state.selectedFiles },
        selecting = { state.isSelectionMode },
        indexAt = { position ->
            gridState.layoutInfo.visibleItemsInfo
                .firstOrNull {
                    position.x >= it.offset.x && position.x < it.offset.x + it.size.width &&
                        position.y >= it.offset.y && position.y < it.offset.y + it.size.height
                }
                ?.index?.takeIf { it < state.files.size } ?: -1
        },
        scrollBy = { gridState.scrollBy(it) },
        onSelection = { onAction(AppAction.SelectAll(it.toList())) },
        onSecondaryClick = { index -> state.files.getOrNull(index)?.let(onMenu) }
    )
}

/**
 * Brings a file asked for by [ExplorerUi.showInFolder] into view once its folder has loaded, and returns
 * its id for as long as it should stand out, which is just long enough to catch the eye.
 */
@Composable
fun rememberReveal(state: AppState, listState: LazyListState, gridState: LazyGridState): Int? {
    var flashed by remember { mutableStateOf<Int?>(null) }
    val target = ExplorerUi.revealPath
    LaunchedEffect(target, state.files, state.isListMode) {
        val path = target ?: return@LaunchedEffect
        if (File(path).parent != state.location) return@LaunchedEffect
        val index = state.files.indexOfFirst { it.path == path }
        if (index < 0) {
            // Still loading, or it's gone. The next list to arrive tries again, and an empty one ends it.
            if (!state.isLoading) ExplorerUi.clearReveal()
            return@LaunchedEffect
        }
        ExplorerUi.clearReveal()
        // A little above it, so the file sits among its neighbours rather than under the header.
        if (state.isListMode) listState.scrollToItem((index - 2).coerceAtLeast(0)) else gridState.scrollToItem((index - 3).coerceAtLeast(0))
        flashed = state.files[index].id
    }
    LaunchedEffect(flashed) {
        if (flashed != null) {
            delay(1600)
            flashed = null
        }
    }
    return flashed
}

/**
 * The obvious keys, for a tablet or a phone with a keyboard. The search field keeps every key while it's
 * being typed in. Everything here is also a tap away, so nothing depends on having a keyboard.
 */
@Composable
fun explorerKeys(
    state: AppState,
    onAction: (AppAction) -> Unit,
    enabled: Boolean,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onFind: () -> Unit
): Modifier {
    if (!enabled) return Modifier
    val focus = remember { FocusRequester() }
    // Keys are only delivered to what holds focus, and leaving the search field lets go of it.
    LaunchedEffect(ExplorerSearch.fieldFocused) {
        if (!ExplorerSearch.fieldFocused) {
            try {
                focus.requestFocus()
            } catch (_: IllegalStateException) {
                // Not laid out yet. The next change asks again.
            }
        }
    }
    return Modifier
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || ExplorerSearch.fieldFocused) return@onPreviewKeyEvent false
            val selected = state.selectedFiles
            when {
                event.isCtrlPressed && event.key == Key.A -> {
                    onAction(AppAction.SelectAll(SelectionOps.allSelectable(state.files).toList()))
                    true
                }
                event.isCtrlPressed && event.key == Key.C && selected.isNotEmpty() -> {
                    onAction(AppAction.SetClipboard("copy"))
                    true
                }
                event.isCtrlPressed && event.key == Key.X && selected.isNotEmpty() -> {
                    onAction(AppAction.SetClipboard("cut"))
                    true
                }
                event.isCtrlPressed && event.key == Key.V && state.clipboardPaths.isNotEmpty() && state.location.startsWith("/") -> {
                    onAction(AppAction.PasteClipboard(state.location))
                    true
                }
                event.isCtrlPressed && event.key == Key.F -> {
                    onFind()
                    true
                }
                event.key == Key.Delete || event.key == Key.Backspace && selected.isNotEmpty() -> {
                    if (selected.isNotEmpty()) onDelete()
                    selected.isNotEmpty()
                }
                event.key == Key.Escape && selected.isNotEmpty() -> {
                    onAction(AppAction.ClearSelection)
                    true
                }
                event.key == Key.Backspace || (event.isAltPressed && event.key == Key.DirectionLeft) -> {
                    onBack()
                    true
                }
                else -> false
            }
        }
        .focusRequester(focus)
        .focusable()
}
