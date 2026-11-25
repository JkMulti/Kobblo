package com.kobblo.gallery.file.explorer.ui.expressive

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.explorer.DragSelectLists
import com.kobblo.gallery.file.explorer.explorer.FolderGridCells
import com.kobblo.gallery.file.explorer.explorer.LocalRowHold
import com.kobblo.gallery.file.explorer.explorer.RowHold
import com.kobblo.gallery.file.explorer.explorer.drag.FileDragModifiers
import com.kobblo.gallery.file.explorer.explorer.drag.FolderDragSource
import com.kobblo.gallery.file.explorer.explorer.drag.rememberFolderDrag
import com.kobblo.gallery.file.explorer.explorer.explorerKeys
import com.kobblo.gallery.file.explorer.explorer.rememberReveal
import com.kobblo.gallery.file.explorer.explorer.rememberRowHold
import com.kobblo.gallery.file.explorer.explorer.search.ExplorerSearch
import com.kobblo.gallery.file.explorer.explorer.search.SearchBackHandler
import com.kobblo.gallery.file.explorer.explorer.search.SearchResults
import com.kobblo.gallery.file.explorer.gallery.FollowViewer
import com.kobblo.gallery.file.explorer.getStorageIdForLocation
import com.kobblo.gallery.file.explorer.tools.storage.StorageVolumes
import com.kobblo.gallery.file.explorer.ui.ExpressiveCircularLoader
import com.kobblo.gallery.file.explorer.ui.TabSwitchLatencyTracker
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.LargeTitleState
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloAppear
import com.kobblo.gallery.file.explorer.ui.design.rememberLargeTitleState

/**
 * One storage location's folders. Each folder is its own page: the new one pushes in over the old
 * one, which keeps showing its own files until it has left the screen.
 */
@Composable
fun ExpressiveBrowseScreen(
    state: AppState,
    onAction: (AppAction) -> Unit,
    onFileClick: (FileItem) -> Unit,
    onFileMenuClick: (FileItem) -> Unit,
    onCreateClick: () -> Unit = {},
    onOpenVault: () -> Unit = {},
    onLaunchDrivePicker: (() -> Unit)? = null,
    onResolveAuthIntent: (() -> Unit)? = null,
    onDeleteRequest: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    SideEffect { TabSwitchLatencyTracker.onBrowseCompose(state.location, state.files.size, state.isLoading) }

    val storageId = rememberStorageId(state)
    val leave = { leaveFolder(state, storageId, onAction) }
    // While selecting, Back belongs to the selection.
    BackHandler(enabled = state.selectedFiles.isEmpty(), onBack = leave)

    val still = ExpressiveMotion.isReducedMotion()
    // Going back returns to a folder where you left it, scrolled to the same place.
    val pages = rememberSaveableStateHolder()
    // This screen is still drawn while the app slides away from it, by which time the location is already
    // the list of locations or another tab. Treated as a folder that would be an empty one, and it would
    // flash "This folder is empty" on the way out, so the page stays on the last real folder.
    val shown = remember { ShownFolder(state) }
    if (isFolderLocation(state.location)) shown.state = state

    AnimatedContent(
        targetState = shown.state,
        contentKey = { it.location },
        transitionSpec = { folderTransition(opening = depthOf(targetState) >= depthOf(initialState), still = still) },
        modifier = modifier.fillMaxSize(),
        label = "folder_push"
    ) { page ->
        CompositionLocalProvider(LocalSelectionActive provides page.isSelectionMode) {
            pages.SaveableStateProvider(page.location) {
                FolderPage(
                    state = page,
                    onAction = onAction,
                    onBack = leave,
                    onOpenFile = onFileClick,
                    onFileMenu = onFileMenuClick,
                    onCreate = onCreateClick,
                    onOpenVault = onOpenVault,
                    onPickFromDrive = onLaunchDrivePicker,
                    onRenewDriveAccess = onResolveAuthIntent,
                    onDelete = onDeleteRequest
                )
            }
        }
    }
}

/** The folder the screen is drawing, held outside of state: changing it is never a reason to draw again. */
private class ShownFolder(var state: AppState)

private fun depthOf(state: AppState): Int = pageDepth(state.location, state.driveFolderStack.size)

/** The id of the storage a state's location is on, as navigation names storages. */
@Composable
private fun rememberStorageId(state: AppState): String =
    remember(state.location, state.sdCardPath, state.googleDriveAccountEmail, state.megaAccountEmail) {
        getStorageIdForLocation(state.location, state.sdCardPath, state.googleDriveAccountEmail, state.megaAccountEmail)
    }

/** The root of the card [state]'s location is on: what the system reports for that path, else the card the app last knew. */
private fun cardRootFor(state: AppState): String? = StorageVolumes.removableRootOf(state.location) ?: state.sdCardPath

/**
 * Back, from the folder in [state]. A folder opened out of search results goes back to them, however
 * it was reached, with the search as it was. Any other goes up, or out to the list of locations.
 */
private fun leaveFolder(state: AppState, storageId: String, onAction: (AppAction) -> Unit) {
    val search = ExplorerSearch.takeReturn(state.location)
    if (search != null) {
        onAction(AppAction.SetLocation(search.location))
        ExplorerSearch.setFilters(search.filters)
        onAction(AppAction.SetQuery(search.query))
        return
    }
    val exit = folderExit(
        location = state.location,
        storageId = storageId,
        noFoldersOpened = state.driveFolderStack.isEmpty(),
        phoneRoot = Environment.getExternalStorageDirectory().absolutePath,
        cardRoot = cardRootFor(state)
    )
    when (exit) {
        is FolderExit.To -> onAction(AppAction.SetLocation(exit.location))
        FolderExit.StepBack -> onAction(AppAction.NavigateBackInDrive)
    }
}

/**
 * How one folder's page gives way to the next. Opening a folder slides it in from the side, over the
 * one it was in, which drifts a quarter of the way off and dims beneath it. Going back plays the same
 * thing the other way round, with the folder being left on top. With motion off the pages just swap.
 */
private fun folderTransition(opening: Boolean, still: Boolean): ContentTransform {
    if (still) return fadeIn(snap()) togetherWith fadeOut(snap())
    val slide = KobbloMotion.smooth<IntOffset>()
    val ease = KobbloMotion.EaseOut
    return if (opening) {
        val arriving = slideInHorizontally(slide) { width -> width } + fadeIn(KobbloMotion.fadeIn())
        val covered = slideOutHorizontally(slide) { width -> -width / 4 } + fadeOut(tween(KobbloMotion.Slow, easing = ease), targetAlpha = 0.5f)
        (arriving togetherWith covered).apply { targetContentZIndex = 1f }
    } else {
        val uncovered = slideInHorizontally(slide) { width -> -width / 4 } + fadeIn(tween(KobbloMotion.Default, easing = ease), initialAlpha = 0.5f)
        val departing = slideOutHorizontally(slide) { width -> width } + fadeOut(KobbloMotion.fadeOut())
        (uncovered togetherWith departing).apply { targetContentZIndex = -1f }
    }
}

/** Which of a folder's sheets is up. Picking Filter in the options closes those and opens the filter. */
private enum class FolderSheet { None, Options, Filter }

/** The ids of the files a folder's page has already drawn, so each settles into place only once. */
private class DrawnFiles {
    private val ids = mutableSetOf<Int>()

    /** True the first time it is asked about [id]. */
    fun isNew(id: Int): Boolean = ids.add(id)
}

@Composable
private fun FolderPage(
    state: AppState,
    onAction: (AppAction) -> Unit,
    onBack: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
    onFileMenu: (FileItem) -> Unit,
    onCreate: () -> Unit,
    onOpenVault: () -> Unit,
    onPickFromDrive: (() -> Unit)?,
    onRenewDriveAccess: (() -> Unit)?,
    onDelete: () -> Unit
) {
    val place = folderPlaceOf(state)
    val onDevice = place.isOnDevice
    val bottomPadding = listClearance(state)
    val largeTitle = rememberLargeTitleState(height = 100.dp)
    // Rows settle in the first time a folder shows them, but not again as they scroll back on.
    val drawn = remember { DrawnFiles() }
    // Owned here rather than left to each list, so they outlive the spinner and can be driven from outside.
    val rows = rememberLazyListState()
    val grid = rememberLazyGridState()
    val results = rememberLazyListState()
    val search = rememberFileSearch(state, searchesDevice = onDevice)
    val hold = rememberRowHold()
    val drag = rememberFolderDrag(state, onDevice, search.showsResults, rows, grid, results)
    val flashedId = rememberReveal(state, rows, grid)
    // Swiping through photos in the viewer keeps their rows in view, so putting it away lands each in its own.
    val positionOf: (String) -> Int = { path -> state.files.indexOfFirst { it.path == path } }
    if (state.isListMode) FollowViewer(rows, bottomPadding, positionOf) else FollowViewer(grid, bottomPadding, positionOf)
    val searchFocus = remember { FocusRequester() }
    var sheet by remember { mutableStateOf(FolderSheet.None) }

    // Back leaves a search before it leaves the folder. Away from the phone a search is only the
    // query, so clearing that is all there is to leave.
    if (onDevice) {
        SearchBackHandler(state, onAction)
    } else {
        BackHandler(enabled = state.selectedFiles.isEmpty() && state.query.isNotEmpty()) { onAction(AppAction.SetQuery("")) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPlaced { drag.pageCoordinates = it }
            .nestedScroll(largeTitle.connection)
            .then(
                explorerKeys(
                    state = state,
                    onAction = onAction,
                    enabled = onDevice,
                    onBack = onBack,
                    onDelete = onDelete,
                    onFind = { searchFocus.requestFocus() }
                )
            )
    ) {
        FolderHeader(
            state = state,
            place = place,
            search = search,
            largeTitle = largeTitle,
            searchFocus = searchFocus,
            onAction = onAction,
            onBack = onBack,
            onCreateClick = onCreate,
            onOpenVault = onOpenVault,
            onShowOptions = { sheet = FolderSheet.Options }
        )

        val inset = Modifier.padding(horizontal = KobbloSpace.screen)
        val onDrive = place.storageId.startsWith("drive")
        when {
            search.pane == SearchPane.Results ->
                SearchResults(state, onAction, onOpenFile, onFileMenu, bottomPadding, listState = results, dragList = drag.results)

            search.pane == SearchPane.RecentSearches -> RecentSearchesPane(search, onAction, bottomPadding)

            state.isLoading -> Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.TopCenter) {
                ExpressiveCircularLoader(size = 40.dp, color = KobbloDesign.colors.action)
            }

            // Without access a folder lists only its subfolders, each reading "0 files", so saying
            // why is more useful than a listing that looks like an empty phone.
            onDevice && !state.hasStoragePermission -> StorageAccessMessage(onAction, inset)

            state.errorMessage != null && state.files.isEmpty() -> FolderErrorMessage(
                reason = state.errorMessage,
                onRetry = { onAction(AppAction.Reload) },
                modifier = inset,
                // Google Drive can ask for its permission again, and says so by handing over an intent.
                onRenewAccess = if (onDrive && state.recoverableAuthIntent != null) {
                    { onRenewDriveAccess?.invoke() }
                } else null
            )

            isKeptClosed(state.location) && !state.hasShizuku -> RestrictedFolderMessage(state.location, onAction, inset)

            state.files.isEmpty() -> EmptyFolderMessage(
                query = state.query,
                // Drive only lists what this app was given, so an empty folder offers to be given more.
                action = if (onDrive && onPickFromDrive != null) {
                    { MessageButton(stringResource(R.string.select_files_from_drive), onPickFromDrive) }
                } else null
            )

            else -> FolderFiles(
                state = state,
                footnote = folderFootnote(state, place),
                taps = rememberFileTaps(onAction, onOpenFile, onFileMenu),
                onAction = onAction,
                onFileMenu = onFileMenu,
                hold = hold,
                drag = drag,
                rows = rows,
                grid = grid,
                drawn = drawn,
                flashedId = flashedId,
                bottomPadding = bottomPadding
            )
        }
    }

    when (sheet) {
        FolderSheet.None -> Unit
        FolderSheet.Options -> FolderOptionsSheet(
            state = state,
            onAction = onAction,
            onDismiss = { sheet = FolderSheet.None },
            onOpenFilter = { sheet = FolderSheet.Filter }
        )
        FolderSheet.Filter -> FilterSheet(
            currentFilter = state.filter,
            onDismiss = { sheet = FolderSheet.None },
            onSelectFilter = { picked ->
                onAction(AppAction.SetFilter(picked))
                sheet = FolderSheet.None
            }
        )
    }
}

/** The two folders in every storage that the system keeps apps out of. */
private fun isKeptClosed(location: String): Boolean = "Android/data" in location || "Android/obb" in location

@Composable
private fun folderPlaceOf(state: AppState): FolderPlace {
    val context = LocalContext.current
    val storages = remember(state, context) { connectedStorages(context, state) }
    return folderPlace(
        location = state.location,
        folderStack = state.driveFolderStack,
        folderName = state.currentFolderName,
        storageId = rememberStorageId(state),
        storages = storages,
        phoneRoot = Environment.getExternalStorageDirectory().absolutePath,
        cardRoot = cardRootFor(state),
        severalDriveAccounts = state.googleDriveAccounts.size > 1,
        severalMegaAccounts = state.megaAccounts.size > 1,
        unnamedStorage = stringResource(R.string.screen_storage)
    )
}

/** The line under a folder's list: how much is in it and, on the phone, how much room is left. */
@Composable
private fun folderFootnote(state: AppState, place: FolderPlace): String {
    val count = state.files.size
    val freeGb = place.freeGb(state.storageFreeGb, state.sdCardStorageFreeGb)
    return if (freeGb == null) {
        pluralStringResource(R.plurals.items_count, count, count)
    } else {
        pluralStringResource(R.plurals.screen_folder_footer_available, count, count, "%.1f".format(freeGb))
    }
}

@Composable
private fun FolderHeader(
    state: AppState,
    place: FolderPlace,
    search: FileSearch,
    largeTitle: LargeTitleState,
    searchFocus: FocusRequester,
    onAction: (AppAction) -> Unit,
    onBack: () -> Unit,
    onCreateClick: () -> Unit,
    onOpenVault: () -> Unit,
    onShowOptions: () -> Unit
) {
    val backTitle = place.backTitle ?: stringResource(R.string.nav_browse)
    val cameFromSearch = ExplorerSearch.returnTo?.openedFolder == state.location
    // While files are being carried, the way back is also somewhere to put them: the folder above.
    val above = place.parentPath
    val dropOnBack = if (above != null && state.isSelectionMode && !search.showsResults) {
        with(FileDragModifiers) { Modifier.folderTarget(above, backTitle, KobbloDesign.colors.action, CapsuleShape) }
    } else Modifier

    FileScreenHeader(
        title = place.title,
        state = state,
        onAction = onAction,
        largeTitle = largeTitle,
        onBack = onBack,
        backLabel = if (cameFromSearch) stringResource(R.string.explorer_back_to_search) else backTitle,
        backModifier = dropOnBack,
        searchesDevice = place.isOnDevice,
        searchFocus = searchFocus,
        belowFilters = {
            // A breadcrumb only earns its space once it can skip more than the back button can.
            if (place.depth >= 2 && !search.showsResults) {
                BrowseBreadcrumbs(
                    crumbs = breadcrumbs(state.location, place.root, place.storageName, state.driveFolderStack, state.currentFolderName),
                    location = state.location,
                    onNavigate = { onAction(AppAction.SetLocation(it)) }
                )
            }
            if (state.filter != NoFilter) {
                ExpressiveChip(
                    label = filterLabel(state.filter),
                    selected = true,
                    onClick = { onAction(AppAction.SetFilter(NoFilter)) },
                    modifier = Modifier.padding(top = 8.dp),
                    icon = AppIcons.Close,
                    iconSize = 15.dp
                )
            }
        }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KobbloIconButton(
                icon = AppIcons.Add,
                description = stringResource(R.string.create_title),
                onClick = onCreateClick,
                onLongClick = onOpenVault
            )
            KobbloIconButton(
                icon = AppIcons.MoreHoriz,
                description = stringResource(R.string.screen_view_options),
                onClick = onShowOptions
            )
        }
    }
}

/** How many of a folder's first files rise into place when it opens. Past these, a file is simply there. */
private const val SettlingFiles = 14

/** A file whose place changes slides to its new one, and one that goes fades out. Arriving is left to [kobbloAppear]. */
private val FileMoves: FiniteAnimationSpec<IntOffset> = KobbloMotion.smooth()
private val FileLeaves: FiniteAnimationSpec<Float> = KobbloMotion.fadeOut()

/**
 * A folder's files, as rows or as icons. Both views are indexed the same way as [AppState.files] and
 * end with one more item, the footnote, which is what dragging and drag-selecting count on.
 */
@Composable
private fun FolderFiles(
    state: AppState,
    footnote: String,
    taps: FileTaps,
    onAction: (AppAction) -> Unit,
    onFileMenu: (FileItem) -> Unit,
    hold: RowHold,
    drag: FolderDragSource,
    rows: LazyListState,
    grid: LazyGridState,
    drawn: DrawnFiles,
    flashedId: Int?,
    bottomPadding: Dp
) {
    val files = state.files
    CompositionLocalProvider(LocalRowHold provides hold) {
        if (state.isListMode) {
            LazyColumn(
                state = rows,
                modifier = with(DragSelectLists) {
                    with(FileDragModifiers) { Modifier.fillMaxSize().list(drag.rows).ofList(hold, state, onAction, onFileMenu, rows) }
                },
                contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(files, key = { _, file -> file.id }) { index, file ->
                    val settles = remember(file.id) { drawn.isNew(file.id) && index < SettlingFiles }
                    FileRow(
                        file = file,
                        isSelected = file.id in state.selectedFiles,
                        taps = taps,
                        groupIndex = index,
                        groupCount = files.size,
                        flashed = file.id == flashedId,
                        modifier = Modifier
                            .animateItem(fadeInSpec = null, placementSpec = FileMoves, fadeOutSpec = FileLeaves)
                            .settlingIn(settles, index)
                    )
                }
                item(key = FootnoteKey) { FolderFootnote(footnote) }
            }
        } else {
            val columns = state.gridColumns.coerceIn(2, 4)
            // A card has no surface of its own, so one that is being pointed out is given the tint behind it.
            val pointedOut = Modifier.background(KobbloDesign.colors.action.copy(alpha = 0.12f), KobbloShapes.sm)
            LazyVerticalGrid(
                columns = FolderGridCells(columns),
                state = grid,
                modifier = with(DragSelectLists) {
                    with(FileDragModifiers) { Modifier.fillMaxSize().list(drag.grid).ofGrid(hold, state, onAction, onFileMenu, grid) }
                },
                contentPadding = PaddingValues(start = KobbloSpace.screen, end = KobbloSpace.screen, top = 8.dp, bottom = bottomPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                gridItemsIndexed(files, key = { _, file -> file.id }) { index, file ->
                    val settles = remember(file.id) { drawn.isNew(file.id) && index < SettlingFiles }
                    FileCard(
                        file = file,
                        isSelected = file.id in state.selectedFiles,
                        taps = taps,
                        columns = columns,
                        modifier = (if (file.id == flashedId) pointedOut else Modifier)
                            .animateItem(fadeInSpec = null, placementSpec = FileMoves, fadeOutSpec = FileLeaves)
                            .settlingIn(settles, index)
                    )
                }
                item(key = FootnoteKey, span = { GridItemSpan(maxLineSpan) }) { FolderFootnote(footnote) }
            }
        }
    }
}

private const val FootnoteKey = "folder_footer"

private fun Modifier.settlingIn(settles: Boolean, index: Int): Modifier = if (settles) kobbloAppear(index) else this

@Composable
private fun FolderFootnote(text: String) {
    Text(
        text = text,
        style = KobbloType.footnote,
        color = KobbloDesign.colors.smoke,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)
    )
}
