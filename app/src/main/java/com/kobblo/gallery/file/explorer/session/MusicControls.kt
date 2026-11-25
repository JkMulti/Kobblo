package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.music.MusicPlaybackState
import com.kobblo.gallery.file.explorer.music.MusicPlayer
import com.kobblo.gallery.file.explorer.music.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The mini and full players' side of the music. The player itself outlives the main screen, so this only
 * passes on what they ask for and copies what they show into the app's state.
 */
internal class MusicControls(
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val player: MusicPlayer,
    private val copies: CloudCopies,
    private val opening: FileOpening
) {
    /** Ticks while music plays. Apart from the app's state, so it only redraws the player and not everything. */
    val position: StateFlow<Long> get() = player.position

    fun observe() {
        scope.launch {
            player.state.collect { playback -> live.update { it.withMusic(playback) } }
        }
    }

    /** Plays a file, with the audio in the folder it was tapped in as the queue. One in the cloud is fetched first. */
    fun play(file: FileItem) {
        if (!Places.isInsideCloud(file.path)) return playOnPhone(file)
        val copy = copies.ready(file) ?: return opening.download(file)
        playOnPhone(file.copy(path = copy.absolutePath))
    }

    private fun playOnPhone(file: FileItem) {
        // The queue is what's beside it, and a locked song shouldn't play just for being next. This one's been
        // asked for by name, past its password if it has one.
        player.playFile(file, live.now.files.filter { !it.isLocked || it.path == file.path })
    }

    fun toggle() = player.toggle()

    fun seekTo(position: Long) = player.seekTo(position)

    fun stop() = player.stop()

    fun next() = player.next()

    fun previous() = player.previous()

    fun showFullScreen(show: Boolean) = live.update { it.copy(showFullScreenPlayer = show) }
}

/** This state with what the player is doing copied into it. */
internal fun AppState.withMusic(playback: MusicPlaybackState): AppState {
    val track = playback.current
    return copy(
        // The row that was tapped says more about the file than its path does, so it's kept while it's the same file.
        currentAudioFile = track?.let { currentAudioFile?.takeIf { shown -> shown.path == it.path } ?: fileOf(it) },
        isAudioPlaying = playback.isPlaying,
        audioDuration = playback.durationMs,
        audioTitle = track?.title.orEmpty(),
        // An untagged file says where it lives, as it does in the library's lists.
        audioArtist = track?.run { artist.ifBlank { folderName } }.orEmpty(),
        audioArtworkData = playback.artwork,
        showFullScreenPlayer = showFullScreenPlayer && track != null
    )
}

private fun fileOf(track: MusicTrack) = FileItem(
    id = track.path.hashCode() and Int.MAX_VALUE,
    path = track.path,
    name = File(track.path).name,
    type = "audio",
    kind = "media",
    size = "",
    changed = "",
    owner = "Me",
    sizeBytes = track.sizeBytes
)
