package com.kobblo.gallery.file.explorer.viewer.video

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.DefaultTrackNameProvider
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import com.kobblo.gallery.file.explorer.viewer.metadata.CodecNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Why a video isn't playing, in terms the page can explain. */
sealed interface VideoProblem {
    data object FileMissing : VideoProblem
    data object NoAccess : VideoProblem

    /** The container or codec has nothing on this device that can read it. */
    data class Unsupported(val format: String) : VideoProblem

    data object Damaged : VideoProblem
}

/** One entry in the audio or subtitle picker. */
class TrackChoice internal constructor(
    val label: String,
    val isSelected: Boolean,
    internal val group: TrackGroup,
    internal val index: Int
)

/**
 * The viewer's single player and everything the page and its controls read about it. Exactly one video
 * plays at a time, the one on the visible page, so this outlives pages but not the viewer. Its
 * settings carry from one video to the next, as they do in any player.
 */
@OptIn(UnstableApi::class)
@Stable
class VideoSession(context: Context) {
    private val appContext = context.applicationContext
    private val resume = VideoResumeStore(appContext)
    private var mediaSession: MediaSession? = null

    var player: ExoPlayer? by mutableStateOf(null)
        private set
    var file: FileItem? by mutableStateOf(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isEnded by mutableStateOf(false)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set

    /** Width over height, already allowing for rotation and non-square pixels. Zero until known. */
    var videoAspect by mutableFloatStateOf(0f)
        private set
    var firstFrameShown by mutableStateOf(false)
        private set
    var problem by mutableStateOf<VideoProblem?>(null)
        private set
    var isAudioOnly by mutableStateOf(false)
        private set
    var cues by mutableStateOf<List<Cue>>(emptyList())
        private set
    var subtitleChoices by mutableStateOf<List<TrackChoice>>(emptyList())
        private set
    var audioChoices by mutableStateOf<List<TrackChoice>>(emptyList())
        private set
    var subtitlesOff by mutableStateOf(false)
        private set

    var isMuted by mutableStateOf(false)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var isLooping by mutableStateOf(false)
        private set
    var scale by mutableStateOf(VideoScale.Fit)

    /** Held down for double speed. It's on top of the chosen speed, so letting go goes back to that. */
    var isHoldingSpeed by mutableStateOf(false)
        private set

    /** Where this video picked up from, for as long as that's worth offering to undo. Zero when it isn't. */
    var resumedAtMs by mutableLongStateOf(0L)
        private set

    /** No gesture or control does anything, so that a pocket or a child can't change what's playing. */
    var isLocked by mutableStateOf(false)

    /** Goes on playing when the screen is off or the app is left, because someone chose that for this viewing. */
    var keepsPlayingInBackground by mutableStateOf(false)
        private set

    /** Bumped when the Rotation setting changes, so that what's on screen follows it at once. */
    var rotationRevision by mutableIntStateOf(0)

    var subtitleSize by mutableStateOf(VideoPrefs.subtitleSize(appContext))
        private set
    var subtitlePosition by mutableStateOf(VideoPrefs.subtitlePosition(appContext))
        private set

    val hasPlayer: Boolean get() = player != null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val active = player ?: return
            if (playbackState == Player.STATE_READY) durationMs = active.duration.coerceAtLeast(0L)
            isEnded = playbackState == Player.STATE_ENDED
            if (isEnded) file?.let { resume.clear(it.path) }
            refreshPosition()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            refreshPosition()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onRenderedFirstFrame() {
            firstFrameShown = true
        }

        override fun onCues(cueGroup: CueGroup) {
            cues = cueGroup.cues
        }

        override fun onTracksChanged(tracks: Tracks) {
            readTracks(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            problem = describe(error)
        }
    }

    /** Starts [target], picking up where it was left. Reads the disk, so call it off the main thread's hot path. */
    suspend fun open(target: FileItem) {
        if (file?.path == target.path && player != null) return
        val setup = withContext(Dispatchers.IO) {
            val isStream = target.path.startsWith("content://")
            Setup(
                uri = if (isStream) Uri.parse(target.path) else Uri.fromFile(File(target.path)),
                subtitles = if (isStream) emptyList() else findSidecars(target),
                resumeAt = resume.get(target.path)
            )
        }
        saveProgress()
        resetFor(target)
        val active = player ?: createPlayer().also { player = it }
        current = setup
        active.setMediaItem(mediaItem(setup, target), setup.resumeAt)
        resumedAtMs = setup.resumeAt
        active.prepare()
        active.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        active.volume = if (isMuted) 0f else 1f
        active.playbackParameters = PlaybackParameters(speed)
        active.playWhenReady = true
    }

    private fun mediaItem(setup: Setup, target: FileItem): MediaItem = MediaItem.Builder()
        .setUri(setup.uri)
        .setMimeType(containerMime(target))
        .setSubtitleConfigurations(setup.subtitles)
        .build()

    /** Back to the beginning, for when picking up where it was left wasn't what was wanted. */
    fun startOver() {
        val active = player ?: return
        resumedAtMs = 0L
        active.seekTo(0L)
        active.play()
        refreshPosition()
    }

    fun dismissResumeOffer() {
        resumedAtMs = 0L
    }

    /** Double speed for as long as a finger is down. It leaves the speed setting as it was. */
    fun holdSpeed(held: Boolean) {
        if (isHoldingSpeed == held) return
        isHoldingSpeed = held
        player?.playbackParameters = PlaybackParameters(if (held) HoldSpeed else speed)
    }

    fun applySubtitleSize(size: SubtitleSize) {
        subtitleSize = size
        VideoPrefs.setSubtitleSize(appContext, size)
    }

    fun applySubtitlePosition(position: SubtitlePosition) {
        subtitlePosition = position
        VideoPrefs.setSubtitlePosition(appContext, position)
    }

    /**
     * Adds a subtitle file someone picked. The player only takes these when the video is set up, so it's set up again
     * at the same spot, with the new one switched on. False if it isn't a kind of file the player can read.
     */
    suspend fun addSubtitle(uri: Uri): Boolean {
        val target = file ?: return false
        val setup = current ?: return false
        val picked = withContext(Dispatchers.IO) { SubtitleFiles.copyPicked(appContext, uri) } ?: return false
        val added = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(picked.file))
            .setMimeType(picked.mimeType)
            .setLabel(picked.label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val others = setup.subtitles.map { it.buildUpon().setSelectionFlags(0).build() }
        val updated = Setup(setup.uri, others + added, setup.resumeAt)
        current = updated
        val active = player ?: return false
        val at = active.currentPosition
        active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        active.setMediaItem(mediaItem(updated, target), at)
        active.prepare()
        active.play()
        return true
    }

    /**
     * Turns background playing on or off. A foreground service is what keeps the system from killing a process with
     * no screen, and it says so with a notification, as anything playing out of sight has to.
     */
    fun chooseBackgroundPlay(enabled: Boolean) {
        keepsPlayingInBackground = enabled
        if (enabled) {
            VideoAudioService.onStopRequested = {
                keepsPlayingInBackground = false
                VideoAudioService.onStopRequested = null
                pause()
            }
            VideoAudioService.start(appContext, file?.name.orEmpty())
        } else {
            VideoAudioService.onStopRequested = null
            VideoAudioService.stop(appContext)
        }
    }

    /** The screen went off or the app was left. */
    fun onLeftScreen() {
        val active = player
        if (keepsPlayingInBackground && active != null && active.isPlaying) {
            // No one's watching, so no point spending a decoder's battery on pictures.
            active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        } else {
            pause()
        }
    }

    fun onBackOnScreen() {
        val active = player ?: return
        if (active.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)) {
            active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
        }
    }

    /** Stops playing and gives up the player, which only the visible page may hold. */
    fun close() {
        saveProgress()
        if (keepsPlayingInBackground) chooseBackgroundPlay(false)
        isLocked = false
        val closing = player
        player = null
        mediaSession?.release()
        mediaSession = null
        closing?.removeListener(listener)
        closing?.release()
        resetFor(null)
    }

    fun togglePlay() {
        val active = player ?: return
        if (isEnded) {
            active.seekTo(0L)
            active.play()
        } else if (active.isPlaying) {
            pause()
        } else {
            active.play()
        }
    }

    /** Plays from wherever it is, or from the start if it had already finished. */
    fun play() {
        val active = player ?: return
        if (isEnded) {
            isEnded = false
            active.seekTo(0L)
        }
        active.play()
    }

    fun pause() {
        player?.pause()
        saveProgress()
    }

    /** Where it is right now, for a gesture to start from, rather than the last position read for display. */
    fun currentPositionNow(): Long = player?.currentPosition ?: 0L

    /** Reads where the player is. The controls call this as it plays, since nothing else announces it. */
    fun refreshPosition() {
        positionMs = player?.currentPosition ?: 0L
    }

    fun seekBy(deltaMs: Long) {
        val active = player ?: return
        val end = durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        active.seekTo((active.currentPosition + deltaMs).coerceIn(0L, end))
        refreshPosition()
    }

    /** Seeking to keyframes keeps up with a dragging finger, and [finishScrub] lands exactly. */
    fun scrubTo(positionMs: Long) {
        val active = player ?: return
        active.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        active.seekTo(positionMs)
    }

    fun finishScrub(positionMs: Long) {
        val active = player ?: return
        active.setSeekParameters(SeekParameters.EXACT)
        active.seekTo(positionMs)
        refreshPosition()
    }

    fun toggleMute() {
        isMuted = !isMuted
        player?.volume = if (isMuted) 0f else 1f
    }

    fun applySpeed(value: Float) {
        speed = value
        if (!isHoldingSpeed) player?.playbackParameters = PlaybackParameters(value)
    }

    fun toggleLoop() {
        isLooping = !isLooping
        player?.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun selectTrack(choice: TrackChoice, trackType: Int) {
        val active = player ?: return
        active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(choice.group, choice.index))
            .build()
    }

    fun turnSubtitlesOff() {
        val active = player ?: return
        active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        cues = emptyList()
    }

    private fun createPlayer(): ExoPlayer {
        // Constant-bitrate seeking is what lets files with no seek index, like AVI, seek at all.
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val created = ExoPlayer.Builder(
            appContext,
            DefaultRenderersFactory(appContext).setEnableDecoderFallback(true),
            DefaultMediaSourceFactory(BdavDataSource.Factory(appContext), extractors)
        )
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setSeekBackIncrementMs(SeekStepMs)
            .setSeekForwardIncrementMs(SeekStepMs)
            .build()
        created.addListener(listener)
        // This is what gives the Picture-in-Picture window its play and pause buttons. Ids must be
        // unique, and a viewer on its way out can still hold its own for a moment.
        mediaSession = MediaSession.Builder(appContext, created).setId("viewer-video-${System.identityHashCode(created)}").build()
        return created
    }

    private fun resetFor(target: FileItem?) {
        file = target
        isPlaying = false
        isEnded = false
        durationMs = 0L
        positionMs = 0L
        videoAspect = 0f
        firstFrameShown = false
        problem = null
        isAudioOnly = false
        cues = emptyList()
        subtitleChoices = emptyList()
        audioChoices = emptyList()
        resumedAtMs = 0L
        isHoldingSpeed = false
        current = null
    }

    private fun saveProgress() {
        val active = player ?: return
        val current = file ?: return
        if (problem != null) return
        resume.put(current.path, active.currentPosition, active.duration)
    }

    private fun readTracks(tracks: Tracks) {
        val names = DefaultTrackNameProvider(appContext.resources)
        val subtitles = ArrayList<TrackChoice>()
        val audio = ArrayList<TrackChoice>()
        var hasVideo = false
        var videoPlays = false
        var videoFormat: String? = null
        for (group in tracks.groups) {
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    hasVideo = true
                    if (group.isSupported) videoPlays = true
                    if (videoFormat == null) videoFormat = group.getTrackFormat(0).sampleMimeType
                }
                C.TRACK_TYPE_TEXT, C.TRACK_TYPE_AUDIO -> for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    val choice = TrackChoice(names.getTrackName(group.getTrackFormat(i)), group.isTrackSelected(i), group.mediaTrackGroup, i)
                    if (group.type == C.TRACK_TYPE_TEXT) subtitles.add(choice) else audio.add(choice)
                }
            }
        }
        subtitleChoices = subtitles
        audioChoices = audio
        subtitlesOff = subtitles.none { it.isSelected }
        // Without this the sound plays over a black screen and looks like a broken player.
        if (hasVideo && !videoPlays) {
            problem = VideoProblem.Unsupported(CodecNames.label(videoFormat) ?: extensionLabel())
            player?.pause()
        }
        isAudioOnly = !hasVideo && audio.isNotEmpty()
    }

    private fun describe(error: PlaybackException): VideoProblem = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> VideoProblem.FileMissing
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> VideoProblem.NoAccess
        // Nothing recognized it. For a format the player does read, that means this file is bad,
        // and blaming the device would send someone hunting for a codec that wouldn't help.
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            if (error.cause is UnrecognizedInputFormatException && extensionIsReadable()) VideoProblem.Damaged
            else VideoProblem.Unsupported(extensionLabel())
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> VideoProblem.Unsupported(extensionLabel())
        // FLV carrying something other than H.264 fails while parsing, with a message naming it.
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            if (error.cause?.javaClass?.simpleName == "UnsupportedFormatException") VideoProblem.Unsupported(extensionLabel())
            else VideoProblem.Damaged
        else -> if (error.cause is SecurityException) VideoProblem.NoAccess else VideoProblem.Damaged
    }

    private fun extensionIsReadable(): Boolean =
        file?.name?.let(FileKinds::extensionOf) in setOf("mp4", "m4v", "mov", "3gp", "3g2", "f4v", "mkv", "webm", "avi", "ts", "m2ts", "mts", "flv", "ogv")

    private fun extensionLabel(): String =
        file?.name?.let(FileKinds::extensionOf)?.uppercase().orEmpty().ifEmpty { appContext.getString(R.string.media_video_this_format) }

    /** Saying what the name implies spares the player guessing, and helps a file with a poor header. */
    private fun containerMime(target: FileItem): String? = when (FileKinds.extensionOf(target.name)) {
        "ts", "m2ts", "mts" -> MimeTypes.VIDEO_MP2T
        "mkv" -> MimeTypes.VIDEO_MATROSKA
        "webm" -> MimeTypes.VIDEO_WEBM
        "avi" -> MimeTypes.VIDEO_AVI
        else -> null
    }

    private fun findSidecars(target: FileItem): List<MediaItem.SubtitleConfiguration> = try {
        val video = File(target.path)
        val names = video.parentFile?.list()?.asList().orEmpty()
        SidecarSubtitles.match(video.name, names).mapIndexed { index, sidecar ->
            val source = File(video.parentFile, sidecar.fileName)
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(SubtitleFiles.readable(appContext, source, sidecar.language)))
                .setMimeType(sidecar.mimeType)
                .setLanguage(sidecar.language)
                .setLabel(SidecarSubtitles.labelFor(sidecar, java.util.Locale.getDefault()))
                // Only the first is switched on, or the picker would start out with a clash.
                .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private class Setup(val uri: Uri, val subtitles: List<MediaItem.SubtitleConfiguration>, val resumeAt: Long)

    private var current: Setup? = null

    companion object {
        const val SeekStepMs = 10_000L
        const val HoldSpeed = 2f
    }
}
