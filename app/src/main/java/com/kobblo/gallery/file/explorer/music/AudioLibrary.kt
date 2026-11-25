package com.kobblo.gallery.file.explorer.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.gallery.GalleryNav
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.ExpressiveCircularLoader
import com.kobblo.gallery.file.explorer.ui.FilePickerDialog
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.design.quietCombinedClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSheetContent
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSearchField
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.icons.FolderIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AlbumCoverSize = 180.dp
private const val LetterIndexMinSongs = 30

/** Items above the first song in the Songs view: the search field and the big buttons. */
private const val SongsHeaderItems = 2

private enum class LibraryView(val label: Int) {
    Songs(R.string.music_songs),
    Albums(R.string.music_albums),
    Artists(R.string.music_artists),
    Folders(R.string.music_folders),
    Playlists(R.string.music_playlists)
}

/**
 * The Audio filter of the Home tab: the device's music, ready the moment it opens. Songs come first,
 * with the view switcher above them. [bottomPadding] clears the tab bar and mini player.
 */
@Composable
fun AudioLibraryContent(
    state: AppState,
    onAction: (AppAction) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val snapshot by remember { MusicLibrary.get(context) }.snapshot.collectAsState()
    var view by rememberSaveable { mutableStateOf(LibraryView.Songs) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val reduced = ExpressiveMotion.isReducedMotion()

    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    Column(modifier = modifier) {
        when {
            !state.hasStoragePermission && snapshot.tracks.isEmpty() -> KobbloMessage(
                icon = AppIcons.Music,
                title = stringResource(R.string.permission_dialog_title),
                body = stringResource(R.string.permission_dialog_message),
                action = {
                    Button(
                        onClick = { onAction(AppAction.RequestStorageAccess) },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.action, contentColor = colors.onAction)
                    ) {
                        Text(stringResource(R.string.permission_grant_access), fontWeight = FontWeight.SemiBold)
                    }
                }
            )

            !snapshot.isLoaded -> Box(
                modifier = Modifier.fillMaxWidth().padding(48.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                ExpressiveCircularLoader(size = 40.dp, color = colors.action)
            }

            searching -> SearchMode(
                query = query,
                onQueryChange = { query = it },
                onCancel = {
                    searching = false
                    query = ""
                },
                snapshot = snapshot,
                onAction = onAction,
                bottomPadding = bottomPadding
            )

            else -> {
                ViewSwitcher(selected = view, onSelect = { view = it })
                Crossfade(
                    targetState = view,
                    animationSpec = if (reduced) snap() else KobbloMotion.fadeIn(),
                    label = "library_view",
                    modifier = Modifier.fillMaxSize()
                ) { target ->
                    val searchField: @Composable () -> Unit = {
                        SearchRow(onSearch = { searching = true }, showSort = target == LibraryView.Songs)
                    }
                    when (target) {
                        LibraryView.Songs -> SongsView(snapshot, onAction, bottomPadding, searchField)
                        LibraryView.Albums -> AlbumsView(snapshot, bottomPadding, searchField)
                        LibraryView.Artists -> ArtistsView(snapshot, bottomPadding, searchField)
                        LibraryView.Folders -> FoldersView(snapshot, bottomPadding, searchField)
                        LibraryView.Playlists -> PlaylistsView(snapshot, onAction, bottomPadding, searchField)
                    }
                }
            }
        }
    }
}

/** Runs [scroll] when the Home tab is tapped while already on it, but not for taps from before. */
@Composable
private fun ScrollToTopOnHomeTap(scroll: suspend () -> Unit) {
    val taps = GalleryNav.homeTaps
    val seen = remember { intArrayOf(taps) }
    LaunchedEffect(taps) {
        if (taps != seen[0]) {
            seen[0] = taps
            scroll()
        }
    }
}

/** Stays above the list while it scrolls, so changing view never means scrolling back up first. */
@Composable
private fun ViewSwitcher(selected: LibraryView, onSelect: (LibraryView) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = KobbloSpace.screen, vertical = KobbloSpace.xs),
        horizontalArrangement = MusicChipSpacing
    ) {
        items(LibraryView.entries.size, key = { LibraryView.entries[it].name }) { index ->
            val option = LibraryView.entries[index]
            MusicChip(
                label = stringResource(option.label),
                selected = option == selected,
                onClick = { onSelect(option) }
            )
        }
    }
}

/** Looks like a search field. Tapping it opens the real one, with the keyboard, in search mode. */
@Composable
private fun SearchRow(onSearch: () -> Unit, showSort: Boolean) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val store = remember { MusicStore.get(context) }
    val data by store.data.collectAsState()
    var showSortMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(CapsuleShape)
                .background(colors.mist)
                .quietClickable(onClick = onSearch)
                .padding(start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(AppIcons.Search, contentDescription = null, tint = colors.smoke, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.music_search_hint),
                style = KobbloType.body,
                color = colors.smoke,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        KobbloIconButton(
            icon = AppIcons.Star,
            description = stringResource(R.string.music_favorites),
            onClick = { Hub.open(MusicRoute.Smart(SmartList.Favorites)) },
            size = 40.dp
        )
        if (showSort) {
            Spacer(Modifier.width(8.dp))
            KobbloIconButton(
                icon = AppIcons.Sort,
                description = stringResource(R.string.music_sort),
                onClick = { showSortMenu = true },
                size = 40.dp
            )
        }
    }
    if (showSortMenu) {
        KobbloSheet(onDismissRequest = { showSortMenu = false }) {
            ExpressiveSheetContent(title = stringResource(R.string.music_sort)) {
                KobbloGroup {
                    SongSort.entries.forEachIndexed { index, option ->
                        KobbloRow(
                            title = stringResource(
                                when (option) {
                                    SongSort.Title -> R.string.music_sort_title
                                    SongSort.Artist -> R.string.music_sort_artist
                                    SongSort.Album -> R.string.music_sort_album
                                    SongSort.RecentlyAdded -> R.string.music_sort_added
                                }
                            ),
                            trailing = {
                                if (data.songSort == option) {
                                    Icon(
                                        AppIcons.Check,
                                        contentDescription = stringResource(R.string.polish_current_sort),
                                        tint = colors.action,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            showDivider = index < SongSort.entries.lastIndex,
                            onClick = {
                                showSortMenu = false
                                store.update { it.copy(songSort = option) }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── Songs ─────────────────────────────────────────────────────────────────────

@Composable
private fun SongsView(
    snapshot: MusicSnapshot,
    onAction: (AppAction) -> Unit,
    bottomPadding: Dp,
    searchField: @Composable () -> Unit
) {
    val context = LocalContext.current
    val player = remember { MusicPlayer.get(context) }
    val playback by player.state.collectAsState()
    val store = remember { MusicStore.get(context) }
    val sort by produceState(store.data.value.songSort, store) {
        store.data.collect { value = it.songSort }
    }
    // The library is held by title already. Any other order is worked out on the side, with the
    // list kept as it was meanwhile, so there's no flash of an empty library.
    val sorted by produceState<List<MusicTrack>?>(if (sort == SongSort.Title) snapshot.tracks else null, snapshot, sort) {
        value = if (sort == SongSort.Title) {
            snapshot.tracks
        } else {
            withContext(Dispatchers.Default) { sortSongs(snapshot.tracks, sort) }
        }
    }
    val songs = sorted ?: snapshot.tracks
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    ScrollToTopOnHomeTap { list.animateScrollToItem(0) }
    val currentPath = playback.current?.path

    // Only worth having once there's enough to get lost in, and only in the order it describes.
    val letters by produceState(emptyList<Pair<Char, Int>>(), songs, sort) {
        value = if (sort == SongSort.Title && songs.size >= LetterIndexMinSongs) {
            withContext(Dispatchers.Default) { letterIndex(songs) }
        } else {
            emptyList()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding)
        ) {
            item(key = "search", contentType = "search") {
                Box(modifier = Modifier.padding(horizontal = KobbloSpace.screen)) { searchField() }
            }
            if (songs.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    KobbloMessage(
                        icon = AppIcons.Music,
                        title = stringResource(R.string.music_empty_title),
                        body = stringResource(R.string.music_empty_body)
                    )
                }
                return@LazyColumn
            }
            item(key = "buttons", contentType = "buttons") {
                PlayShuffleButtons(
                    onPlay = { player.play(songs, shuffle = false) },
                    onShuffle = { player.shuffleAll(songs) },
                    modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)
                )
            }
            itemsIndexed(songs, key = { _, track -> track.path }, contentType = { _, _ -> "song" }) { index, track ->
                SongRow(
                    track = track,
                    index = index,
                    count = songs.size,
                    isCurrent = track.path == currentPath,
                    isPlaying = playback.isPlaying,
                    onClick = { tapSong(player, onAction, songs, index, currentPath) },
                    onLongClick = { MusicUi.songMenu = SongMenuRequest(track) }
                )
            }
            item(key = "footer", contentType = "footer") {
                Text(
                    text = songsSummary(songs),
                    style = KobbloType.footnote,
                    color = KobbloDesign.colors.smoke,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)
                )
            }
        }
        if (letters.isNotEmpty()) {
            LetterStrip(
                letters = letters,
                onLetter = { songIndex ->
                    scope.launch { list.scrollToItem(songIndex + SongsHeaderItems) }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(bottom = bottomPadding)
            )
        }
    }
}

/**
 * The letters down the side of a long list, to tap or run a finger along. Quiet at rest, since most
 * of the time it isn't what anyone is looking at.
 */
@Composable
private fun LetterStrip(
    letters: List<Pair<Char, Int>>,
    onLetter: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val latestLetters by rememberUpdatedState(letters)
    val latestOnLetter by rememberUpdatedState(onLetter)
    var lastPicked by remember { mutableIntStateOf(-1) }
    val description = stringResource(R.string.music_letter_index)
    Column(
        modifier = modifier
            .width(26.dp)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                fun pick(y: Float) {
                    val count = latestLetters.size
                    if (count == 0 || size.height == 0) return
                    val slot = (y / size.height * count).toInt().coerceIn(0, count - 1)
                    if (slot != lastPicked) {
                        lastPicked = slot
                        haptics.tap()
                        latestOnLetter(latestLetters[slot].second)
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    lastPicked = -1
                    pick(down.position.y)
                    drag(down.id) { change ->
                        change.consume()
                        pick(change.position.y)
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        letters.forEach { (letter, _) ->
            Text(
                text = letter.toString(),
                style = KobbloType.tab.copy(fontSize = 10.sp, lineHeight = 13.sp),
                color = colors.action
            )
        }
    }
}

/** Tapping the song that's already on opens the player, rather than starting it over. */
fun tapSong(
    player: MusicPlayer,
    onAction: (AppAction) -> Unit,
    songs: List<MusicTrack>,
    index: Int,
    currentPath: String?
) {
    val track = songs.getOrNull(index) ?: return
    if (track.path == currentPath) {
        onAction(AppAction.SetShowFullScreenPlayer(true))
    } else {
        player.play(songs, startIndex = index)
    }
}

// ── Albums ────────────────────────────────────────────────────────────────────

@Composable
private fun AlbumsView(snapshot: MusicSnapshot, bottomPadding: Dp, searchField: @Composable () -> Unit) {
    val grid = rememberLazyGridState()
    ScrollToTopOnHomeTap { grid.animateScrollToItem(0) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = KobbloSpace.screen, end = KobbloSpace.screen, bottom = bottomPadding),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
    ) {
        item(key = "search", span = { GridItemSpan(maxLineSpan) }, contentType = "search") { searchField() }
        items(snapshot.albums, key = { it.key }, contentType = { "album" }) { album ->
            AlbumTile(album = album)
        }
        item(key = "footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
            Text(
                text = pluralStringResource(R.plurals.music_album_count, snapshot.albums.size, snapshot.albums.size),
                style = KobbloType.footnote,
                color = KobbloDesign.colors.smoke,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)
            )
        }
    }
}

@Composable
fun AlbumTile(album: MusicAlbum, modifier: Modifier = Modifier, backLabel: String? = null) {
    val colors = KobbloDesign.colors
    Column(
        modifier = modifier.quietCombinedClickable(
            onLongClick = { MusicUi.collectionMenu = CollectionMenuRequest(album.title, album.tracks) },
            onClick = { Hub.open(MusicRoute.Album(album.key, backLabel)) }
        )
    ) {
        // Asks for a fixed size of image and fills the tile with it. Measuring each tile first to pick
        // the exact size would cost more while scrolling than the sharper pixels are worth.
        MusicCover(
            track = album.tracks.firstOrNull(),
            size = AlbumCoverSize,
            shape = KobbloShapes.md,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = album.title,
            style = KobbloType.callout.copy(fontWeight = FontWeight.Medium),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val artist = album.artistLine()
        if (artist.isNotEmpty()) {
            Text(
                text = artist,
                style = KobbloType.footnote,
                color = colors.smoke,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Artists ───────────────────────────────────────────────────────────────────

@Composable
private fun ArtistsView(snapshot: MusicSnapshot, bottomPadding: Dp, searchField: @Composable () -> Unit) {
    val list = rememberLazyListState()
    ScrollToTopOnHomeTap { list.animateScrollToItem(0) }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        item(key = "search", contentType = "search") {
            Column(modifier = Modifier.padding(horizontal = KobbloSpace.screen)) {
                searchField()
                Spacer(Modifier.height(12.dp))
            }
        }
        itemsIndexed(snapshot.artists, key = { _, artist -> artist.key }, contentType = { _, _ -> "artist" }) { index, artist ->
            ArtistRow(artist = artist, index = index, count = snapshot.artists.size)
        }
    }
}

@Composable
private fun ArtistRow(artist: MusicArtist, index: Int, count: Int) {
    val name = artistLabel(artist.name)
    NavigationRow(
        title = name,
        subtitle = pluralStringResource(R.plurals.music_song_count, artist.tracks.size, artist.tracks.size),
        index = index,
        count = count,
        leading = { MusicCover(track = artist.tracks.firstOrNull(), size = CoverRowCoverSize, shape = CapsuleShape) },
        onLongClick = { MusicUi.collectionMenu = CollectionMenuRequest(name, artist.tracks) },
        onClick = { Hub.open(MusicRoute.Artist(artist.key)) }
    )
}

// ── Folders ───────────────────────────────────────────────────────────────────

@Composable
private fun FoldersView(snapshot: MusicSnapshot, bottomPadding: Dp, searchField: @Composable () -> Unit) {
    val list = rememberLazyListState()
    ScrollToTopOnHomeTap { list.animateScrollToItem(0) }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        item(key = "search", contentType = "search") {
            Column(modifier = Modifier.padding(horizontal = KobbloSpace.screen)) {
                searchField()
                Spacer(Modifier.height(12.dp))
            }
        }
        itemsIndexed(snapshot.folders, key = { _, folder -> folder.path }, contentType = { _, _ -> "folder" }) { index, folder ->
            NavigationRow(
                title = folder.name,
                subtitle = pluralStringResource(R.plurals.music_song_count, folder.tracks.size, folder.tracks.size),
                index = index,
                count = snapshot.folders.size,
                leading = { FolderIcon(modifier = Modifier.size(CoverRowCoverSize)) },
                onLongClick = { MusicUi.collectionMenu = CollectionMenuRequest(folder.name, folder.tracks) },
                onClick = { Hub.open(MusicRoute.Folder(folder.path)) }
            )
        }
    }
}

// ── Playlists ─────────────────────────────────────────────────────────────────

@Composable
private fun PlaylistsView(
    snapshot: MusicSnapshot,
    onAction: (AppAction) -> Unit,
    bottomPadding: Dp,
    searchField: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val store = remember { MusicStore.get(context) }
    val data by store.data.collectAsState()
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    ScrollToTopOnHomeTap { list.animateScrollToItem(0) }
    var naming by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        item(key = "search", contentType = "search") {
            Column(modifier = Modifier.padding(horizontal = KobbloSpace.screen)) {
                searchField()
                Spacer(Modifier.height(12.dp))
            }
        }
        smartListRows(data, snapshot)
        item(key = "gap", contentType = "gap") { Spacer(Modifier.height(KobbloSpace.lg)) }
        val rows = data.playlists.size + 2
        item(key = "new", contentType = "row") {
            ActionRow(
                title = stringResource(R.string.music_new_playlist),
                icon = AppIcons.Add,
                index = 0,
                count = rows,
                onClick = { naming = true }
            )
        }
        item(key = "import", contentType = "row") {
            ActionRow(
                title = stringResource(R.string.music_import_m3u),
                icon = AppIcons.Download,
                index = 1,
                count = rows,
                onClick = { importing = true }
            )
        }
        itemsIndexed(data.playlists, key = { _, playlist -> playlist.id }, contentType = { _, _ -> "playlist" }) { index, playlist ->
            val available = snapshot.tracksFor(playlist.paths)
            NavigationRow(
                title = playlist.name,
                subtitle = pluralStringResource(R.plurals.music_song_count, available.size, available.size),
                index = index + 2,
                count = rows,
                leading = { MusicCover(track = available.firstOrNull(), size = CoverRowCoverSize, shape = KobbloShapes.tile) },
                onClick = { Hub.open(MusicRoute.PlaylistScreen(playlist.id)) }
            )
        }
    }

    if (naming) {
        PlaylistNameDialog(
            title = stringResource(R.string.music_new_playlist),
            confirm = stringResource(R.string.music_create),
            initial = "",
            onDismiss = { naming = false },
            onConfirm = { name ->
                naming = false
                store.update {
                    it.withNewPlaylist(java.util.UUID.randomUUID().toString(), name, emptyList(), System.currentTimeMillis())
                }
            }
        )
    }
    if (importing) {
        FilePickerDialog(
            title = stringResource(R.string.music_import_m3u),
            initialPath = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC).path,
            onDismiss = { importing = false },
            onFilesSelected = { files ->
                importing = false
                scope.launch {
                    files.forEach { file ->
                        val result = PlaylistFiles.import(store, snapshot, file.path)
                        onAction(
                            AppAction.ShowToast(
                                if (result != null) {
                                    context.getString(R.string.music_imported, result.name, result.matched, result.total)
                                } else {
                                    context.getString(R.string.music_import_failed)
                                }
                            )
                        )
                    }
                }
            }
        )
    }
}

/** The lists the library keeps by itself. */
private fun LazyListScope.smartListRows(data: MusicData, snapshot: MusicSnapshot) {
    val rows = listOf(SmartList.Favorites, SmartList.RecentlyPlayed, SmartList.MostPlayed)
    itemsIndexed(rows, key = { _, list -> "smart_${list.name}" }, contentType = { _, _ -> "smart" }) { index, list ->
        val songs = when (list) {
            SmartList.Favorites -> snapshot.tracksFor(data.favorites)
            SmartList.RecentlyPlayed -> snapshot.tracksFor(data.recent)
            SmartList.MostPlayed -> snapshot.tracksFor(data.mostPlayed())
        }
        NavigationRow(
            title = stringResource(list.title),
            subtitle = pluralStringResource(R.plurals.music_song_count, songs.size, songs.size),
            index = index,
            count = rows.size,
            leading = { KobbloIconBadge(icon = list.icon, color = list.tint, size = CoverRowCoverSize) },
            onClick = { Hub.open(MusicRoute.Smart(list)) }
        )
    }
}

@Composable
private fun ActionRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, index: Int, count: Int, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    MusicRow(
        title = title,
        subtitle = null,
        index = index,
        count = count,
        isCurrent = true,
        leading = {
            Box(modifier = Modifier.size(CoverRowCoverSize), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.action, modifier = Modifier.size(24.dp))
            }
        },
        onClick = onClick
    )
}

// ── Search ────────────────────────────────────────────────────────────────────

private data class SearchHits(
    val artists: List<MusicArtist> = emptyList(),
    val albums: List<MusicAlbum> = emptyList(),
    val songs: List<MusicTrack> = emptyList()
)

@Composable
private fun SearchMode(
    query: String,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
    snapshot: MusicSnapshot,
    onAction: (AppAction) -> Unit,
    bottomPadding: Dp
) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val player = remember { MusicPlayer.get(context) }
    val playback by player.state.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val hits by produceState(SearchHits(), query, snapshot) {
        if (query.isBlank()) {
            value = SearchHits()
            return@produceState
        }
        // A beat between keystrokes, so a whole word only searches once.
        delay(120)
        value = withContext(Dispatchers.Default) {
            val words = queryWords(query)
            SearchHits(
                artists = snapshot.artists
                    .filter { it.name.isNotBlank() && words.all { word -> word in sortKey(it.name) } }
                    .take(3),
                albums = snapshot.albums
                    .filter { album -> words.all { word -> word in sortKey(album.title) } }
                    .take(3),
                songs = snapshot.tracks.filter { it.matchesQuery(words) }
            )
        }
    }
    val currentPath = playback.current?.path
    val list = rememberLazyListState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = KobbloSpace.screen, end = KobbloSpace.xs, top = KobbloSpace.xs, bottom = KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KobbloSearchField(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.music_search_hint),
            modifier = Modifier.weight(1f).focusRequester(focus)
        )
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.music_cancel), style = KobbloType.body, color = colors.action)
        }
    }

    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        if (query.isNotBlank() && hits.artists.isEmpty() && hits.albums.isEmpty() && hits.songs.isEmpty()) {
            item(key = "none", contentType = "none") {
                KobbloMessage(icon = AppIcons.Search, title = stringResource(R.string.music_no_results))
            }
        }
        if (hits.artists.isNotEmpty()) {
            item(key = "artists_header", contentType = "header") { SectionTitle(stringResource(R.string.music_artists)) }
            itemsIndexed(hits.artists, key = { _, artist -> "a_${artist.key}" }, contentType = { _, _ -> "artist" }) { index, artist ->
                ArtistRow(artist = artist, index = index, count = hits.artists.size)
            }
        }
        if (hits.albums.isNotEmpty()) {
            item(key = "albums_header", contentType = "header") { SectionTitle(stringResource(R.string.music_albums)) }
            itemsIndexed(hits.albums, key = { _, album -> "b_${album.key}" }, contentType = { _, _ -> "albumRow" }) { index, album ->
                NavigationRow(
                    title = album.title,
                    subtitle = album.artistLine().ifEmpty { null },
                    index = index,
                    count = hits.albums.size,
                    leading = { MusicCover(track = album.tracks.firstOrNull(), size = CoverRowCoverSize, shape = KobbloShapes.tile) },
                    onClick = { Hub.open(MusicRoute.Album(album.key)) }
                )
            }
        }
        if (hits.songs.isNotEmpty()) {
            item(key = "songs_header", contentType = "header") { SectionTitle(stringResource(R.string.music_songs)) }
            itemsIndexed(hits.songs, key = { _, track -> track.path }, contentType = { _, _ -> "song" }) { index, track ->
                SongRow(
                    track = track,
                    index = index,
                    count = hits.songs.size,
                    isCurrent = track.path == currentPath,
                    isPlaying = playback.isPlaying,
                    onClick = { tapSong(player, onAction, hits.songs, index, currentPath) },
                    onLongClick = { MusicUi.songMenu = SongMenuRequest(track) }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = KobbloType.title,
        color = KobbloDesign.colors.ink,
        modifier = Modifier.padding(start = KobbloSpace.screen, end = KobbloSpace.screen, top = KobbloSpace.section, bottom = KobbloSpace.titleGap)
    )
}
