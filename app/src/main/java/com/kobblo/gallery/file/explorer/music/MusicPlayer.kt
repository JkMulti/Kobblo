package com.kobblo.gallery.file.explorer.music

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.service.quicksettings.TileService
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.kobblo.gallery.file.explorer.FileItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Why a song couldn't play. */
enum class PlaybackProblemKind { FileMissing, NoPermission, Unsupported, Damaged, Other }

data class PlaybackProblem(val track: MusicTrack, val kind: PlaybackProblemKind)

data class MusicPlaybackState(
    val queue: PlayQueue = PlayQueue(),
    val isPlaying: Boolean = false,
    /** The length the player reports, which beats whatever the library thought once it's known. */
    val durationMs: Long = 0L,
    val artwork: ByteArray? = null,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val problem: PlaybackProblem? = null
) {
    val current: MusicTrack? get() = queue.current?.track
    val isShuffled: Boolean get() = queue.isShuffled
}

/** Lyrics for the song that's playing, once they've been looked for. */
data class LyricsState(val path: String? = null, val lyrics: Lyrics? = null, val isLoading: Boolean = false)

/**
 * The one player in the app. It outlives every screen, so playback carries on when the Activity goes,
 * and it's what [MusicService] publishes to the notification and lock screen. The mini player and Now
 * Playing read it through `MainViewModel`, as they always have.
 *
 * All of it runs on the main thread, as ExoPlayer requires. The player itself is built on first use,
 * so a launch that doesn't touch music doesn't pay for it, and a restored queue only loads into the
 * player when something asks it to play.
 */
@OptIn(UnstableApi::class)
class MusicPlayer private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = MusicStore.get(app)
    private val library = MusicLibrary.get(app)
    private val session = MusicSessionStore(app)
    private val effects = SoundEffects(app)
    private val nextUid = AtomicLong(1L)

    // The player's volume is these two multiplied, so the sleep timer and a crossfade can't undo each other.
    private var sleepGain = 1f
    private var fadeGain = 1f

    private val crossfader = Crossfader(
        scope = scope,
        mainPlayer = { exo },
        buildHelper = ::buildTailPlayer,
        lengthOfItem = { index -> _state.value.queue.entries.getOrNull(index)?.track?.durationMs ?: 0L },
        queueSettled = { queueLoad?.isActive != true },
        outerGain = { sleepGain },
        setMainGain = { gain ->
            fadeGain = gain
            applyVolume()
        }
    )

    private val _state = MutableStateFlow(MusicPlaybackState())
    val state: StateFlow<MusicPlaybackState> = _state.asStateFlow()

    // Ticks while playing, in its own flow so only the players redraw for it.
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _lyrics = MutableStateFlow(LyricsState())
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()

    private val _sleepEndsAt = MutableStateFlow<Long?>(null)
    /** When the sleep timer will stop playback, on the [SystemClock.elapsedRealtime] clock. */
    val sleepEndsAt: StateFlow<Long?> = _sleepEndsAt.asStateFlow()

    private var exo: ExoPlayer? = null
    private var queueNeedsLoading = false
    private var queueLoad: Job? = null
    private val waitingForQueue = ArrayDeque<() -> Unit>()
    private var ticker: Job? = null
    private var tagsJob: Job? = null
    private var sidecarJob: Job? = null
    private var saveQueueJob: Job? = null
    private var sleepJob: Job? = null
    private var lastTick = 0L
    private var ticksSinceSave = 0
    private var failuresInARow = 0
    private var currentUid = 0L

    // Listening time adds up in here and reaches the store now and then. Every tick would redraw
    // everything that reads the store, and would keep pushing its save back for as long as music played.
    private var unsavedListenPath: String? = null
    private var unsavedListenMs = 0L

    private val listens = ListenTracker(
        onListened = { path, delta ->
            if (path != unsavedListenPath) flushListening()
            unsavedListenPath = path
            unsavedListenMs += delta
            if (unsavedListenMs >= FlushListeningMs) flushListening()
        },
        onCounted = { path ->
            flushListening()
            store.update { it.withPlay(path, System.currentTimeMillis()) }
        }
    )

    private fun flushListening() {
        val path = unsavedListenPath
        val listened = unsavedListenMs
        unsavedListenMs = 0L
        if (path != null && listened > 0L) store.update { it.withListened(path, listened) }
    }

    // The lyrics come from two places that finish in either order: a file beside the song, and the
    // tags the player reads when it opens it. These hold what each has found for the current song.
    private var sidecarLyricsFor: String? = null
    private var sidecarLyrics: Lyrics? = null
    private var embeddedLyricsFor: String? = null
    private var embeddedLyrics: Lyrics? = null

    init {
        scope.launch { restoreQueue() }
        scope.launch {
            store.data.map { it.sound }.distinctUntilChanged().collect { applySound(it) }
        }
    }

    // ── Starting playback ─────────────────────────────────────────────────────

    /**
     * Plays [tracks] as the new queue, from [startIndex]. Leaving [shuffle] null keeps whatever the
     * user last chose: tapping a song while shuffle is on starts there and shuffles the rest.
     */
    fun play(tracks: List<MusicTrack>, startIndex: Int = 0, shuffle: Boolean? = null) {
        if (tracks.isEmpty()) return
        val shuffling = shuffle ?: _state.value.isShuffled
        val entries = newEntries(tracks)
        var queue = PlayQueue(entries = entries, index = startIndex.coerceIn(tracks.indices))
        if (shuffling) queue = queue.shuffled()
        replaceQueue(queue, positionMs = 0L, playNow = true)
    }

    /** Shuffles a whole list, starting from a random song in it. */
    fun shuffleAll(tracks: List<MusicTrack>) {
        if (tracks.isEmpty()) return
        play(tracks, startIndex = Random.nextInt(tracks.size), shuffle = true)
    }

    /**
     * Plays a file tapped in a folder, with the audio beside it as the queue. Whatever the library
     * already knows about each file comes along, so the queue shows real titles straight away.
     */
    fun playFile(file: FileItem, siblings: List<FileItem>) {
        val known = library.snapshot.value.byPath
        val storageRoot = Environment.getExternalStorageDirectory().path
        // Another app may hand over /sdcard for what the library knows by its real path. Favorites
        // and stats key on the path, so a file has to go by just one.
        fun trackOf(item: FileItem): MusicTrack {
            val path = canonicalStoragePath(item.path, storageRoot)
            return known[path] ?: trackFromPath(path, item.sizeBytes)
        }
        val audio = siblings.filter { it.type != "folder" && (it.type == "audio" || isAudioFileName(it.name)) }
        val index = audio.indexOfFirst { it.path == file.path }
        if (index >= 0) {
            play(audio.map(::trackOf), startIndex = index)
        } else {
            play(listOf(trackOf(file)))
        }
    }

    private fun newEntries(tracks: List<MusicTrack>): List<QueueEntry> =
        tracks.map { QueueEntry(nextUid.getAndIncrement(), it) }

    /** Makes [queue] the queue. The screens see it at once, and the music starts as soon as it can. */
    private fun replaceQueue(queue: PlayQueue, positionMs: Long, playNow: Boolean) {
        crossfader.cutShort()
        _state.update { it.copy(queue = queue, problem = null, durationMs = queue.current?.track?.durationMs ?: 0L) }
        _position.value = positionMs
        failuresInARow = 0
        scheduleQueueSave()
        loadQueue(queue, positionMs, playNow)
    }

    /**
     * Hands [queue] to the player. Thousands of songs would freeze the app if done in one go, so a long
     * queue's items are made on a background thread, then the song that's on goes in straight away and
     * the rest follow in slices with frames drawn between them. Whatever is asked of the queue in the
     * meantime waits its turn in [whenQueueLoaded], so the player and the queue never disagree.
     */
    private fun loadQueue(queue: PlayQueue, positionMs: Long, playNow: Boolean) {
        queueLoad?.cancel()
        queueLoad = null
        waitingForQueue.clear()
        queueNeedsLoading = false
        if (queue.entries.size <= LoadAtOnceLimit) {
            val player = player()
            player.shuffleModeEnabled = queue.isShuffled
            player.setMediaItems(queue.entries.map(::mediaItemOf), queue.index, positionMs)
            player.prepare()
            if (playNow) startPlaying(player)
            return
        }
        queueLoad = scope.launch {
            val items = withContext(Dispatchers.Default) { queue.entries.map(::mediaItemOf) }
            val player = player()
            player.shuffleModeEnabled = queue.isShuffled
            val index = queue.index.coerceAtLeast(0)
            player.setMediaItems(listOf(items[index]), 0, positionMs)
            player.prepare()
            if (playNow) startPlaying(player)
            // The songs still to come go first, so the current one can't run out with nothing after it.
            for (slice in items.subList(index + 1, items.size).chunked(LoadSliceSize)) {
                delay(LoadSliceGapMs)
                player.addMediaItems(slice)
            }
            var at = 0
            for (slice in items.subList(0, index).chunked(LoadSliceSize)) {
                delay(LoadSliceGapMs)
                player.addMediaItems(at, slice)
                at += slice.size
            }
            queueLoad = null
            while (waitingForQueue.isNotEmpty()) waitingForQueue.removeFirst().invoke()
        }
    }

    /** Runs [action] now, or once a long queue on its way to the player has all arrived. */
    private fun whenQueueLoaded(action: () -> Unit) {
        if (queueNeedsLoading) {
            val queue = _state.value.queue
            if (queue.isEmpty) queueNeedsLoading = false else loadQueue(queue, _position.value, playNow = false)
        }
        if (queueLoad?.isActive == true) waitingForQueue.addLast(action) else action()
    }

    // ── The player ────────────────────────────────────────────────────────────

    private fun player(): ExoPlayer = exo ?: buildPlayer().also { exo = it }

    private fun mediaSources(): DefaultMediaSourceFactory {
        // Raw AAC and some MP3s have no index to measure them by. Treating them as constant bitrate
        // is an estimate, but it gives them a length and makes them seekable, which beats neither.
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        return DefaultMediaSourceFactory(app, extractors)
    }

    private fun musicAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /**
     * Plays the end of a song during a crossfade. Audio focus, a headset coming out and the session all belong to
     * the main player, and this one follows it when it pauses.
     */
    private fun buildTailPlayer(): ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(mediaSources())
        .setAudioAttributes(musicAttributes(), /* handleAudioFocus = */ false)
        .build()

    private fun applyVolume() {
        exo?.volume = sleepGain * fadeGain
    }

    private fun buildPlayer(): ExoPlayer {
        val player = ExoPlayer.Builder(app)
            .setMediaSourceFactory(mediaSources())
            .setAudioAttributes(musicAttributes(), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // Shuffle is done in the queue itself, so the player's own order must stay as listed. The
        // flag is still set, which keeps headsets and the system's controls showing it correctly.
        player.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(0))
        player.repeatMode = _state.value.repeatMode
        player.addListener(playerListener)
        val sound = store.data.value.sound
        player.playbackParameters = PlaybackParameters(sound.speed, sound.pitchFactor)
        player.skipSilenceEnabled = sound.skipSilence
        val audioSession = player.audioSessionId
        scope.launch(Dispatchers.Default) { effects.attach(audioSession, sound) }
        return player
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startTicker() else stopTicker()
            savePosition()
            refreshTile()
            crossfader.onPlayingChanged(isPlaying)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = crossfader.followMain()

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = crossfader.followMain()

        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = exo ?: return
            when (playbackState) {
                Player.STATE_READY -> {
                    val duration = player.duration.coerceAtLeast(0L)
                    _state.update { it.copy(durationMs = if (duration > 0L) duration else it.current?.durationMs ?: 0L) }
                    listens.updateDuration(duration)
                }
                Player.STATE_ENDED -> afterListeners {
                    crossfader.cutShort()
                    // The queue ran out. Go back to its start, ready to play again rather than stuck.
                    val ended = exo ?: return@afterListeners
                    if (ended.playbackState == Player.STATE_ENDED) {
                        ended.playWhenReady = false
                        ended.seekTo(0, 0L)
                        _position.value = 0L
                        savePosition()
                    }
                }
                else -> Unit
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val uid = mediaItem?.mediaId?.toLongOrNull() ?: return
            val state = _state.value
            val index = state.queue.indexOfUid(uid)
            if (index < 0) return
            _state.update { it.copy(queue = it.queue.at(index)) }
            _position.value = exo?.currentPosition ?: 0L
            val entry = state.queue.entries[index]
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                listens.begin(entry.track.path, _state.value.durationMs)
            } else {
                onCurrentChanged(entry)
            }
            saveQueueIndex()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = afterListeners {
            whenQueueLoaded {
                val on = exo?.shuffleModeEnabled ?: return@whenQueueLoaded
                val queue = _state.value.queue
                if (queue.isShuffled != on) {
                    reorderAroundCurrent(if (on) queue.shuffled() else queue.unshuffledBack())
                }
            }
        }

        // A seek from the notification or a headset, or a song looping, moves the position while
        // nothing else here is watching it.
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            _position.value = newPosition.positionMs
            when (reason) {
                Player.DISCONTINUITY_REASON_SEEK -> crossfader.onMoved(isSeek = true)
                Player.DISCONTINUITY_REASON_REMOVE -> crossfader.onMoved(isSeek = false)
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.update { it.copy(repeatMode = repeatMode) }
            session.repeatMode = repeatMode
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            val sound = store.data.value.sound
            scope.launch(Dispatchers.Default) { effects.attach(audioSessionId, sound) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (tracks.isEmpty) return
            val track = _state.value.current ?: return
            embeddedLyricsFor = track.path
            embeddedLyrics = embeddedLyricsIn(tracks)
            publishLyrics()
            // A file can open fine and still hold nothing this device can decode. The player would
            // play its silence to the end, so this is the one failure it won't report by itself.
            if (!tracks.isTypeSupported(C.TRACK_TYPE_AUDIO, /* allowExceedsCapabilities = */ true)) {
                afterListeners { reportProblem(track, PlaybackProblemKind.Unsupported) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            crossfader.cutShort()
            val track = _state.value.current ?: return
            afterListeners { reportProblem(track, kindOf(error)) }
        }
    }

    /**
     * Runs once every listener has heard the current event. Changing the player from inside a callback
     * delivers the listeners after it a view of the player that's already moved on. The media session
     * is one of those, and it treats a mismatch between the two as fatal.
     */
    private fun afterListeners(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

    /** Says what went wrong and, if there's more in the queue, keeps the music going. */
    private fun reportProblem(track: MusicTrack, kind: PlaybackProblemKind) {
        val player = exo ?: return
        // Nobody asked this one to play: the queue ran out and came to rest on it, or a restored queue
        // opened on it. Pressing Play tries it again, and that's when it's worth a message.
        if (!player.playWhenReady) return
        _state.update { it.copy(problem = PlaybackProblem(track, kind)) }
        failuresInARow++
        if (player.playWhenReady && player.hasNextMediaItem() && failuresInARow < player.mediaItemCount) {
            player.seekToNextMediaItem()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        } else {
            player.playWhenReady = false
        }
    }

    private fun kindOf(error: PlaybackException): PlaybackProblemKind = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackProblemKind.FileMissing
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlaybackProblemKind.NoPermission
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> PlaybackProblemKind.Unsupported
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> PlaybackProblemKind.Damaged
        else -> PlaybackProblemKind.Other
    }

    fun clearProblem() {
        _state.update { it.copy(problem = null) }
    }

    private fun mediaItemOf(entry: QueueEntry): MediaItem {
        val track = entry.track
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist.ifBlank { null })
            .setAlbumTitle(track.album.ifBlank { null })
            .setAlbumArtist(track.albumArtist.ifBlank { null })
            .apply {
                if (track.trackNumber > 0) setTrackNumber(track.trackNumber)
                // The notification's fallback. The cover embedded in the file wins once it's read.
                if (track.albumId > 0L) setArtworkUri(ContentUris.withAppendedId(AlbumArtRoot, track.albumId))
            }
            .build()
        // No MIME type on purpose: a misnamed file is sniffed from its bytes rather than turned down.
        return MediaItem.Builder()
            .setMediaId(entry.uid.toString())
            .setUri(uriOf(track.path))
            .setMediaMetadata(metadata)
            .build()
    }

    // ── Transport ─────────────────────────────────────────────────────────────

    fun toggle() = whenQueueLoaded {
        val player = player()
        if (player.playWhenReady && player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) {
            player.pause()
        } else {
            resume()
        }
    }

    fun resume() = whenQueueLoaded { startPlaying(player()) }

    private fun startPlaying(player: ExoPlayer) {
        if (player.mediaItemCount == 0) return
        ensureService()
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> player.seekToDefaultPosition()
            else -> Unit
        }
        player.play()
    }

    fun pause() {
        exo?.pause()
    }

    fun seekTo(positionMs: Long) = whenQueueLoaded {
        player().seekTo(positionMs.coerceAtLeast(0L))
        _position.value = positionMs.coerceAtLeast(0L)
        savePosition()
    }

    /** The next song, wrapping to the start at the end of the queue so the button is never dead. */
    fun next() = whenQueueLoaded {
        val player = player()
        if (player.mediaItemCount == 0) return@whenQueueLoaded
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekTo(0, 0L)
        resume()
    }

    /** Back to the start of this song, or to the one before if it has only just begun. */
    fun previous() = whenQueueLoaded {
        val player = player()
        if (player.mediaItemCount == 0) return@whenQueueLoaded
        player.seekToPrevious()
        _position.value = player.currentPosition
        resume()
    }

    /** Jumps to a song already in the queue. */
    fun jumpTo(index: Int) = whenQueueLoaded {
        val player = player()
        if (index !in 0 until player.mediaItemCount) return@whenQueueLoaded
        player.seekTo(index, 0L)
        _position.value = 0L
        resume()
    }

    fun stop() {
        cancelSleepTimer()
        saveQueueJob?.cancel()
        queueLoad?.cancel()
        queueLoad = null
        waitingForQueue.clear()
        currentUid = 0L
        crossfader.stopNow()
        exo?.let {
            it.playWhenReady = false
            it.stop()
            it.clearMediaItems()
        }
        queueNeedsLoading = false
        _position.value = 0L
        _state.update { it.copy(queue = PlayQueue(), isPlaying = false, durationMs = 0L, artwork = null, problem = null) }
        _lyrics.value = LyricsState()
        listens.begin(null, 0L)
        scope.launch(Dispatchers.IO) { session.clear() }
        store.flush()
        try {
            app.stopService(Intent(app, MusicService::class.java))
        } catch (_: Exception) {
        }
        refreshTile()
    }

    /** Makes sure the player has the queue open, for screens about to show its details. */
    fun ensureLoaded() = whenQueueLoaded { }

    fun setShuffle(enabled: Boolean) = whenQueueLoaded {
        player().shuffleModeEnabled = enabled
    }

    fun toggleShuffle() = setShuffle(!_state.value.isShuffled)

    fun setRepeatMode(mode: Int) {
        val player = exo
        if (player != null) {
            player.repeatMode = mode
        } else {
            // Nothing has played yet, so there's no player to tell. It picks this up when built.
            _state.update { it.copy(repeatMode = mode) }
            session.repeatMode = mode
        }
    }

    /** Off, then the whole queue, then this one song. */
    fun cycleRepeat() {
        setRepeatMode(
            when (_state.value.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        )
    }

    // ── The queue ─────────────────────────────────────────────────────────────

    fun playNext(tracks: List<MusicTrack>): Unit = whenQueueLoaded {
        if (tracks.isEmpty()) return@whenQueueLoaded
        val state = _state.value
        if (state.queue.isEmpty) {
            play(tracks)
            return@whenQueueLoaded
        }
        val entries = newEntries(tracks)
        val queue = state.queue.playNext(entries)
        _state.update { it.copy(queue = queue) }
        player().addMediaItems(state.queue.index + 1, entries.map(::mediaItemOf))
        scheduleQueueSave()
    }

    fun addToQueue(tracks: List<MusicTrack>): Unit = whenQueueLoaded {
        if (tracks.isEmpty()) return@whenQueueLoaded
        val state = _state.value
        if (state.queue.isEmpty) {
            play(tracks)
            return@whenQueueLoaded
        }
        val entries = newEntries(tracks)
        _state.update { it.copy(queue = it.queue.append(entries)) }
        player().addMediaItems(entries.map(::mediaItemOf))
        scheduleQueueSave()
    }

    fun moveQueueItem(from: Int, to: Int) = whenQueueLoaded {
        val queue = _state.value.queue
        val moved = queue.moved(from, to)
        if (moved === queue) return@whenQueueLoaded
        _state.update { it.copy(queue = moved) }
        player().moveMediaItem(from, to)
        scheduleQueueSave()
    }

    fun removeQueueItem(index: Int) = whenQueueLoaded {
        val queue = _state.value.queue
        if (index !in queue.entries.indices) return@whenQueueLoaded
        if (queue.entries.size == 1) {
            stop()
            return@whenQueueLoaded
        }
        _state.update { it.copy(queue = queue.removedAt(index)) }
        player().removeMediaItem(index)
        scheduleQueueSave()
    }

    /** Clears everything but the song that's playing. */
    fun clearQueue() = whenQueueLoaded {
        val queue = _state.value.queue
        val current = queue.index
        if (current < 0) return@whenQueueLoaded
        _state.update { it.copy(queue = queue.cleared()) }
        val player = player()
        player.removeMediaItems(current + 1, player.mediaItemCount)
        if (current > 0) player.removeMediaItems(0, current)
        scheduleQueueSave()
    }

    /** Takes a file that's gone out of the queue, wherever it was, however many times it was queued. */
    fun removePath(path: String) = whenQueueLoaded {
        val queue = _state.value.queue
        for (index in queue.entries.indices.reversed()) {
            if (queue.entries[index].track.path == path) removeQueueItem(index)
        }
    }

    /**
     * Rebuilds the queue around the song that's playing, without touching it, so turning shuffle on
     * or off never so much as hiccups the music.
     */
    private fun reorderAroundCurrent(reordered: PlayQueue) {
        _state.update { it.copy(queue = reordered) }
        val player = player()
        val current = player.currentMediaItemIndex
        val count = player.mediaItemCount
        if (count > 0 && reordered.index >= 0) {
            player.removeMediaItems(current + 1, count)
            if (current > 0) player.removeMediaItems(0, current)
            val before = reordered.entries.take(reordered.index).map(::mediaItemOf)
            val after = reordered.entries.drop(reordered.index + 1).map(::mediaItemOf)
            if (after.isNotEmpty()) player.addMediaItems(1, after)
            if (before.isNotEmpty()) player.addMediaItems(0, before)
        }
        scheduleQueueSave()
    }

    // ── The current song ──────────────────────────────────────────────────────

    private fun onCurrentChanged(entry: QueueEntry) {
        // Loading a restored queue into the player lands here too, for a song already set up.
        if (entry.uid == currentUid) return
        currentUid = entry.uid
        val track = entry.track
        // The old cover stays until the new one is read, so skipping within an album doesn't flash.
        _state.update { it.copy(durationMs = track.durationMs) }
        listens.begin(track.path, track.durationMs)
        loadDetails(entry)
        loadSidecarLyrics(track)
        refreshTile()
    }

    private fun loadDetails(entry: QueueEntry) {
        tagsJob?.cancel()
        tagsJob = scope.launch {
            val details = withContext(Dispatchers.IO) { readDetails(entry.track.path) }
            if (_state.value.queue.current?.uid != entry.uid) return@launch
            _state.update { it.copy(artwork = details.artwork) }
            // A file MediaStore has never seen only has its name so far. Now it has real tags too.
            val track = entry.track
            if (track.mediaId == 0L) {
                val tagged = track.copy(
                    title = details.title?.takeIf { it.isNotBlank() } ?: track.title,
                    artist = details.artist?.takeIf { it.isNotBlank() } ?: track.artist,
                    album = details.album?.takeIf { it.isNotBlank() } ?: track.album,
                    durationMs = if (track.durationMs > 0L) track.durationMs else details.durationMs
                )
                if (tagged != track) {
                    val updated = entry.copy(track = tagged)
                    _state.update { it.copy(queue = it.queue.withTrack(entry.uid, tagged)) }
                    val player = exo
                    val index = _state.value.queue.indexOfUid(entry.uid)
                    if (player != null && index in 0 until player.mediaItemCount) {
                        // The same URI, so this only refreshes the notification. Playback doesn't stop.
                        player.replaceMediaItem(index, mediaItemOf(updated))
                    }
                    scheduleQueueSave()
                }
            }
        }
    }

    private fun loadSidecarLyrics(track: MusicTrack) {
        _lyrics.value = LyricsState(path = track.path, isLoading = true)
        sidecarLyricsFor = null
        sidecarLyrics = null
        sidecarJob?.cancel()
        sidecarJob = scope.launch {
            val found = withContext(Dispatchers.IO) { readSidecarLyrics(track.path) }
            sidecarLyricsFor = track.path
            sidecarLyrics = found
            publishLyrics()
        }
    }

    private fun publishLyrics() {
        val path = _state.value.current?.path ?: return
        val fromFile = if (sidecarLyricsFor == path) sidecarLyrics else null
        val fromTags = if (embeddedLyricsFor == path) embeddedLyrics else null
        // A file beside the song is something the user put there on purpose, so it wins. Otherwise
        // synced beats plain, wherever each came from.
        val best = when {
            fromFile?.synced == true -> fromFile
            fromTags?.synced == true -> fromTags
            else -> fromFile ?: fromTags
        }
        val stillLooking = best == null && (sidecarLyricsFor != path || embeddedLyricsFor != path)
        _lyrics.value = LyricsState(path = path, lyrics = best, isLoading = stillLooking)
    }

    // ── Sound ─────────────────────────────────────────────────────────────────

    private fun applySound(sound: SoundSettings) {
        crossfader.setLength(sound.crossfadeSeconds)
        val player = exo ?: return
        player.playbackParameters = PlaybackParameters(sound.speed, sound.pitchFactor)
        player.skipSilenceEnabled = sound.skipSilence
        crossfader.followSound(player)
        scope.launch(Dispatchers.Default) { effects.apply(sound) }
    }

    /** What the device's equalizer can do, or null if it doesn't have one. */
    suspend fun describeEqualizer(): EqualizerInfo? {
        val session = player().audioSessionId
        val sound = store.data.value.sound
        return withContext(Dispatchers.Default) {
            effects.attach(session, sound)
            effects.describe()
        }
    }

    suspend fun presetLevels(preset: Int): List<Int>? =
        withContext(Dispatchers.Default) { effects.levelsOfPreset(preset) }

    fun startSleepTimer(minutes: Int) {
        store.update { it.copy(sound = it.sound.copy(lastSleepMinutes = minutes)) }
        sleepJob?.cancel()
        setSleepGain(1f)
        val endsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        _sleepEndsAt.value = endsAt
        sleepJob = scope.launch {
            // Checked against the real clock in steps, since a delay stands still while the phone sleeps.
            while (isActive && SystemClock.elapsedRealtime() < endsAt - SleepFadeMs) {
                delay((endsAt - SleepFadeMs - SystemClock.elapsedRealtime()).coerceIn(50L, 30_000L))
            }
            // A slow fade, so the music leaves without waking anyone.
            val steps = 40
            for (step in 1..steps) {
                setSleepGain(1f - step.toFloat() / steps)
                delay(SleepFadeMs / steps)
            }
            exo?.pause()
            setSleepGain(1f)
            _sleepEndsAt.value = null
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        setSleepGain(1f)
        _sleepEndsAt.value = null
    }

    private fun setSleepGain(gain: Float) {
        sleepGain = gain
        applyVolume()
        crossfader.refreshGain()
    }

    // ── Service ───────────────────────────────────────────────────────────────

    /** The player [MusicService] publishes. Never null once the service asks for it. */
    internal val sessionPlayer: ExoPlayer get() = player()

    private fun ensureService() {
        try {
            app.startService(Intent(app, MusicService::class.java))
        } catch (_: IllegalStateException) {
            // Android won't start a service for a background app. The next tap in the app retries it.
        }
    }

    /** With no service holding the session, an idle player is only tying up codecs. */
    internal fun onServiceDestroyed() {
        val player = exo ?: return
        if (player.playWhenReady) return
        val queue = _state.value.queue
        queueNeedsLoading = !queue.isEmpty
        _position.value = if (queue.isEmpty) 0L else player.currentPosition
        savePosition()
        crossfader.release()
        scope.launch(Dispatchers.Default) { effects.release() }
        player.removeListener(playerListener)
        player.release()
        exo = null
        _state.update { it.copy(isPlaying = false) }
    }

    private fun refreshTile() {
        try {
            TileService.requestListeningState(app, ComponentName(app, MusicTileService::class.java))
        } catch (_: Exception) {
        }
    }

    // ── Ticking ───────────────────────────────────────────────────────────────

    private fun startTicker() {
        lastTick = SystemClock.elapsedRealtime()
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(TickMs)
                val player = exo ?: break
                val now = SystemClock.elapsedRealtime()
                listens.listened(now - lastTick)
                lastTick = now
                _position.value = player.currentPosition
                val duration = player.duration
                if (duration > 0L && duration != _state.value.durationMs) {
                    _state.update { it.copy(durationMs = duration) }
                }
                // Past a second, this song clearly plays, so the run of failures is over.
                if (player.currentPosition > 1000L) failuresInARow = 0
                if (++ticksSinceSave >= SaveEveryTicks) {
                    ticksSinceSave = 0
                    savePosition()
                }
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
        val now = SystemClock.elapsedRealtime()
        if (lastTick > 0L) listens.listened(now - lastTick)
        lastTick = 0L
        flushListening()
        exo?.let { _position.value = it.currentPosition }
    }

    // ── Keeping the session ───────────────────────────────────────────────────

    private fun savePosition() {
        val state = _state.value
        if (state.queue.isEmpty) return
        session.positionMs = _position.value
        session.currentIndex = state.queue.index
    }

    private fun saveQueueIndex() {
        session.currentIndex = _state.value.queue.index
        session.positionMs = _position.value
    }

    private fun scheduleQueueSave() {
        saveQueueJob?.cancel()
        saveQueueJob = scope.launch {
            delay(QueueSaveDelayMs)
            val queue = _state.value.queue
            if (queue.isEmpty) return@launch
            val saved = SavedQueue(
                tracks = queue.entries.map { it.track },
                index = queue.index,
                unshuffledOrder = queue.unshuffled?.let { order ->
                    val positions = HashMap<Long, Int>(queue.entries.size)
                    queue.entries.forEachIndexed { i, entry -> positions[entry.uid] = i }
                    order.mapNotNull { positions[it] }
                }
            )
            savePosition()
            withContext(Dispatchers.IO) { session.writeQueue(saved) }
        }
    }

    private suspend fun restoreQueue() {
        data class Restored(val queue: PlayQueue, val positionMs: Long, val repeatMode: Int)
        val restored = withContext(Dispatchers.IO) {
            val saved = session.readQueue() ?: return@withContext null
            val index = session.currentIndex
            val positionMs = session.positionMs
            val repeat = session.repeatMode
            val all = saved.tracks.map { QueueEntry(nextUid.getAndIncrement(), it) }
            val existing = all.filter { File(it.track.path).exists() }
            if (existing.isEmpty()) return@withContext null
            val current = all.getOrNull(index)
            val wasKept = current != null && current in existing
            val newIndex = if (wasKept) existing.indexOf(current) else 0
            val order = saved.unshuffledOrder?.mapNotNull { all.getOrNull(it)?.uid }
                ?.filter { uid -> existing.any { it.uid == uid } }
            Restored(
                queue = PlayQueue(existing, newIndex, order),
                positionMs = if (wasKept) positionMs else 0L,
                repeatMode = repeat
            )
        } ?: return
        // Something may have started playing while the file was read. That wins.
        if (!_state.value.queue.isEmpty) return
        _state.update {
            it.copy(
                queue = restored.queue,
                repeatMode = restored.repeatMode,
                durationMs = restored.queue.current?.track?.durationMs ?: 0L
            )
        }
        _position.value = restored.positionMs
        queueNeedsLoading = true
        restored.queue.current?.let {
            currentUid = it.uid
            listens.begin(it.track.path, it.track.durationMs)
            loadDetails(it)
            loadSidecarLyrics(it.track)
        }
    }

    companion object {
        private const val TickMs = 250L
        private const val SaveEveryTicks = 20 // Every five seconds.
        private const val QueueSaveDelayMs = 1000L
        private const val FlushListeningMs = 15_000L
        private const val LoadAtOnceLimit = 200
        private const val LoadSliceSize = 250
        private const val LoadSliceGapMs = 16L
        private const val SleepFadeMs = 20_000L
        private val AlbumArtRoot: Uri = Uri.parse("content://media/external/audio/albumart")

        @Volatile
        private var instance: MusicPlayer? = null

        fun get(context: Context): MusicPlayer =
            instance ?: synchronized(this) {
                instance ?: MusicPlayer(context.applicationContext).also { instance = it }
            }

        internal fun uriOf(path: String): Uri = when {
            path.startsWith("content://") -> Uri.parse(path)
            else -> Uri.fromFile(File(path))
        }
    }
}

private class TrackDetails(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val artwork: ByteArray?
)

private val CoverFileNames = listOf(
    "cover.jpg", "cover.png", "folder.jpg", "folder.png", "album.jpg", "album.png",
    "front.jpg", "front.png", "albumart.jpg", "albumart.png"
)

/** The biggest cover file worth holding in memory. Anything larger is a scan, not a cover. */
private const val MaxCoverFileBytes = 20L * 1024 * 1024

/** A cover image beside the music, for files with none of their own. */
internal fun findCoverFile(folder: File): File? {
    val names = folder.list() ?: return null
    val byLowerName = names.associateBy { it.lowercase() }
    return CoverFileNames.firstNotNullOfOrNull { wanted ->
        byLowerName[wanted]?.let { File(folder, it) }
    }?.takeIf { it.length() in 1..MaxCoverFileBytes }
}

private fun readDetails(path: String): TrackDetails {
    val retriever = MediaMetadataRetriever()
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var duration = 0L
    var artwork: ByteArray? = null
    try {
        retriever.setDataSource(path)
        title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        artwork = retriever.embeddedPicture
    } catch (_: Exception) {
        // Unreadable tags don't stop a file from playing, and a bad file reports itself when it plays.
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
    if (artwork == null) {
        artwork = try {
            File(path).parentFile?.let { findCoverFile(it) }?.readBytes()
        } catch (_: Exception) {
            null
        }
    }
    return TrackDetails(title, artist, album, duration, artwork)
}

/** Lyrics in a file next to the song, sharing its name, as `.lrc` or plain `.txt`. */
internal fun readSidecarLyrics(path: String): Lyrics? {
    val song = File(path)
    val folder = song.parentFile ?: return null
    val base = song.nameWithoutExtension
    val names = folder.list() ?: return null
    fun find(extension: String) = names.firstOrNull { it.equals("$base.$extension", ignoreCase = true) }
    val name = find("lrc") ?: find("txt") ?: return null
    return try {
        val file = File(folder, name)
        // Lyrics are a few kilobytes. Something far bigger with the same name is not lyrics.
        if (file.length() > 1024 * 1024) return null
        LrcParser.parse(decodeLyricBytes(file.readBytes())).takeIf { it.lines.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}

private val LyricTextKeys = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS", "SYNCED LYRICS")

/** Whatever lyrics the file's own tags hold, across the ways ID3, Vorbis comments and MP4 store them. */
private fun embeddedLyricsIn(tracks: Tracks): Lyrics? {
    var synced: Lyrics? = null
    var plain: String? = null
    for (group in tracks.groups) {
        for (i in 0 until group.length) {
            val metadata = group.getTrackFormat(i).metadata ?: continue
            for (e in 0 until metadata.length()) {
                when (val entry = metadata.get(e)) {
                    is BinaryFrame -> when (entry.id) {
                        "SYLT" -> synced = synced ?: Id3Lyrics.decodeSylt(entry.data)
                        "USLT" -> plain = plain ?: Id3Lyrics.decodeUslt(entry.data)
                    }
                    is TextInformationFrame -> {
                        val isLyrics = entry.id == "USLT" ||
                            (entry.id == "TXXX" && entry.description?.uppercase() in LyricTextKeys)
                        if (isLyrics) plain = plain ?: entry.values.firstOrNull()
                    }
                    is VorbisComment -> {
                        if (entry.key.uppercase() in LyricTextKeys) plain = plain ?: entry.value
                    }
                }
            }
        }
    }
    // Tags often carry a whole LRC file as their text, so plain text may turn out synced after all.
    val parsed = plain?.let { LrcParser.parse(it) }?.takeIf { it.lines.isNotEmpty() }
    return when {
        synced != null -> synced
        else -> parsed
    }
}
