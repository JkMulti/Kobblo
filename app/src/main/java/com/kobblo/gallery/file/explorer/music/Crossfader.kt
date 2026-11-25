package com.kobblo.gallery.file.explorer.music

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The decisions behind a crossfade, apart from any player. */
internal object CrossfadeRules {
    /**
     * Where in the current song the fade should start, or null if this song ends as it always did. Repeat One
     * loops, the last song has nothing to fade into, and a song too short for two fades would be nothing but
     * fading. A next song of unknown length is given the benefit of the doubt: its own end will sort itself out.
     * Everything is in the song's own time, so at double speed a five second fade takes ten seconds of it.
     */
    fun tailStartMs(currentMs: Long, nextMs: Long, fadeMs: Long, speed: Float, repeatMode: Int, hasNext: Boolean): Long? {
        if (fadeMs <= 0L || currentMs <= 0L || !hasNext || repeatMode == Player.REPEAT_MODE_ONE) return null
        val songFadeMs = (fadeMs * speed).toLong()
        if (currentMs < 2 * songFadeMs) return null
        if (nextMs > 0L && nextMs < 2 * songFadeMs) return null
        return currentMs - songFadeMs
    }

    /** Equal power, so the two together stay about as loud as one song instead of dipping in the middle. */
    fun fadingOut(progress: Float): Float = cos(progress.coerceIn(0f, 1f) * PI / 2).toFloat()
    fun fadingIn(progress: Float): Float = sin(progress.coerceIn(0f, 1f) * PI / 2).toFloat()
}

/**
 * Overlaps the end of one song with the start of the next. The session's player stays the only one anything else
 * ever sees, and at the start of the fade it simply moves on to the next song, so the queue, the notification and
 * Now Playing all stay true without knowing about this. A helper with no session plays what's left of the old
 * song beside it, and is thrown away when that's done. It shares the audio session, so the equalizer covers both.
 *
 * Nearly everything can fail safe: the helper is set up early with the main player untouched, and if it isn't
 * ready in time the song just runs out and into the next one, as it does with this off. With it off, nothing here
 * runs and the main player's volume isn't touched.
 */
@OptIn(UnstableApi::class)
internal class Crossfader(
    private val scope: CoroutineScope,
    private val mainPlayer: () -> ExoPlayer?,
    private val buildHelper: () -> ExoPlayer,
    private val lengthOfItem: (Int) -> Long,
    private val queueSettled: () -> Boolean,
    private val outerGain: () -> Float,
    private val setMainGain: (Float) -> Unit
) {
    private var lengthMs = 0L
    private var helper: ExoPlayer? = null
    private var watch: Job? = null
    private var fade: Job? = null
    private var armedFor: String? = null
    private var armedAt = 0L

    /** A song whose starting point went by without a fade, such as by seeking past it. */
    private var passedBy: String? = null
    private var ownSeek = false
    private var tailGain = 0f
    private var mainGain = 1f

    private val isFading: Boolean get() = fade?.isActive == true

    fun setLength(seconds: Int) {
        val next = seconds * 1000L
        if (next == lengthMs) return
        lengthMs = next
        if (next == 0L) {
            stopWatching()
            if (isFading) cutShort() else releaseHelper()
        } else {
            disarm()
            if (mainPlayer()?.isPlaying == true) startWatching()
        }
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        if (lengthMs == 0L) return
        if (isPlaying) startWatching() else stopWatching()
    }

    private fun startWatching() {
        if (watch?.isActive == true) return
        watch = scope.launch {
            while (isActive) delay(step())
        }
    }

    private fun stopWatching() {
        watch?.cancel()
        watch = null
    }

    /** Looks at where the song has got to, and says how long until it's worth looking again. */
    private fun step(): Long {
        val player = mainPlayer() ?: return LookEveryMs
        if (isFading) return LookEveryMs
        val song = player.currentMediaItem?.mediaId ?: return LookEveryMs
        val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
        val start = if (queueSettled() && player.isCurrentMediaItemSeekable) {
            CrossfadeRules.tailStartMs(
                currentMs = player.duration,
                nextMs = lengthOfItem(player.nextMediaItemIndex),
                fadeMs = lengthMs,
                speed = speed,
                repeatMode = player.repeatMode,
                hasNext = player.hasNextMediaItem()
            )
        } else {
            null
        }
        if (start == null) {
            disarm()
            return LookEveryMs
        }
        val untilStart = ((start - player.currentPosition) / speed).toLong()
        if (untilStart > ArmLeadMs) {
            passedBy = null
            disarm()
            return min(LookEveryMs, untilStart - ArmLeadMs)
        }
        if (passedBy == song) return LookEveryMs
        if (untilStart < -TooLateMs) {
            passedBy = song
            disarm()
            Log.d(Tag, "no fade for this song: past its starting point")
            return LookEveryMs
        }
        if (armedFor != song || armedAt != start) arm(player, song, start, speed)
        if (untilStart > 0L) return untilStart.coerceIn(MinLookMs, LookEveryMs)
        val tail = helper
        if (tail == null || tail.playbackState != Player.STATE_READY) return MinLookMs
        handOver(player, tail)
        return LookEveryMs
    }

    /** Opens the old song in the helper ahead of time, waiting silently at the point it will take over. */
    private fun arm(player: ExoPlayer, song: String, start: Long, speed: Float) {
        val item = player.currentMediaItem ?: return
        val tail = helper ?: buildHelper().also { helper = it }
        if (tail.audioSessionId != player.audioSessionId) tail.audioSessionId = player.audioSessionId
        tail.playbackParameters = player.playbackParameters
        tail.skipSilenceEnabled = player.skipSilenceEnabled
        tail.playWhenReady = false
        setTailGain(0f)
        // Audio takes a moment to come out once started, so it waits slightly ahead to come in on time.
        tail.setMediaItem(item, start + (StartLagMs * speed).toLong())
        tail.prepare()
        armedFor = song
        armedAt = start
        Log.d(Tag, "armed")
    }

    private fun disarm() {
        if (armedFor == null) return
        armedFor = null
        clearHelper()
    }

    private fun clearHelper() {
        val tail = helper ?: return
        tail.playWhenReady = false
        tail.stop()
        tail.clearMediaItems()
    }

    private fun handOver(player: ExoPlayer, tail: ExoPlayer) {
        armedFor = null
        Log.d(Tag, "fading")
        val fadeMs = lengthMs
        fade = scope.launch {
            tail.play()
            // For a moment both are playing the same music, a hair apart, so the change is blended rather than cut.
            ramp(BlendMs) { progress ->
                setTailGain(progress)
                setMain(1f - progress)
            }
            if (!player.hasNextMediaItem()) {
                settle()
                return@launch
            }
            ownSeek = true
            player.seekToNextMediaItem()
            // Runs on the clock rather than the helper's position, so a stalled helper can't hold the volume down.
            var elapsed = 0L
            var last = SystemClock.elapsedRealtime()
            while (elapsed < fadeMs) {
                val running = mainWantsToPlay()
                delay(if (running) StepMs else PausedStepMs)
                val now = SystemClock.elapsedRealtime()
                if (running && mainWantsToPlay()) elapsed += now - last
                last = now
                val progress = elapsed.toFloat() / fadeMs
                setTailGain(CrossfadeRules.fadingOut(progress))
                setMain(CrossfadeRules.fadingIn(progress))
                if (tail.playbackState == Player.STATE_ENDED || tail.playbackState == Player.STATE_IDLE) break
            }
            settle()
        }
    }

    /** Whatever's left of the old song goes, and the new one comes up to full. */
    private suspend fun settle() {
        val fromTail = tailGain
        val fromMain = mainGain
        ramp(SettleMs) { progress ->
            setTailGain(fromTail * (1f - progress))
            setMain(fromMain + (1f - fromMain) * progress)
        }
        clearHelper()
        if (lengthMs == 0L) releaseHelper()
        Log.d(Tag, "settled")
    }

    private suspend fun ramp(ms: Long, apply: (Float) -> Unit) {
        val began = SystemClock.elapsedRealtime()
        while (true) {
            val progress = ((SystemClock.elapsedRealtime() - began).toFloat() / ms).coerceIn(0f, 1f)
            apply(progress)
            if (progress >= 1f) return
            delay(StepMs)
        }
    }

    private fun setTailGain(gain: Float) {
        tailGain = gain
        helper?.volume = gain * outerGain()
    }

    private fun setMain(gain: Float) {
        mainGain = gain
        setMainGain(gain)
    }

    private fun mainWantsToPlay(): Boolean {
        val player = mainPlayer() ?: return false
        return player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
    }

    /** A pause, or a call taking the audio focus, stops both. The fade carries on from where it was afterwards. */
    fun followMain() {
        if (!isFading) return
        helper?.playWhenReady = mainWantsToPlay()
    }

    /** The main player was moved. The move of its own is expected, and any other means someone wanted something else. */
    fun onMoved(isSeek: Boolean) {
        if (isSeek && ownSeek) {
            ownSeek = false
            return
        }
        cutShort()
    }

    /**
     * Ends a fade within a fraction of a second, for when it no longer applies. Posted, not run here, because it
     * changes the main player's volume, and this is often called from inside one of its callbacks.
     */
    fun cutShort() {
        if (!isFading) return
        fade?.cancel()
        fade = scope.launch(Dispatchers.Main) { settle() }
    }

    fun followSound(player: ExoPlayer) {
        val tail = helper ?: return
        tail.playbackParameters = player.playbackParameters
        tail.skipSilenceEnabled = player.skipSilenceEnabled
    }

    /** The outer volume changed, which only matters while the helper is playing. */
    fun refreshGain() {
        if (isFading) helper?.volume = tailGain * outerGain()
    }

    /** At once, with no ramp, for when playback is ending altogether. */
    fun stopNow() {
        fade?.cancel()
        fade = null
        stopWatching()
        armedFor = null
        passedBy = null
        ownSeek = false
        tailGain = 0f
        if (mainGain != 1f) setMain(1f)
        clearHelper()
    }

    /** Also lets the helper go, for when the main player is going too. */
    fun release() {
        stopNow()
        releaseHelper()
    }

    private fun releaseHelper() {
        armedFor = null
        helper?.release()
        helper = null
    }

    private companion object {
        const val Tag = "Crossfade"
        const val LookEveryMs = 250L
        const val MinLookMs = 5L
        const val ArmLeadMs = 3_000L
        const val TooLateMs = 300L
        const val StartLagMs = 80L
        const val BlendMs = 120L
        const val SettleMs = 150L
        const val StepMs = 30L
        const val PausedStepMs = 250L
    }
}
