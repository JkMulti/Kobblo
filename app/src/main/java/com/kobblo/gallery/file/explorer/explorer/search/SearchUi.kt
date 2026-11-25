package com.kobblo.gallery.file.explorer.explorer.search

import androidx.compose.foundation.layout.heightIn
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.explorer.LocalRowHold
import com.kobblo.gallery.file.explorer.explorer.PathTools
import com.kobblo.gallery.file.explorer.explorer.dragSelect
import com.kobblo.gallery.file.explorer.explorer.rememberRowHold
import com.kobblo.gallery.file.explorer.tools.RouteMemory
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.tools.storage.StorageVolumes
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.design.quietCombinedClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveFileThumbnail
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSheetContent
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveTheme
import com.kobblo.gallery.file.explorer.ui.expressive.formatFileMeta
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.pressedRowFill
import com.kobblo.gallery.file.explorer.ui.design.rowDivider

private enum class FilterSheet { Kind, Size, Date }

/**
 * Back leaves a search before it leaves the screen. With the keyboard up the system takes the first Back
 * to close it, and the field keeps the cursor, so this is what the next one does.
 */
@Composable
fun SearchBackHandler(state: AppState, onAction: (AppAction) -> Unit) {
    val focus = LocalFocusManager.current
    val filters by ExplorerSearch.filters.collectAsState()
    BackHandler(enabled = state.selectedFiles.isEmpty() && (ExplorerSearch.fieldFocused || state.query.isNotEmpty() || filters.narrows)) {
        focus.clearFocus()
        ExplorerSearch.setFilters(SearchFilters())
        onAction(AppAction.SetQuery(""))
    }
}

/**
 * The filters, which exist only once a search has begun. Nothing here takes room from someone who is
 * just browsing a folder. Each one reads as its current value, so what's narrowing the list stays in sight.
 */
@Composable
fun SearchFilterBar(state: AppState, onAction: (AppAction) -> Unit) {
    val filters by ExplorerSearch.filters.collectAsState()
    val visible = ExplorerSearch.fieldFocused || state.query.isNotEmpty() || filters.narrows
    var sheet by remember { mutableStateOf<FilterSheet?>(null) }

    fun apply(next: SearchFilters) {
        ExplorerSearch.setFilters(next)
        onAction(AppAction.SetQuery(state.query))
    }

    AnimatedVisibility(visible = visible) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Scope means something only from inside a folder. Everywhere else already is everywhere.
            if (state.location.startsWith("/")) {
                SearchChip(stringResource(R.string.explorer_scope_folder), active = !filters.everywhere) {
                    if (filters.everywhere) apply(filters.copy(everywhere = false))
                }
                SearchChip(stringResource(R.string.explorer_scope_everywhere), active = filters.everywhere) {
                    if (!filters.everywhere) apply(filters.copy(everywhere = true))
                }
            }
            SearchChip(
                label = filters.kind?.let { stringResource(labelOf(it)) } ?: stringResource(R.string.explorer_filter_kind),
                active = filters.kind != null,
                trailing = AppIcons.ChevronDown
            ) { sheet = FilterSheet.Kind }
            SearchChip(
                label = filters.size?.let { stringResource(labelOf(it)) } ?: stringResource(R.string.explorer_filter_size),
                active = filters.size != null,
                trailing = AppIcons.ChevronDown
            ) { sheet = FilterSheet.Size }
            SearchChip(
                label = filters.date?.let { stringResource(labelOf(it)) } ?: stringResource(R.string.explorer_filter_date),
                active = filters.date != null,
                trailing = AppIcons.ChevronDown
            ) { sheet = FilterSheet.Date }
        }
    }

    when (sheet) {
        FilterSheet.Kind -> OptionSheet(
            title = stringResource(R.string.explorer_filter_kind),
            options = SearchKind.entries,
            selected = filters.kind,
            label = { stringResource(labelOf(it)) },
            onDismiss = { sheet = null },
            onPick = {
                sheet = null
                apply(filters.copy(kind = it))
            }
        )
        FilterSheet.Size -> OptionSheet(
            title = stringResource(R.string.explorer_filter_size),
            options = SearchSize.entries,
            selected = filters.size,
            label = { stringResource(labelOf(it)) },
            onDismiss = { sheet = null },
            onPick = {
                sheet = null
                apply(filters.copy(size = it))
            }
        )
        FilterSheet.Date -> OptionSheet(
            title = stringResource(R.string.explorer_filter_date),
            options = SearchDate.entries,
            selected = filters.date,
            label = { stringResource(labelOf(it)) },
            onDismiss = { sheet = null },
            onPick = {
                sheet = null
                apply(filters.copy(date = it))
            }
        )
        null -> Unit
    }
}

private fun labelOf(kind: SearchKind): Int = when (kind) {
    SearchKind.Photos -> R.string.explorer_kind_photos
    SearchKind.Videos -> R.string.explorer_kind_videos
    SearchKind.Audio -> R.string.explorer_kind_audio
    SearchKind.Documents -> R.string.explorer_kind_documents
    SearchKind.Archives -> R.string.explorer_kind_archives
    SearchKind.Apps -> R.string.explorer_kind_apps
    SearchKind.Folders -> R.string.explorer_kind_folders
}

private fun labelOf(size: SearchSize): Int = when (size) {
    SearchSize.Under1Mb -> R.string.explorer_size_under_1mb
    SearchSize.To100Mb -> R.string.explorer_size_to_100mb
    SearchSize.To1Gb -> R.string.explorer_size_to_1gb
    SearchSize.Over1Gb -> R.string.explorer_size_over_1gb
}

private fun labelOf(date: SearchDate): Int = when (date) {
    SearchDate.Today -> R.string.explorer_date_today
    SearchDate.Week -> R.string.explorer_date_week
    SearchDate.Month -> R.string.explorer_date_month
    SearchDate.Year -> R.string.explorer_date_year
}

@Composable
private fun SearchChip(
    label: String,
    active: Boolean,
    trailing: ImageVector? = null,
    onClick: () -> Unit
) {
    val colors = ExpressiveTheme.colors
    val design = KobbloDesign.colors
    val source = remember { MutableInteractionSource() }
    val content = if (active) colors.ink else design.ink
    Row(
        modifier = Modifier
            .height(32.dp)
            .pressScale(pressedScale = 0.95f, interactionSource = source)
            .clip(CapsuleShape)
            .background(if (active) design.action else design.snow)
            .quietClickable(source = source, onClick = onClick)
            .padding(start = 12.dp, end = if (trailing != null) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = KobbloType.footnote.copy(fontWeight = FontWeight.Medium),
            color = content,
            maxLines = 1
        )
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            Icon(trailing, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> OptionSheet(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onPick: (T?) -> Unit
) {
    val design = KobbloDesign.colors
    KobbloSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        ExpressiveSheetContent(title = title) {
            KobbloGroup {
                KobbloRow(
                    title = stringResource(R.string.explorer_filter_any),
                    trailing = {
                        if (selected == null) Icon(AppIcons.Check, contentDescription = null, tint = design.action, modifier = Modifier.size(20.dp))
                    },
                    showDivider = true,
                    onClick = { onPick(null) }
                )
                options.forEachIndexed { index, option ->
                    KobbloRow(
                        title = label(option),
                        trailing = {
                            if (option == selected) Icon(AppIcons.Check, contentDescription = null, tint = design.action, modifier = Modifier.size(20.dp))
                        },
                        showDivider = index < options.lastIndex,
                        onClick = { onPick(option) }
                    )
                }
            }
        }
    }
}

/** What was searched for lately, offered the moment the field takes the cursor and gone once typing starts. */
@Composable
fun RecentSearches(
    recent: List<String>,
    onPick: (String) -> Unit,
    bottomPadding: Dp
) {
    val design = KobbloDesign.colors
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = KobbloSpace.screen),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KobbloGroupHeader(
                    title = stringResource(R.string.explorer_recent_searches),
                    modifier = Modifier.weight(1f).padding(top = 0.dp)
                )
                Text(
                    text = stringResource(R.string.explorer_clear),
                    style = KobbloType.callout,
                    color = design.action,
                    modifier = Modifier
                        .clip(CapsuleShape)
                        .clickable { ExplorerSearch.clearRecent() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        item(key = "group") {
            KobbloGroup {
                recent.forEachIndexed { index, text ->
                    KobbloRow(
                        title = text,
                        leading = { Icon(AppIcons.Clock, contentDescription = null, tint = design.smoke, modifier = Modifier.size(22.dp)) },
                        showDivider = index < recent.lastIndex,
                        onClick = { onPick(text) }
                    )
                }
            }
        }
    }
}

/**
 * Search results as one inset group. Every row says which folder it's in, because two files with the
 * same name are otherwise the same row. Scrolling puts the keyboard away, so all of them can be read.
 */
@Composable
internal fun SearchResults(
    state: AppState,
    onAction: (AppAction) -> Unit,
    onFileClick: (FileItem) -> Unit,
    onFileMenuClick: (FileItem) -> Unit,
    bottomPadding: Dp,
    listState: LazyListState = rememberLazyListState(),
    dragList: com.kobblo.gallery.file.explorer.explorer.drag.DragList? = null
) {
    val focus = LocalFocusManager.current
    val files = state.files
    val capped by ExplorerSearch.capped.collectAsState()
    val volumes by StorageVolumes.volumes.collectAsState()
    val roots = remember(volumes) { volumes.map { it.path to it.name } }
    val text = remember(state.query) { SearchText(state.query) }
    val hold = rememberRowHold()
    val dismissKeyboard = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && ExplorerSearch.fieldFocused) focus.clearFocus()
                return Offset.Zero
            }
        }
    }

    if (files.isEmpty()) {
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                com.kobblo.gallery.file.explorer.ui.ExpressiveCircularLoader(size = 36.dp, color = KobbloDesign.colors.action)
            }
        } else {
            Column {
                KobbloMessage(
                    icon = AppIcons.Search,
                    title = if (state.query.isNotEmpty()) stringResource(R.string.no_files_found_query, state.query) else stringResource(R.string.explorer_no_matches)
                )
                if (state.query.length >= 2) SearchInsideRow(state.query, state.location)
            }
        }
        return
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalRowHold provides hold) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (dragList != null) {
                        with(com.kobblo.gallery.file.explorer.explorer.drag.FileDragModifiers) { Modifier.list(dragList) }
                    } else Modifier
                )
                .nestedScroll(dismissKeyboard)
                .dragSelect(
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
                    onSecondaryClick = { index -> state.files.getOrNull(index)?.let(onFileMenuClick) }
                ),
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding)
        ) {
            items(files.size, key = { files[it].id }) { index ->
                val file = files[index]
                SearchResultRow(
                    file = file,
                    text = text,
                    folderLine = remember(file.path, roots) { PathTools.folderLine(file.path, roots) },
                    isSelected = state.selectedFiles.contains(file.id),
                    groupIndex = index,
                    groupCount = files.size,
                    onSelect = { onAction(AppAction.ToggleSelection(file.id)) },
                    onClick = {
                        ExplorerSearch.remember(state.query)
                        // Back from a folder opened out of the results comes back to them.
                        if (file.type == "folder" && !state.isSelectionMode) ExplorerSearch.rememberReturn(state.location, state.query, file.path)
                        onFileClick(file)
                    },
                    onMenuClick = {
                        ExplorerSearch.remember(state.query)
                        onFileMenuClick(file)
                    }
                )
            }
            item(key = "search_footer") {
                Column {
                    Text(
                        text = when {
                            capped -> stringResource(R.string.explorer_results_capped, SearchEngine.MaxResults)
                            state.isLoading -> stringResource(R.string.explorer_searching_more, files.size)
                            else -> pluralStringResource(R.plurals.explorer_results, files.size, files.size)
                        },
                        style = KobbloType.footnote,
                        color = KobbloDesign.colors.smoke,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)
                    )
                    if (state.query.length >= 2) SearchInsideRow(state.query, state.location)
                }
            }
        }
    }
}

/** A name search and a search of what's inside files are one idea, so one leads straight to the other. */
@Composable
private fun SearchInsideRow(query: String, location: String) {
    val root = if (location.startsWith("/")) location else android.os.Environment.getExternalStorageDirectory().absolutePath
    KobbloGroup {
        KobbloRow(
            title = stringResource(R.string.explorer_search_inside, query),
            leading = { Icon(AppIcons.FileText, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(24.dp)) },
            trailing = { KobbloChevron() },
            onClick = {
                RouteMemory.put("search:$root", query)
                Hub.open(ToolsRoute.ContentSearch(root))
            }
        )
    }
}

@Composable
private fun SearchResultRow(
    file: FileItem,
    text: SearchText,
    folderLine: String,
    isSelected: Boolean,
    groupIndex: Int,
    groupCount: Int,
    onSelect: () -> Unit,
    onClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    val colors = KobbloDesign.colors
    val haptics = com.kobblo.gallery.file.explorer.haptics.LocalHaptics.current
    val hold = LocalRowHold.current
    val source = remember { MutableInteractionSource() }
    val pressFill = pressedRowFill(source)
    val onLongClick = {
        // Holding what's chosen picks all of it up. Anything else is a hold as it's always been.
        if (!isSelected || !com.kobblo.gallery.file.explorer.explorer.drag.FileDrag.lift(file)) {
            haptics.tap()
            if (hold?.arm(onMenuClick) != true) onMenuClick()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen)
            .clip(KobbloShapes.groupItem(groupIndex, groupCount))
            .background(colors.snow)
            .background(if (isSelected) colors.action.copy(alpha = 0.12f) else colors.action.copy(alpha = 0f))
            .background(pressFill)
            .alpha(if (file.isHidden) 0.55f else 1f)
            .quietCombinedClickable(source = source, onLongClick = onLongClick, onClick = onClick)
            .rowDivider(groupIndex < groupCount - 1, com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.inset + com.kobblo.gallery.file.explorer.ui.design.KobbloSize.thumbnail + com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.leadingGap, colors.hairline)
            // At least as tall as the picture with the inset above and below, so the picture sits as far
            // from the group's top and bottom as from its side.
            .heightIn(min = com.kobblo.gallery.file.explorer.ui.design.KobbloSize.fileRow)
            .padding(horizontal = com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.inset, vertical = com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.quietCombinedClickable(onLongClick = onLongClick, onClick = onSelect)) {
            ExpressiveFileThumbnail(
                file = file,
                isSelected = isSelected,
                size = com.kobblo.gallery.file.explorer.ui.design.KobbloSize.thumbnail,
                flightCorner = com.kobblo.gallery.file.explorer.ui.design.KobbloSize.thumbnail * com.kobblo.gallery.file.explorer.ui.expressive.TileCornerShare
            )
        }
        Spacer(Modifier.width(com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.leadingGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = remember(file.name, text) { highlighted(file.name, text) },
                style = KobbloType.body,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (file.type != "folder") {
                Text(
                    text = formatFileMeta(file),
                    style = KobbloType.footnote,
                    color = colors.smoke,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Folder, contentDescription = null, tint = colors.smoke, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                // The folder nearest the file is the one that tells two copies apart, so that's the end kept.
                Text(
                    text = folderLine,
                    style = KobbloType.footnote,
                    color = colors.smoke,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis
                )
            }
        }
        Spacer(Modifier.width(KobbloSpace.xs))
        KobbloIconButton(
            icon = AppIcons.MoreHoriz,
            description = stringResource(R.string.explorer_menu),
            onClick = onMenuClick,
            size = com.kobblo.gallery.file.explorer.ui.design.KobbloSize.rowButton,
            iconSize = 17.dp,
            tint = colors.smoke,
            background = colors.mist
        )
    }
}

/** The name with what matched in a heavier weight, so a long list shows at a glance why each row is in it. */
private fun highlighted(name: String, text: SearchText): AnnotatedString = buildAnnotatedString {
    append(name)
    var covered = 0
    for (range in text.ranges(name)) {
        val start = maxOf(range.first, covered)
        val end = range.last + 1
        if (start < end) {
            addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, end)
            covered = end
        }
    }
}
