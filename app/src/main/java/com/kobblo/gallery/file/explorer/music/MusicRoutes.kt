package com.kobblo.gallery.file.explorer.music

import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.MiniMusicPlayer
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.hub.HubRoute
import com.kobblo.gallery.file.explorer.ui.hub.HubScreen
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlassSource
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch

/** The lists the library keeps by itself. */
enum class SmartList(val title: Int, val icon: ImageVector, val tint: Color, val empty: Int) {
    Favorites(R.string.music_favorites, AppIcons.StarFill, KobbloTint.Yellow, R.string.music_favorites_empty),
    RecentlyPlayed(R.string.music_recently_played, AppIcons.Clock, KobbloTint.Blue, R.string.music_recent_empty),
    MostPlayed(R.string.music_most_played, AppIcons.ChartBars, KobbloTint.Pink, R.string.music_most_played_empty)
}

/** The full screens of this area. Open one with `Hub.open(...)`. */
sealed interface MusicRoute : HubRoute {
    data class Album(val key: String, val backLabel: String? = null) : MusicRoute
    data class Artist(val key: String) : MusicRoute
    data class Folder(val path: String) : MusicRoute
    data class PlaylistScreen(val id: String) : MusicRoute
    data class Smart(val list: SmartList) : MusicRoute
}

@Composable
fun MusicRouteContent(route: MusicRoute, state: AppState, onAction: (AppAction) -> Unit) {
    when (route) {
        is MusicRoute.Album -> AlbumScreen(route, state, onAction)
        is MusicRoute.Artist -> ArtistScreen(route, state, onAction)
        is MusicRoute.Folder -> FolderScreen(route, state, onAction)
        is MusicRoute.PlaylistScreen -> PlaylistDetailScreen(route, state, onAction)
        is MusicRoute.Smart -> SmartListScreen(route, state, onAction)
    }
}

/**
 * The frame of a pushed music screen. These cover the tab bar, and the mini player with it, so the
 * screen brings its own: starting a song here has to show something happened.
 */
@Composable
private fun MusicScreen(
    title: String,
    state: AppState,
    onAction: (AppAction) -> Unit,
    backLabel: String = stringResource(R.string.music_back_label_library),
    trailingIcon: ImageVector? = null,
    trailingDescription: String = "",
    onTrailingClick: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val canvas = KobbloDesign.colors.canvas
    val backdrop = rememberLayerBackdrop {
        drawRect(canvas)
        drawContent()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        HubScreen(
            title = title,
            backLabel = backLabel,
            trailingIcon = trailingIcon,
            trailingDescription = trailingDescription,
            onTrailingClick = onTrailingClick,
            modifier = Modifier.kobbloGlassSource(backdrop),
            content = content
        )
        val reduced = ExpressiveMotion.isReducedMotion()
        AnimatedVisibility(
            visible = state.currentAudioFile != null && !state.showFullScreenPlayer,
            enter = if (reduced) fadeIn() else slideInVertically { it } + fadeIn(),
            exit = if (reduced) fadeOut() else slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
        ) {
            MiniMusicPlayer(state = state, onAction = onAction, backdrop = backdrop)
        }
    }
}

/** The state of playback that every list needs in order to mark the song that's on. */
@Composable
private fun rememberPlayback(): Pair<MusicPlayer, MusicPlaybackState> {
    val context = LocalContext.current
    val player = remember { MusicPlayer.get(context) }
    val playback by player.state.collectAsState()
    return player to playback
}

@Composable
private fun rememberSnapshot(): MusicSnapshot {
    val context = LocalContext.current
    val snapshot by remember { MusicLibrary.get(context) }.snapshot.collectAsState()
    return snapshot
}

/** For a screen whose subject has vanished, say because its last song was just deleted. */
@Composable
private fun GoneScreen(state: AppState, onAction: (AppAction) -> Unit, title: String = "") {
    MusicScreen(title = title, state = state, onAction = onAction) {
        KobbloMessage(icon = AppIcons.Music, title = stringResource(R.string.music_no_results))
    }
}

// ── Album ─────────────────────────────────────────────────────────────────────

@Composable
private fun AlbumScreen(route: MusicRoute.Album, state: AppState, onAction: (AppAction) -> Unit) {
    val snapshot = rememberSnapshot()
    val (player, playback) = rememberPlayback()
    val album = snapshot.album(route.key)
    if (album == null) {
        GoneScreen(state, onAction)
        return
    }
    val currentPath = playback.current?.path
    val artist = snapshot.artists.firstOrNull { it.name.isNotBlank() && sortKey(it.name) == sortKey(album.artist) }
    MusicScreen(
        title = album.title,
        state = state,
        onAction = onAction,
        backLabel = route.backLabel ?: stringResource(R.string.music_back_label_library)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = PushedScreenBottomPadding)
        ) {
            item(key = "header", contentType = "header") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MusicCover(track = album.tracks.firstOrNull(), size = 220.dp, shape = KobbloShapes.lg)
                    Spacer(Modifier.height(12.dp))
                    val artistLine = album.artistLine()
                    if (artistLine.isNotEmpty()) {
                        Text(
                            text = artistLine,
                            style = KobbloType.body,
                            color = if (artist != null) KobbloDesign.colors.action else KobbloDesign.colors.smoke,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(horizontal = KobbloSpace.xl)
                                .then(
                                    if (artist != null) {
                                        Modifier.quietClickable { Hub.open(MusicRoute.Artist(artist.key)) }
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                    if (album.year > 0) {
                        Text(text = album.year.toString(), style = KobbloType.footnote, color = KobbloDesign.colors.smoke)
                    }
                    Spacer(Modifier.height(12.dp))
                    PlayShuffleButtons(
                        onPlay = { player.play(album.tracks, shuffle = false) },
                        onShuffle = { player.shuffleAll(album.tracks) },
                        shuffleLabel = stringResource(R.string.music_shuffle)
                    )
                }
            }
            itemsIndexed(album.tracks, key = { _, track -> track.path }, contentType = { _, _ -> "song" }) { index, track ->
                SongRow(
                    track = track,
                    index = index,
                    count = album.tracks.size,
                    isCurrent = track.path == currentPath,
                    isPlaying = playback.isPlaying,
                    leadingNumber = track.trackNumber,
                    subtitle = if (album.isVarious) track.artist else "",
                    onClick = { tapSong(player, onAction, album.tracks, index, currentPath) },
                    onLongClick = { MusicUi.songMenu = SongMenuRequest(track) }
                )
            }
            item(key = "footer", contentType = "footer") { KobbloGroupFooter(songsSummary(album.tracks)) }
        }
    }
}

// ── Artist ────────────────────────────────────────────────────────────────────

@Composable
private fun ArtistScreen(route: MusicRoute.Artist, state: AppState, onAction: (AppAction) -> Unit) {
    val snapshot = rememberSnapshot()
    val (player, playback) = rememberPlayback()
    val artist = snapshot.artist(route.key)
    if (artist == null) {
        GoneScreen(state, onAction)
        return
    }
    val currentPath = playback.current?.path
    val name = artistLabel(artist.name)
    MusicScreen(title = name, state = state, onAction = onAction) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = PushedScreenBottomPadding)
        ) {
            item(key = "header", contentType = "header") {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
                    Text(
                        text = pluralStringResource(R.plurals.music_album_count, artist.albums.size, artist.albums.size) +
                            " · " + songsSummary(artist.tracks),
                        style = KobbloType.footnote,
                        color = KobbloDesign.colors.smoke,
                        modifier = Modifier.padding(horizontal = KobbloSpace.content)
                    )
                    Spacer(Modifier.height(12.dp))
                    PlayShuffleButtons(
                        onPlay = { player.play(artist.tracks, shuffle = false) },
                        onShuffle = { player.shuffleAll(artist.tracks) },
                        shuffleLabel = stringResource(R.string.music_shuffle)
                    )
                }
            }
            if (artist.albums.isNotEmpty()) {
                item(key = "albums", contentType = "albums") {
                    Column {
                        Text(
                            text = stringResource(R.string.music_albums),
                            style = KobbloType.title,
                            color = KobbloDesign.colors.ink,
                            modifier = Modifier.padding(horizontal = KobbloSpace.screen, vertical = 8.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = KobbloSpace.screen),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(artist.albums, key = { it.key }) { album ->
                                AlbumTile(album = album, modifier = Modifier.width(140.dp), backLabel = name)
                            }
                        }
                    }
                }
            }
            item(key = "songs_title", contentType = "title") {
                Text(
                    text = stringResource(R.string.music_songs),
                    style = KobbloType.title,
                    color = KobbloDesign.colors.ink,
                    modifier = Modifier.padding(start = KobbloSpace.screen, end = KobbloSpace.screen, top = KobbloSpace.section, bottom = KobbloSpace.titleGap)
                )
            }
            itemsIndexed(artist.tracks, key = { _, track -> track.path }, contentType = { _, _ -> "song" }) { index, track ->
                SongRow(
                    track = track,
                    index = index,
                    count = artist.tracks.size,
                    isCurrent = track.path == currentPath,
                    isPlaying = playback.isPlaying,
                    subtitle = track.albumOrFolder,
                    onClick = { tapSong(player, onAction, artist.tracks, index, currentPath) },
                    onLongClick = { MusicUi.songMenu = SongMenuRequest(track) }
                )
            }
        }
    }
}

// ── Folder ────────────────────────────────────────────────────────────────────

@Composable
private fun FolderScreen(route: MusicRoute.Folder, state: AppState, onAction: (AppAction) -> Unit) {
    val snapshot = rememberSnapshot()
    val (player, playback) = rememberPlayback()
    val folder = snapshot.folder(route.path)
    if (folder == null) {
        GoneScreen(state, onAction)
        return
    }
    val currentPath = playback.current?.path
    MusicScreen(
        title = folder.name,
        state = state,
        onAction = onAction,
        trailingIcon = AppIcons.FolderOpen,
        trailingDescription = stringResource(R.string.music_show_in_folder),
        onTrailingClick = {
            Hub.close()
            onAction(AppAction.SetLocation(folder.path))
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = PushedScreenBottomPadding)
        ) {
            item(key = "buttons", contentType = "buttons") {
                PlayShuffleButtons(
                    onPlay = { player.play(folder.tracks, shuffle = false) },
                    onShuffle = { player.shuffleAll(folder.tracks) },
                    shuffleLabel = stringResource(R.string.music_shuffle),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
            }
            itemsIndexed(folder.tracks, key = { _, track -> track.path }, contentType = { _, _ -> "song" }) { index, track ->
                SongRow(
                    track = track,
                    index = index,
                    count = folder.tracks.size,
                    isCurrent = track.path == currentPath,
                    isPlaying = playback.isPlaying,
                    onClick = { tapSong(player, onAction, folder.tracks, index, currentPath) },
                    onLongClick = { MusicUi.songMenu = SongMenuRequest(track) }
                )
            }
            item(key = "footer", contentType = "footer") { KobbloGroupFooter(songsSummary(folder.tracks)) }
        }
    }
}

// ── Playlist ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistDetailScreen(route: MusicRoute.PlaylistScreen, state: AppState, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val snapshot = rememberSnapshot()
    val (player, playback) = rememberPlayback()
    val store = remember { MusicStore.get(context) }
    val data by store.data.collectAsState()
    val playlist = data.playlist(route.id)
    var showMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (playlist == null) {
        GoneScreen(state, onAction)
        return
    }
    // Reordering works on the stored list by position, so hold onto each song's position in it.
    val rows = remember(playlist.paths, snapshot) {
        playlist.paths.mapIndexedNotNull { position, path -> snapshot.byPath[path]?.let { position to it } }
    }
    val tracks = remember(rows) { rows.map { it.second } }
    val currentPath = playback.current?.path
    val list = rememberLazyListState()
    val reorder = rememberReorderState(
        list = list,
        canMoveOver = { (it.key as? String)?.startsWith(PlaylistSongKeyPrefix) == true },
        onMove = { from, to ->
            val fromPosition = rows.getOrNull(from - PlaylistHeaderItems)?.first
            val toPosition = rows.getOrNull(to - PlaylistHeaderItems)?.first
            if (fromPosition != null && toPosition != null) {
                store.update { it.withMovedInPlaylist(playlist.id, fromPosition, toPosition, System.currentTimeMillis()) }
            }
        }
    )

    MusicScreen(
        title = playlist.name,
        state = state,
        onAction = onAction,
        trailingIcon = if (editing) AppIcons.Check else AppIcons.MoreHoriz,
        trailingDescription = stringResource(if (editing) R.string.music_done else R.string.music_more),
        onTrailingClick = { if (editing) editing = false else showMenu = true }
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = PushedScreenBottomPadding)
        ) {
            item(key = "buttons", contentType = "buttons") {
                if (tracks.isEmpty()) {
                    KobbloMessage(
                        icon = AppIcons.Playlist,
                        title = playlist.name,
                        body = stringResource(R.string.music_playlist_empty)
                    )
                } else if (!editing) {
                    PlayShuffleButtons(
                        onPlay = { player.play(tracks, shuffle = false) },
                        onShuffle = { player.shuffleAll(tracks) },
                        shuffleLabel = stringResource(R.string.music_shuffle),
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                }
            }
            itemsIndexed(
                rows,
                // A key has to survive a move, so it's the song rather than its position. A list
                // holds a song only once. The prefix tells songs from the header and footer.
                key = { _, row -> PlaylistSongKeyPrefix + row.second.path },
                contentType = { _, _ -> "song" }
            ) { index, row ->
                val (position, track) = row
                val key = PlaylistSongKeyPrefix + track.path
                SongRow(
                    track = track,
                    index = index,
                    count = rows.size,
                    isCurrent = track.path == currentPath,
                    isPlaying = playback.isPlaying,
                    modifier = Modifier
                        .then(if (reorder.draggedKey == key) Modifier else Modifier.animateItem())
                        .reorderRow(reorder, key),
                    trailing = if (editing) {
                        {
                            Icon(
                                imageVector = AppIcons.DragHandle,
                                contentDescription = stringResource(R.string.music_drag_to_reorder),
                                tint = colors.smoke,
                                modifier = Modifier
                                    .size(28.dp)
                                    .reorderHandle(reorder, key)
                            )
                        }
                    } else null,
                    onClick = { if (!editing) tapSong(player, onAction, tracks, index, currentPath) },
                    onLongClick = { MusicUi.songMenu = SongMenuRequest(track, playlist.id, position) }
                )
            }
            if (tracks.isNotEmpty()) {
                item(key = "footer", contentType = "footer") { KobbloGroupFooter(songsSummary(tracks)) }
            }
        }
    }

    if (showMenu) {
        PlaylistMenuSheet(
            playlist = playlist,
            canReorder = tracks.size > 1,
            onDismiss = { showMenu = false },
            onRename = { renaming = true },
            onReorder = { editing = true },
            onExport = {
                scope.launch {
                    val file = PlaylistFiles.export(context, playlist, snapshot)
                    onAction(
                        AppAction.ShowToast(
                            if (file != null) {
                                context.getString(R.string.music_exported, file.path.removePrefix(android.os.Environment.getExternalStorageDirectory().path + "/"))
                            } else {
                                context.getString(R.string.music_export_failed)
                            }
                        )
                    )
                }
            },
            onDelete = { confirmDelete = true }
        )
    }
    if (renaming) {
        PlaylistNameDialog(
            title = stringResource(R.string.music_rename_playlist),
            confirm = stringResource(R.string.music_save),
            initial = playlist.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                store.update { it.withRenamedPlaylist(playlist.id, name, System.currentTimeMillis()) }
            }
        )
    }
    if (confirmDelete) {
        com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveConfirmationSheet(
            title = stringResource(R.string.music_delete_playlist),
            subtitle = stringResource(R.string.music_delete_playlist_body, playlist.name),
            confirmLabel = stringResource(R.string.music_delete),
            cancelLabel = stringResource(R.string.music_cancel),
            onConfirm = {
                confirmDelete = false
                Hub.back()
                store.update { it.withoutPlaylist(playlist.id) }
            },
            onDismissRequest = { confirmDelete = false }
        )
    }
}

/** Items above the first song in a playlist's list. */
private const val PlaylistHeaderItems = 1
private const val PlaylistSongKeyPrefix = "song:"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistMenuSheet(
    playlist: Playlist,
    canReorder: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onReorder: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = KobbloDesign.colors
    KobbloSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = KobbloSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = playlist.name,
                style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            KobbloGroup {
                PlaylistMenuRow(stringResource(R.string.music_rename), AppIcons.Pencil, showDivider = true) {
                    onDismiss()
                    onRename()
                }
                if (canReorder) {
                    PlaylistMenuRow(stringResource(R.string.music_reorder), AppIcons.DragHandle, showDivider = true) {
                        onDismiss()
                        onReorder()
                    }
                }
                PlaylistMenuRow(stringResource(R.string.music_export_m3u), AppIcons.Upload) {
                    onDismiss()
                    onExport()
                }
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                KobbloRow(
                    title = stringResource(R.string.music_delete_playlist),
                    titleColor = colors.danger,
                    trailing = { Icon(AppIcons.Trash, contentDescription = null, tint = colors.danger, modifier = Modifier.size(22.dp)) },
                    onClick = {
                        onDismiss()
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun PlaylistMenuRow(title: String, icon: ImageVector, showDivider: Boolean = false, onClick: () -> Unit) {
    KobbloRow(
        title = title,
        showDivider = showDivider,
        trailing = { Icon(icon, contentDescription = null, tint = KobbloDesign.colors.ink, modifier = Modifier.size(22.dp)) },
        onClick = onClick
    )
}

// ── Smart lists ───────────────────────────────────────────────────────────────

@Composable
private fun SmartListScreen(route: MusicRoute.Smart, state: AppState, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val snapshot = rememberSnapshot()
    val (player, playback) = rememberPlayback()
    val data by remember { MusicStore.get(context) }.data.collectAsState()
    val list = route.list
    val tracks = remember(list, data, snapshot) {
        when (list) {
            SmartList.Favorites -> snapshot.tracksFor(data.favorites)
            SmartList.RecentlyPlayed -> snapshot.tracksFor(data.recent)
            SmartList.MostPlayed -> snapshot.tracksFor(data.mostPlayed())
        }
    }
    val currentPath = playback.current?.path
    MusicScreen(title = stringResource(list.title), state = state, onAction = onAction) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = PushedScreenBottomPadding)
        ) {
            if (tracks.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    KobbloMessage(icon = list.icon, title = stringResource(list.title), body = stringResource(list.empty))
                }
            } else {
                item(key = "buttons", contentType = "buttons") {
                    PlayShuffleButtons(
                        onPlay = { player.play(tracks, shuffle = false) },
                        onShuffle = { player.shuffleAll(tracks) },
                        shuffleLabel = stringResource(R.string.music_shuffle),
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                }
                itemsIndexed(tracks, key = { _, track -> track.path }, contentType = { _, _ -> "song" }) { index, track ->
                    val plays = data.stats[track.path]?.plays ?: 0
                    SongRow(
                        track = track,
                        index = index,
                        count = tracks.size,
                        isCurrent = track.path == currentPath,
                        isPlaying = playback.isPlaying,
                        subtitle = if (list == SmartList.MostPlayed) {
                            pluralStringResource(R.plurals.music_play_count, plays, plays) + " · " + track.subtitleLine()
                        } else {
                            track.subtitleLine()
                        },
                        onClick = { tapSong(player, onAction, tracks, index, currentPath) },
                        onLongClick = { MusicUi.songMenu = SongMenuRequest(track) }
                    )
                }
                if (list == SmartList.MostPlayed && data.totalListenedMs > 0L) {
                    item(key = "listening", contentType = "footer") {
                        KobbloGroupFooter(stringResource(R.string.music_listening_time, formatListeningTime(data.totalListenedMs)))
                    }
                }
            }
        }
    }
}

/** Time spent listening, in the largest unit that's still honest about small amounts. */
@Composable
fun formatListeningTime(millis: Long): String = formatTotalDuration(millis)

