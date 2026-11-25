package com.kobblo.gallery.file.explorer.music

import androidx.compose.foundation.layout.ColumnScope
import androidx.core.content.FileProvider
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import java.io.File
import java.util.UUID

/** Asks for a song's menu. [playlistId] and [playlistIndex] add Remove from Playlist. */
data class SongMenuRequest(val track: MusicTrack, val playlistId: String? = null, val playlistIndex: Int = -1)

/** Asks for the menu of a whole album, artist or folder. */
data class CollectionMenuRequest(val title: String, val tracks: List<MusicTrack>)

/**
 * The sheets and dialogs any music screen can ask for. They live above everything, in one place, so
 * a list row only has to say which song it means and a screen can be left behind without taking
 * its sheet down mid-gesture.
 */
object MusicUi {
    var songMenu by mutableStateOf<SongMenuRequest?>(null)
    var collectionMenu by mutableStateOf<CollectionMenuRequest?>(null)
    var addToPlaylist by mutableStateOf<List<MusicTrack>?>(null)
    var confirmDelete by mutableStateOf<MusicTrack?>(null)
}

/** Drawn once, above the app. Starts the library, and shows what [MusicUi] and the player ask for. */
@Composable
fun MusicHost(state: AppState, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val player = remember { MusicPlayer.get(context) }
    val library = remember { MusicLibrary.get(context) }
    val playback by player.state.collectAsState()

    // Read now, off the main thread, so opening Audio finds the songs already there.
    LaunchedEffect(state.hasStoragePermission) {
        library.start()
    }

    MusicUi.songMenu?.let { request ->
        SongMenuSheet(request = request, onAction = onAction, onDismiss = { MusicUi.songMenu = null })
    }
    MusicUi.collectionMenu?.let { request ->
        CollectionMenuSheet(request = request, onDismiss = { MusicUi.collectionMenu = null })
    }
    MusicUi.addToPlaylist?.let { tracks ->
        AddToPlaylistSheet(tracks = tracks, onAction = onAction, onDismiss = { MusicUi.addToPlaylist = null })
    }
    MusicUi.confirmDelete?.let { track ->
        ConfirmDeleteDialog(track = track, onAction = onAction, onDismiss = { MusicUi.confirmDelete = null })
    }
    playback.problem?.let { problem ->
        PlaybackProblemDialog(problem = problem, onDismiss = { player.clearProblem() })
    }
}

/**
 * The frame the menus here share: one centered column, clear of the navigation bar. A menu whose
 * length depends on the song or on how many playlists there are [scrolls], so its last row can
 * always be reached.
 */
@Composable
private fun MenuSheet(onDismiss: () -> Unit, scrolls: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    KobbloSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(bottom = KobbloSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
private fun SongMenuSheet(request: SongMenuRequest, onAction: (AppAction) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val player = remember { MusicPlayer.get(context) }
    val store = remember { MusicStore.get(context) }
    val data by store.data.collectAsState()
    val library by MusicLibrary.get(context).snapshot.collectAsState()
    val track = request.track
    val isFavorite = data.isFavorite(track.path)

    fun leaveToScreen(open: () -> Unit) {
        onDismiss()
        onAction(AppAction.SetShowFullScreenPlayer(false))
        open()
    }

    MenuSheet(onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KobbloSpace.screen, vertical = KobbloSpace.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MusicCover(track = track, size = 52.dp, shape = KobbloShapes.tile)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.subtitleLine(),
                    style = KobbloType.footnote,
                    color = colors.smoke,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(KobbloSpace.xs))
        KobbloGroup {
            MenuRow(stringResource(R.string.music_play_next), AppIcons.Queue, showDivider = true) {
                onDismiss()
                player.playNext(listOf(track))
                onAction(AppAction.ShowToast(context.getString(R.string.music_queued_next)))
            }
            MenuRow(stringResource(R.string.music_add_to_queue), AppIcons.Add, showDivider = true) {
                onDismiss()
                player.addToQueue(listOf(track))
                onAction(AppAction.ShowToast(context.getString(R.string.music_queued)))
            }
            MenuRow(stringResource(R.string.music_add_to_playlist), AppIcons.Playlist, showDivider = true) {
                onDismiss()
                MusicUi.addToPlaylist = listOf(track)
            }
            MenuRow(
                stringResource(if (isFavorite) R.string.music_unfavorite else R.string.music_favorite),
                if (isFavorite) AppIcons.StarFill else AppIcons.Star
            ) {
                store.update { it.withFavorite(track.path, !isFavorite) }
            }
        }
        Spacer(Modifier.height(KobbloSpace.groupGap))
        KobbloGroup {
            val album = library.albums.firstOrNull { a -> a.tracks.any { it.path == track.path } }
            val artist = library.artists.firstOrNull { a -> a.name.isNotBlank() && a.tracks.any { it.path == track.path } }
            if (album != null) {
                MenuRow(stringResource(R.string.music_go_to_album), AppIcons.Book, showDivider = true) {
                    leaveToScreen { Hub.open(MusicRoute.Album(album.key)) }
                }
            }
            if (artist != null) {
                MenuRow(stringResource(R.string.music_go_to_artist), AppIcons.Person, showDivider = true) {
                    leaveToScreen { Hub.open(MusicRoute.Artist(artist.key)) }
                }
            }
            MenuRow(stringResource(R.string.music_share), AppIcons.Share) {
                onDismiss()
                shareTrack(context, track)
            }
        }
        Spacer(Modifier.height(KobbloSpace.groupGap))
        KobbloGroup {
            if (request.playlistId != null) {
                MenuRow(stringResource(R.string.music_remove_from_playlist), AppIcons.Remove, showDivider = true) {
                    onDismiss()
                    store.update { it.withRemovedFromPlaylist(request.playlistId, request.playlistIndex, System.currentTimeMillis()) }
                }
            }
            MenuRow(stringResource(R.string.music_delete), AppIcons.Trash, tint = colors.danger) {
                onDismiss()
                MusicUi.confirmDelete = track
            }
        }
    }
}

@Composable
private fun CollectionMenuSheet(request: CollectionMenuRequest, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val player = remember { MusicPlayer.get(context) }
    MenuSheet(onDismiss, scrolls = false) {
        Text(
            text = request.title,
            style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        KobbloGroup {
            MenuRow(stringResource(R.string.music_play), AppIcons.Play, showDivider = true) {
                onDismiss()
                player.play(request.tracks, shuffle = false)
            }
            MenuRow(stringResource(R.string.music_shuffle), AppIcons.Shuffle, showDivider = true) {
                onDismiss()
                player.shuffleAll(request.tracks)
            }
            MenuRow(stringResource(R.string.music_play_next), AppIcons.Queue, showDivider = true) {
                onDismiss()
                player.playNext(request.tracks)
            }
            MenuRow(stringResource(R.string.music_add_to_queue), AppIcons.Add, showDivider = true) {
                onDismiss()
                player.addToQueue(request.tracks)
            }
            MenuRow(stringResource(R.string.music_add_to_playlist), AppIcons.Playlist) {
                onDismiss()
                MusicUi.addToPlaylist = request.tracks
            }
        }
    }
}

@Composable
private fun MenuRow(
    title: String,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color = KobbloDesign.colors.ink,
    showDivider: Boolean = false,
    onClick: () -> Unit
) {
    KobbloRow(
        title = title,
        titleColor = tint,
        showDivider = showDivider,
        trailing = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) },
        onClick = onClick
    )
}

@Composable
private fun AddToPlaylistSheet(tracks: List<MusicTrack>, onAction: (AppAction) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val store = remember { MusicStore.get(context) }
    val data by store.data.collectAsState()
    var naming by remember { mutableStateOf(false) }
    val paths = remember(tracks) { tracks.map { it.path } }

    fun addTo(id: String, name: String) {
        store.update { it.withAddedToPlaylist(id, paths, System.currentTimeMillis()) }
        onAction(AppAction.ShowToast(context.getString(R.string.music_added_to_playlist, name)))
        onDismiss()
    }

    MenuSheet(onDismiss) {
        Text(
            text = stringResource(R.string.music_add_to_playlist),
            style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        KobbloGroup {
            KobbloRow(
                title = stringResource(R.string.music_new_playlist),
                titleColor = colors.action,
                showDivider = data.playlists.isNotEmpty(),
                trailing = { Icon(AppIcons.Add, contentDescription = null, tint = colors.action, modifier = Modifier.size(22.dp)) },
                onClick = { naming = true }
            )
            data.playlists.forEachIndexed { index, playlist ->
                KobbloRow(
                    title = playlist.name,
                    subtitle = androidx.compose.ui.res.pluralStringResource(R.plurals.music_song_count, playlist.paths.size, playlist.paths.size),
                    showDivider = index < data.playlists.lastIndex,
                    onClick = { addTo(playlist.id, playlist.name) }
                )
            }
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
                val id = UUID.randomUUID().toString()
                store.update { it.withNewPlaylist(id, name, paths, System.currentTimeMillis()) }
                onAction(AppAction.ShowToast(context.getString(R.string.music_added_to_playlist, name)))
                onDismiss()
            }
        )
    }
}

/** Asks for a playlist's name, with the keyboard already up. */
@Composable
fun PlaylistNameDialog(
    title: String,
    confirm: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val colors = KobbloDesign.colors
    var name by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    com.kobblo.gallery.file.explorer.ui.GradientAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold), color = colors.ink) },
        text = {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = KobbloType.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.action),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .clip(CapsuleShape)
                    .background(colors.mist)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (name.isEmpty()) {
                        Text(stringResource(R.string.music_playlist_name), style = KobbloType.body, color = colors.smoke)
                    }
                    inner()
                }
            )
        },
        confirmButton = {
            com.kobblo.gallery.file.explorer.ui.design.KobbloAlertAction(label = confirm, onClick = { onConfirm(name) }, enabled = name.isNotBlank())
        },
        dismissButton = {
            com.kobblo.gallery.file.explorer.ui.design.KobbloAlertCancel(onClick = onDismiss, label = stringResource(R.string.music_cancel))
        }
    )
}

@Composable
private fun ConfirmDeleteDialog(track: MusicTrack, onAction: (AppAction) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveConfirmationSheet(
        title = stringResource(R.string.music_delete_title),
        subtitle = stringResource(R.string.music_delete_body, track.title),
        confirmLabel = stringResource(R.string.music_delete),
        cancelLabel = stringResource(R.string.music_cancel),
        onConfirm = {
            onDismiss()
            MusicPlayer.get(context).removePath(track.path)
            MusicLibrary.get(context).moveToTrash(track.path) { report ->
                if (!report.ok) onAction(AppAction.ShowToast(com.kobblo.gallery.file.explorer.fileops.FileOpText.deleteFailed(context, 0, report.failed)))
                onAction(AppAction.Reload)
            }
        },
        onDismissRequest = onDismiss
    )
}

@Composable
private fun PlaybackProblemDialog(problem: PlaybackProblem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val name = problem.track.fileName
    val message = when (problem.kind) {
        PlaybackProblemKind.FileMissing -> stringResource(R.string.music_problem_missing, name)
        PlaybackProblemKind.NoPermission -> stringResource(R.string.music_problem_permission, name)
        PlaybackProblemKind.Unsupported -> stringResource(R.string.music_problem_unsupported, name)
        PlaybackProblemKind.Damaged -> stringResource(R.string.music_problem_damaged, name)
        PlaybackProblemKind.Other -> stringResource(R.string.music_problem_other, name)
    }
    val canOpenElsewhere = problem.kind != PlaybackProblemKind.FileMissing && File(problem.track.path).exists()
    com.kobblo.gallery.file.explorer.ui.GradientAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.music_problem_title), style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold), color = colors.ink) },
        text = { Text(message, style = KobbloType.callout, color = colors.smoke) },
        confirmButton = {
            if (canOpenElsewhere) {
                com.kobblo.gallery.file.explorer.ui.design.KobbloAlertAction(
                    label = stringResource(R.string.music_open_with),
                    onClick = {
                        onDismiss()
                        MusicPlayer.get(context).pause()
                        openWithAnotherApp(context, problem.track)
                    }
                )
            }
        },
        dismissButton = {
            com.kobblo.gallery.file.explorer.ui.design.KobbloAlertCancel(onClick = onDismiss, label = stringResource(R.string.music_ok))
        }
    )
}

private fun fileItemOf(track: MusicTrack): FileItem = FileItem(
    id = track.path.hashCode() and 0x7FFFFFFF,
    path = track.path,
    name = track.fileName,
    type = "audio",
    kind = "media",
    size = "",
    changed = "",
    owner = "Me"
)

internal fun openWithAnotherApp(context: Context, track: MusicTrack) {
    com.kobblo.gallery.file.explorer.ui.openFileInExternalApp(context, fileItemOf(track), "audio/*")
}

internal fun shareTrack(context: Context, track: MusicTrack) = shareSong(context, track.path, inOwnTask = true)

/**
 * Offers the song at [path] to other apps through the system's share sheet. With [inOwnTask] the sheet
 * opens as a task of its own rather than on top of the screen that asked. A file that can't be handed
 * over, and a device with nothing to hand it to, are passed over without a word.
 */
internal fun shareSong(context: Context, path: String, inOwnTask: Boolean) {
    try {
        val song = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_STREAM, song)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val sheet = Intent.createChooser(send, context.getString(R.string.music_share))
        if (inOwnTask) sheet.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(sheet)
    } catch (_: Exception) {
    }
}

/** The arrangement used by header rows of chips across the music screens. */
val MusicChipSpacing: Arrangement.Horizontal = Arrangement.spacedBy(6.dp)
