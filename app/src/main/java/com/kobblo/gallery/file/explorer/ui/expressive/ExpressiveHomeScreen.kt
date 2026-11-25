package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.explorer.FolderGridCells
import com.kobblo.gallery.file.explorer.explorer.drag.DragList
import com.kobblo.gallery.file.explorer.explorer.drag.FileDragModifiers
import com.kobblo.gallery.file.explorer.explorer.drag.rememberRecentsDrag
import com.kobblo.gallery.file.explorer.explorer.search.SearchBackHandler
import com.kobblo.gallery.file.explorer.explorer.search.SearchResults
import com.kobblo.gallery.file.explorer.gallery.FollowViewer
import com.kobblo.gallery.file.explorer.ui.ExpressiveCircularLoader
import com.kobblo.gallery.file.explorer.ui.TabSwitchLatencyTracker
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloAppear
import com.kobblo.gallery.file.explorer.ui.design.rememberLargeTitleState
import java.util.Calendar
import java.util.SortedMap

/** How long ago a file changed, as the buckets the Recents screen groups by. */
internal enum class Recency(@StringRes val titleRes: Int) {
    TODAY(R.string.recents_today),
    YESTERDAY(R.string.recents_yesterday),
    WEEK(R.string.recents_week),
    EARLIER(R.string.recents_earlier)
}

/** The bucket for something changed at [lastModified], on the calendar [now] is set to. Days turn over at midnight there. */
internal fun recencyOf(lastModified: Long, now: Calendar): Recency {
    fun startOfDay(daysAgo: Int): Long = (now.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        lastModified >= startOfDay(0) -> Recency.TODAY
        lastModified >= startOfDay(1) -> Recency.YESTERDAY
        lastModified >= startOfDay(7) -> Recency.WEEK
        else -> Recency.EARLIER
    }
}

/**
 * What Recents lists: files only, hidden ones only when those are shown, newest first, under the
 * bucket each falls in. Buckets with nothing in them are left out.
 */
internal fun recentGroups(files: List<FileItem>, showHidden: Boolean, now: Calendar): SortedMap<Recency, List<FileItem>> =
    files
        .filter { it.type != "folder" && (showHidden || !it.isHidden) }
        .sortedByDescending { it.lastModified }
        .groupBy { recencyOf(it.lastModified, now) }
        .toSortedMap()

/**
 * Where the file at [path] sits in a list drawn as each group's heading followed by its files, or -1
 * if it isn't in any of them.
 */
internal fun positionAmongGroups(groups: Collection<List<FileItem>>, path: String): Int {
    var groupStart = 0
    for (files in groups) {
        val within = files.indexOfFirst { it.path == path }
        // One further on, past this group's own heading.
        if (within >= 0) return groupStart + 1 + within
        groupStart += 1 + files.size
    }
    return -1
}

/**
 * The Recents tab: what you touched lately, grouped by when, in the same grid as a folder. Where
 * files live is the Browse tab's job.
 */
@Composable
fun ExpressiveHomeScreen(
    state: AppState,
    onAction: (AppAction) -> Unit,
    onFileClick: (FileItem) -> Unit,
    onFileMenuClick: (FileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    SideEffect { TabSwitchLatencyTracker.onHomeCompose(state.recentFiles.size) }

    val largeTitle = rememberLargeTitleState(height = 100.dp)
    val bottomPadding = listClearance(state)
    val search = rememberFileSearch(state)
    SearchBackHandler(state, onAction)
    val rows = rememberLazyListState()
    val grid = rememberLazyGridState()
    val results = rememberLazyListState()
    val drag = rememberRecentsDrag(state, search.showsResults, rows, grid, results)
    val groups = remember(state.recentFiles, state.showHiddenFiles) {
        recentGroups(state.recentFiles, state.showHiddenFiles, Calendar.getInstance())
    }
    // Swiping through photos in the viewer keeps them in view here, past each group's heading.
    val positionOf: (String) -> Int = { path -> positionAmongGroups(groups.values, path) }
    if (state.isListMode) FollowViewer(rows, bottomPadding, positionOf) else FollowViewer(grid, bottomPadding, positionOf)
    val taps = rememberFileTaps(onAction, onFileClick, onFileMenuClick)

    CompositionLocalProvider(LocalSelectionActive provides state.isSelectionMode) {
        Column(modifier = modifier.fillMaxSize().nestedScroll(largeTitle.connection)) {
            FileScreenHeader(stringResource(R.string.nav_recents), state, onAction, largeTitle) {
                ViewModeButton(state.isListMode, onAction)
            }
            when {
                search.pane == SearchPane.Results ->
                    SearchResults(state, onAction, onFileClick, onFileMenuClick, bottomPadding, results, drag.results)

                search.pane == SearchPane.RecentSearches -> RecentSearchesPane(search, onAction, bottomPadding)

                groups.isNotEmpty() && state.isListMode -> RecentRows(groups, state, taps, rows, drag.rows, bottomPadding)

                groups.isNotEmpty() -> RecentGrid(groups, state, taps, grid, drag.grid, bottomPadding)

                !state.hasStoragePermission -> StorageAccessMessage(onAction)

                state.isLoading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.TopCenter) {
                    ExpressiveCircularLoader(size = 40.dp, color = KobbloDesign.colors.action)
                }

                else -> KobbloMessage(
                    icon = AppIcons.Clock,
                    title = stringResource(R.string.no_recent_files),
                    action = {
                        MessageButton(stringResource(R.string.nav_browse)) { onAction(AppAction.SetLocation(LOCATIONS)) }
                    }
                )
            }
        }
    }
}

/** List view: each bucket is an inset group of rows under its heading. */
@Composable
private fun RecentRows(
    groups: Map<Recency, List<FileItem>>,
    state: AppState,
    taps: FileTaps,
    listState: LazyListState,
    dragList: DragList,
    bottomPadding: Dp
) {
    LazyColumn(
        state = listState,
        modifier = with(FileDragModifiers) { Modifier.fillMaxSize().list(dragList) },
        contentPadding = PaddingValues(bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for ((recency, files) in groups) {
            item(key = "header_$recency") { KobbloGroupHeader(title = stringResource(recency.titleRes)) }
            // Dragging finds a file by this key, so it has to stay the file's id behind "recent_".
            itemsIndexed(files, key = { _, file -> "recent_${file.id}" }) { index, file ->
                FileRow(
                    file = file,
                    isSelected = file.id in state.selectedFiles,
                    taps = taps,
                    groupIndex = index,
                    groupCount = files.size,
                    modifier = Modifier.kobbloAppear(index)
                )
            }
        }
    }
}

/** Icon view: the same buckets, each heading running the width of the grid. */
@Composable
private fun RecentGrid(
    groups: Map<Recency, List<FileItem>>,
    state: AppState,
    taps: FileTaps,
    gridState: LazyGridState,
    dragList: DragList,
    bottomPadding: Dp
) {
    val columns = state.gridColumns.coerceIn(2, 4)
    LazyVerticalGrid(
        columns = FolderGridCells(columns),
        state = gridState,
        modifier = with(FileDragModifiers) { Modifier.fillMaxSize().list(dragList) },
        contentPadding = PaddingValues(start = KobbloSpace.screen, end = KobbloSpace.screen, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        for ((recency, files) in groups) {
            item(key = "header_$recency", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(recency.titleRes),
                    style = KobbloType.title,
                    color = KobbloDesign.colors.ink,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }
            gridItemsIndexed(files, key = { _, file -> "recent_${file.id}" }) { index, file ->
                FileCard(
                    file = file,
                    isSelected = file.id in state.selectedFiles,
                    taps = taps,
                    columns = columns,
                    modifier = Modifier.kobbloAppear(index)
                )
            }
        }
    }
}
