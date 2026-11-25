package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.explorer.search.ExplorerSearch
import com.kobblo.gallery.file.explorer.explorer.search.RecentSearches
import com.kobblo.gallery.file.explorer.explorer.search.SearchFilterBar
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.LargeTitleState
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

// What Recents, Browse and a folder have in common: a header that searches, what the search puts in
// place of the screen's own list, the room a list leaves at its foot, and what is said when the app
// may not read files at all.

/**
 * The header of a screen of files. Its search field edits the app's query, and a search that is
 * submitted is kept among the recent ones.
 *
 * [searchesDevice] is false where the files aren't on the phone. Only their names can be searched
 * there, so the filters stay away and the field's focus isn't followed to offer recent searches.
 */
@Composable
internal fun FileScreenHeader(
    title: String,
    state: AppState,
    onAction: (AppAction) -> Unit,
    largeTitle: LargeTitleState,
    onBack: (() -> Unit)? = null,
    backLabel: String = stringResource(R.string.back),
    backModifier: Modifier = Modifier,
    searchesDevice: Boolean = true,
    searchFocus: FocusRequester? = null,
    belowFilters: (@Composable ColumnScope.() -> Unit)? = null,
    actions: @Composable () -> Unit
) {
    ExpressiveScreenHeader(
        title = title,
        modifier = Modifier.padding(horizontal = KobbloSpace.screen),
        onBack = onBack,
        backLabel = backLabel,
        backModifier = backModifier,
        rightAction = actions,
        largeTitle = largeTitle,
        showSearch = true,
        query = state.query,
        onQueryChange = { typed -> onAction(AppAction.SetQuery(typed)) },
        onSearchSubmit = { ExplorerSearch.remember(state.query) },
        onSearchFocusChanged = if (searchesDevice) { hasCursor -> ExplorerSearch.fieldFocused = hasCursor } else null,
        searchFocusRequester = searchFocus,
        belowTitle = {
            if (searchesDevice) SearchFilterBar(state, onAction)
            belowFilters?.invoke(this)
        }
    )
}

/** What a screen of files shows in place of its own list while its search field is in use. */
internal enum class SearchPane { Results, RecentSearches, None }

/**
 * Decides the pane. Anything typed, or a filter that narrows, means results. Before that, a field
 * that has just taken the cursor offers the recent searches if there are any. Where the phone's files
 * aren't what is searched, neither applies: the list itself is filtered by name.
 */
internal fun searchPane(
    searchesDevice: Boolean,
    query: String,
    filtersNarrow: Boolean,
    fieldFocused: Boolean,
    hasRecentSearches: Boolean
): SearchPane = when {
    !searchesDevice -> SearchPane.None
    query.isNotEmpty() || filtersNarrow -> SearchPane.Results
    fieldFocused && hasRecentSearches -> SearchPane.RecentSearches
    else -> SearchPane.None
}

/** A screen's search as it stands: which [pane] shows, and the [recent] searches to offer in it. */
internal class FileSearch(val pane: SearchPane, val recent: List<String>) {
    val showsResults: Boolean get() = pane == SearchPane.Results
}

@Composable
internal fun rememberFileSearch(state: AppState, searchesDevice: Boolean = true): FileSearch {
    val filters by ExplorerSearch.filters.collectAsState()
    val recent by ExplorerSearch.recent.collectAsState()
    val pane = searchPane(searchesDevice, state.query, filters.narrows, ExplorerSearch.fieldFocused, recent.isNotEmpty())
    return FileSearch(pane, recent)
}

/** The recent searches, any of which becomes the query when tapped. */
@Composable
internal fun RecentSearchesPane(search: FileSearch, onAction: (AppAction) -> Unit, bottomPadding: Dp) {
    RecentSearches(
        recent = search.recent,
        onPick = { picked -> onAction(AppAction.SetQuery(picked)) },
        bottomPadding = bottomPadding
    )
}

/**
 * The room a list leaves at its foot for what floats over it: the tab bar and the system's own bar,
 * the mini player while something plays, and the selection bar, which is taller than the tab bar it
 * takes the place of. [aboveTabBar] is the room for the tab bar itself.
 */
@Composable
internal fun listClearance(
    state: AppState,
    aboveTabBar: Dp = KobbloSpace.tabBarClearance,
    withSelectionBar: Boolean = true
): Dp {
    val systemBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val miniPlayer = if (state.currentAudioFile != null) KobbloSpace.miniPlayerClearance else 0.dp
    val selectionBar = if (withSelectionBar && state.isSelectionMode) selectionBarClearance() else 0.dp
    return aboveTabBar + systemBar + miniPlayer + selectionBar
}

/** The toolbar button that turns a list into icons and back. It shows the view it leads to, not the one in use. */
@Composable
internal fun ViewModeButton(isListMode: Boolean, onAction: (AppAction) -> Unit) {
    KobbloIconButton(
        icon = if (isListMode) AppIcons.Grid else AppIcons.List,
        description = stringResource(if (isListMode) R.string.screen_show_as_icons else R.string.screen_show_as_list),
        onClick = { onAction(AppAction.ToggleViewMode) }
    )
}

/** A message's one button, in the tint. */
@Composable
internal fun MessageButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Stands where files would be when the app hasn't been allowed to read them. A list that looked
 * like an empty phone would say nothing, so this says why and offers the way to allow it.
 */
@Composable
internal fun StorageAccessMessage(
    onAction: (AppAction) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = AppIcons.FolderShared
) {
    KobbloMessage(
        icon = icon,
        title = stringResource(R.string.permission_dialog_title),
        body = stringResource(R.string.permission_dialog_message),
        modifier = modifier,
        action = {
            MessageButton(stringResource(R.string.permission_grant_access)) { onAction(AppAction.RequestStorageAccess) }
        }
    )
}
