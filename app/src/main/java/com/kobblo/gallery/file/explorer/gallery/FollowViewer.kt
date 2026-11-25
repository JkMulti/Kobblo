package com.kobblo.gallery.file.explorer.gallery

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

/**
 * Keeps a list scrolled to the photo the viewer is on. Swiping through photos then leaves the list
 * where you'd expect it, and putting the viewer away flies the photo back into its own row rather than
 * fading it where it is. [indexOf] is the list's item for a path, or -1 for one it doesn't show.
 */
@Composable
fun FollowViewer(state: LazyListState, bottomPadding: Dp, indexOf: (String) -> Int) {
    val currentIndexOf by rememberUpdatedState(indexOf)
    val clearance = with(LocalDensity.current) { bottomPadding.toPx() }
    LaunchedEffect(state) {
        snapshotFlow { GalleryScope.viewerAt }.filterNotNull().collectLatest { path ->
            val index = currentIndexOf(path)
            if (index < 0) return@collectLatest
            val info = state.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
            val inView = item != null && item.offset >= info.viewportStartOffset &&
                item.offset + item.size <= info.viewportEndOffset - clearance
            if (!inView) state.scrollToItem(index, -(info.viewportSize.height / 3))
        }
    }
}

/** [FollowViewer] for a grid. */
@Composable
fun FollowViewer(state: LazyGridState, bottomPadding: Dp, indexOf: (String) -> Int) {
    val currentIndexOf by rememberUpdatedState(indexOf)
    val clearance = with(LocalDensity.current) { bottomPadding.toPx() }
    LaunchedEffect(state) {
        snapshotFlow { GalleryScope.viewerAt }.filterNotNull().collectLatest { path ->
            val index = currentIndexOf(path)
            if (index < 0) return@collectLatest
            val info = state.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
            val inView = item != null && item.offset.y >= info.viewportStartOffset &&
                item.offset.y + item.size.height <= info.viewportEndOffset - clearance
            if (!inView) state.scrollToItem(index, -(info.viewportSize.height / 3))
        }
    }
}
