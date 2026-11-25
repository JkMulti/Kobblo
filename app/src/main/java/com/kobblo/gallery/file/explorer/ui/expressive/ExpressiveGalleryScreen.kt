package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.gallery.AlbumGridScreen
import com.kobblo.gallery.file.explorer.gallery.AlbumPickerSheet
import com.kobblo.gallery.file.explorer.gallery.AlbumTransfer
import com.kobblo.gallery.file.explorer.gallery.AlbumsScreen
import com.kobblo.gallery.file.explorer.gallery.GalleryDest
import com.kobblo.gallery.file.explorer.gallery.GalleryFeed
import com.kobblo.gallery.file.explorer.gallery.GalleryGrid
import com.kobblo.gallery.file.explorer.gallery.GalleryNav
import com.kobblo.gallery.file.explorer.gallery.GalleryScope
import com.kobblo.gallery.file.explorer.gallery.GalleryStore
import com.kobblo.gallery.file.explorer.gallery.LiveMediaSync
import com.kobblo.gallery.file.explorer.gallery.MediaIndex
import com.kobblo.gallery.file.explorer.gallery.SectionLabels
import com.kobblo.gallery.file.explorer.gallery.TransferPill
import com.kobblo.gallery.file.explorer.gallery.buildGalleryFeed
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.LargeTitleState
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.rememberLargeTitleState
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HomePageCount = 4
private const val PhotoPageCount = 3
private const val AudioPage = 3
private const val OtherPagesDelayMillis = 500L

/**
 * The Home tab: every photo and video on the device as one feed, newest first. The tiles run edge to
 * edge with hairline gaps so the pictures do the talking, under headers for the recent days and then
 * for each month. Albums open inside the tab, so the tab bar and selection bar stay with them.
 */
@Composable
fun ExpressiveGalleryScreen(
    state: AppState,
    onAction: (AppAction) -> Unit,
    onFileClick: (FileItem) -> Unit,
    onFileMenuClick: (FileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val store = remember { GalleryStore.get(context) }
    val columns by store.columns.collectAsState()
    val dest = GalleryNav.current
    val reduced = ExpressiveMotion.isReducedMotion()
    val bottomPadding = listClearance(state)
    val labels = SectionLabels(stringResource(R.string.recents_today), stringResource(R.string.recents_yesterday))

    // These outlive a trip into Albums, so coming back finds the feed where it was left.
    val largeTitle = rememberLargeTitleState(height = 100.dp)
    val pager = rememberPagerState(pageCount = { HomePageCount })
    // Each page keeps its own place, so swiping away and back doesn't lose it.
    val gridStates = List(PhotoPageCount) { rememberLazyGridState() }
    val feeds = remember(labels) { HomeFeeds(labels) }

    // New, changed and deleted media shows up by itself while this tab is on screen.
    if (state.location == "gallery") {
        LiveMediaSync(state.galleryFiles.size) { onAction(AppAction.Reload) }
    }

    // Smart albums need more than the feed holds. That's only worth reading once albums are in use.
    val inAlbums = dest != null
    LaunchedEffect(inAlbums, state.galleryFiles) {
        if (inAlbums) MediaIndex.get(context).refresh(state.galleryFiles)
    }

    // A selection belongs to the feed it was made in, so Back clears it before leaving an album.
    BackHandler(enabled = dest != null && !state.isSelectionMode) { GalleryNav.back() }

    // Which way the last change went, worked out once per change. The transition asks twice, for the
    // place coming in and the one leaving, so working it out there sent every leaving place a quarter
    // to the left, where Back should slide it off to the right.
    val previousDepth = remember { intArrayOf(0) }
    val deeper = remember { booleanArrayOf(true) }
    if (GalleryNav.depth != previousDepth[0]) {
        deeper[0] = GalleryNav.depth > previousDepth[0]
        previousDepth[0] = GalleryNav.depth
    }
    // Each place keeps its scroll position and search while something else is on top of it.
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = dest,
            transitionSpec = {
                when {
                    reduced -> fadeIn(KobbloMotion.fadeIn()) togetherWith fadeOut(KobbloMotion.fadeOut())
                    deeper[0] -> slideInHorizontally(KobbloMotion.smooth()) { it } togetherWith
                        slideOutHorizontally(KobbloMotion.smooth()) { -it / 4 }
                    // The place leaving stays on top as it slides off, uncovering the one under it.
                    else -> (slideInHorizontally(KobbloMotion.smooth()) { -it / 4 } togetherWith
                        slideOutHorizontally(KobbloMotion.smooth()) { it }).apply { targetContentZIndex = -1f }
                }
            },
            label = "gallery_nav",
            modifier = Modifier.fillMaxSize()
        ) { target ->
            Box(modifier = Modifier.fillMaxSize().background(com.kobblo.gallery.file.explorer.ui.design.KobbloDesign.colors.canvas)) {
                when (target) {
                    null -> HomeFeed(
                        state = state,
                        isCurrent = dest == null,
                        columns = columns,
                        onColumnsChange = store::setColumns,
                        pager = pager,
                        feeds = feeds,
                        largeTitle = largeTitle,
                        gridStates = gridStates,
                        onAction = onAction,
                        onOpen = onFileClick,
                        onMenu = onFileMenuClick,
                        bottomPadding = bottomPadding
                    )

                    GalleryDest.Albums -> saved.SaveableStateProvider("albums") {
                        AlbumsScreen(
                            state = state,
                            onAction = onAction,
                            bottomPadding = bottomPadding
                        )
                    }

                    GalleryDest.Search -> com.kobblo.gallery.file.explorer.gallery.search.GallerySearchScreen(
                        isCurrent = target == dest,
                        state = state,
                        columns = columns,
                        labels = labels,
                        onColumnsChange = store::setColumns,
                        onAction = onAction,
                        onFileClick = onFileClick,
                        onFileMenuClick = onFileMenuClick,
                        bottomPadding = bottomPadding
                    )

                    GalleryDest.Places -> saved.SaveableStateProvider("places") {
                        com.kobblo.gallery.file.explorer.gallery.places.PlacesScreen(state, bottomPadding)
                    }

                    GalleryDest.Tags -> saved.SaveableStateProvider("tags") {
                        com.kobblo.gallery.file.explorer.gallery.places.TagsScreen(state, bottomPadding)
                    }

                    GalleryDest.People -> saved.SaveableStateProvider("people") {
                        com.kobblo.gallery.file.explorer.smart.PeopleScreen(bottomPadding)
                    }

                    else -> AlbumGridScreen(
                        dest = target,
                        isCurrent = target == dest,
                        state = state,
                        columns = columns,
                        labels = labels,
                        onColumnsChange = store::setColumns,
                        onAction = onAction,
                        onFileClick = onFileClick,
                        onFileMenuClick = onFileMenuClick,
                        bottomPadding = bottomPadding
                    )
                }
            }
        }

        val transfer by AlbumTransfer.progress.collectAsState()
        AnimatedVisibility(
            visible = transfer != null,
            enter = fadeIn(KobbloMotion.fadeIn()),
            exit = fadeOut(KobbloMotion.fadeOut()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomPadding + 8.dp)
        ) {
            transfer?.let { TransferPill(it) }
        }

        val bulk by com.kobblo.gallery.file.explorer.gallery.BulkWork.progress.collectAsState()
        AnimatedVisibility(
            visible = bulk != null,
            enter = fadeIn(KobbloMotion.fadeIn()),
            exit = fadeOut(KobbloMotion.fadeOut()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomPadding + 8.dp)
        ) {
            bulk?.let { work ->
                com.kobblo.gallery.file.explorer.gallery.WorkPill(work.title, work.done, work.total) {
                    com.kobblo.gallery.file.explorer.gallery.BulkWork.cancel()
                }
            }
        }
    }

    if (com.kobblo.gallery.file.explorer.gallery.GalleryScope.showBulkActions) {
        val selected = remember(state.selectedFiles, state.galleryFiles) {
            state.galleryFiles.filter { it.id in state.selectedFiles }
        }
        com.kobblo.gallery.file.explorer.gallery.BulkActionsSheet(
            selected = selected,
            onAction = onAction,
            onDismiss = { com.kobblo.gallery.file.explorer.gallery.GalleryScope.showBulkActions = false }
        )
    }

    if (GalleryScope.pickAlbum) {
        val selected = remember(state.selectedFiles, state.galleryFiles) {
            state.galleryFiles.filter { it.id in state.selectedFiles }
        }
        AlbumPickerSheet(
            files = state.galleryFiles,
            initiallyMoving = GalleryScope.pickMoves,
            onPick = { folder, move ->
                GalleryScope.pickAlbum = false
                onAction(AppAction.ClearSelection)
                AlbumTransfer.start(context, selected, folder, move) { done, failed ->
                    val message = if (failed > 0) {
                        context.resources.getQuantityString(R.plurals.gallery_transfer_failed, failed, failed)
                    } else {
                        context.resources.getQuantityString(
                            if (move) R.plurals.gallery_moved_to else R.plurals.gallery_copied_to,
                            done,
                            done,
                            folder.name
                        )
                    }
                    onAction(AppAction.ShowToast(message))
                    onAction(AppAction.Reload)
                }
            },
            onFailed = { message ->
                GalleryScope.pickAlbum = false
                onAction(AppAction.ShowToast(message))
            },
            onDismiss = { GalleryScope.pickAlbum = false }
        )
    }
}

@Composable
private fun HomeFeed(
    state: AppState,
    isCurrent: Boolean,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    pager: PagerState,
    feeds: HomeFeeds,
    largeTitle: LargeTitleState,
    gridStates: List<LazyGridState>,
    onAction: (AppAction) -> Unit,
    onOpen: (FileItem) -> Unit,
    onMenu: (FileItem) -> Unit,
    bottomPadding: Dp
) {
    val reduced = ExpressiveMotion.isReducedMotion()
    val context = LocalContext.current
    val hidden by remember { GalleryStore.get(context) }.hiddenAlbums.collectAsState()

    val settled = pager.settledPage
    LaunchedEffect(state.galleryFiles, hidden, settled) {
        feeds.keepUp(state.galleryFiles, hidden, settled)
    }

    val inView = feeds[settled]
    LaunchedEffect(inView, isCurrent) {
        if (isCurrent && inView != null) GalleryScope.setVisible(inView.files)
    }

    // The tabs fold away with the title, and a swipe can still change the page. They come back to show where it landed.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).collect {
            if (reduced) largeTitle.reset() else largeTitle.open(KobbloMotion.smooth())
        }
    }

    // Tapping the tab you are already on goes back to the newest photos.
    val homeTaps = com.kobblo.gallery.file.explorer.gallery.GalleryNav.homeTaps
    val seenTaps = remember { intArrayOf(homeTaps) }
    LaunchedEffect(homeTaps) {
        if (homeTaps != seenTaps[0]) {
            seenTaps[0] = homeTaps
            largeTitle.reset()
            gridStates.getOrNull(pager.currentPage)?.animateScrollToItem(0)
        }
    }

    Column(modifier = Modifier.fillMaxSize().nestedScroll(largeTitle.connection)) {
        ExpressiveScreenHeader(
            title = stringResource(R.string.nav_home),
            modifier = Modifier.padding(horizontal = KobbloSpace.screen),
            // The same on every page, Audio too, so the bar doesn't change under the owner's thumb as the pages turn.
            rightAction = {
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    KobbloIconButton(
                        icon = AppIcons.Search,
                        description = stringResource(R.string.gallery_search),
                        onClick = { GalleryNav.open(GalleryDest.Search) }
                    )
                    KobbloIconButton(
                        icon = AppIcons.Albums,
                        description = stringResource(R.string.gallery_albums),
                        onClick = { GalleryNav.open(GalleryDest.Albums) }
                    )
                }
            },
            largeTitle = largeTitle,
            belowTitle = { PageTabs(pager, gridStates) }
        )

        // The same pages as the tabs above. While selecting, a sideways drag selects instead of turning the page.
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = KobbloSpace.screen,
            userScrollEnabled = !state.isSelectionMode,
            key = { it }
        ) { page ->
            val feed = feeds[page]
            when {
                page == AudioPage -> com.kobblo.gallery.file.explorer.music.AudioLibraryContent(
                    state = state,
                    onAction = onAction,
                    bottomPadding = bottomPadding,
                    modifier = Modifier.fillMaxSize()
                )

                !state.hasStoragePermission && state.galleryFiles.isEmpty() -> StorageAccessMessage(onAction, icon = AppIcons.Photo)

                feed == null || (feed.files.isEmpty() && state.isLoading && state.galleryFiles.isEmpty()) -> Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    com.kobblo.gallery.file.explorer.ui.ExpressiveCircularLoader(size = 40.dp, color = com.kobblo.gallery.file.explorer.ui.design.KobbloDesign.colors.action)
                }

                feed.files.isEmpty() -> KobbloMessage(
                    icon = AppIcons.Photo,
                    title = stringResource(R.string.gallery_empty)
                )

                else -> GalleryGrid(
                    feed = feed,
                    gridState = gridStates[page],
                    columns = columns,
                    onColumnsChange = onColumnsChange,
                    selected = state.selectedFiles,
                    selecting = state.isSelectionMode,
                    onFileClick = onOpen,
                    onFileMenuClick = onMenu,
                    onSelectionChange = { onAction(AppAction.SelectAll(it)) },
                    bottomPadding = bottomPadding
                )
            }
        }
    }
}

/**
 * The pages by name. Tapping the one in view goes back to its top. The page next door slides over,
 * and one further away is jumped to, rather than laying out every page between.
 */
@Composable
private fun PageTabs(pager: PagerState, gridStates: List<LazyGridState>) {
    val scope = rememberCoroutineScope()
    val reduced = ExpressiveMotion.isReducedMotion()
    KobbloSegmentedControl(
        options = listOf(
            stringResource(R.string.gallery_all),
            stringResource(R.string.gallery_photos),
            stringResource(R.string.gallery_videos),
            stringResource(R.string.gallery_audio)
        ),
        selectedIndex = pager.currentPage,
        onSelect = { index ->
            scope.launch {
                val distance = abs(index - pager.currentPage)
                when {
                    distance == 0 -> gridStates.getOrNull(index)?.animateScrollToItem(0)
                    reduced || distance > 1 -> pager.scrollToPage(index)
                    else -> pager.animateScrollToPage(index, animationSpec = KobbloMotion.smooth())
                }
            }
        },
        modifier = Modifier.padding(top = 12.dp)
    )
}

/**
 * The groups behind each photo page. Grouping a large library takes real time, so it never happens on the
 * main thread, and they're kept so that a swipe finds the next page already laid out.
 */
@Stable
private class HomeFeeds(private val labels: SectionLabels) {
    private class Source(val files: List<FileItem>, val hidden: Set<String>)

    private val ready = mutableStateMapOf<Int, GalleryFeed>()
    private val builtFrom = arrayOfNulls<Source>(PhotoPageCount)

    operator fun get(page: Int): GalleryFeed? = ready[page]

    /** The page in view comes first. The rest wait a moment, so they never hold up the app starting. */
    suspend fun keepUp(files: List<FileItem>, hidden: Set<String>, inView: Int) {
        val nearest = inView.coerceAtMost(PhotoPageCount - 1)
        build(nearest, files, hidden)
        delay(OtherPagesDelayMillis)
        for (page in (0 until PhotoPageCount).sortedBy { abs(it - nearest) }) build(page, files, hidden)
    }

    private suspend fun build(page: Int, files: List<FileItem>, hidden: Set<String>) {
        val from = builtFrom[page]
        if (from != null && from.files === files && from.hidden === hidden) return
        ready[page] = withContext(Dispatchers.Default) {
            val hiddenPaths = com.kobblo.gallery.file.explorer.fileops.LockedPaths(hidden)
            val visible = if (hidden.isEmpty()) files else files.filterNot { hiddenPaths.covers(it.path) }
            val shown = when (page) {
                1 -> visible.filter { it.type == "image" }
                2 -> visible.filter { it.type == "video" }
                else -> visible
            }
            buildGalleryFeed(shown, labels)
        }
        builtFrom[page] = Source(files, hidden)
    }
}
