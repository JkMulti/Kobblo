package com.kobblo.gallery.file.explorer.viewer.video

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.viewer.findActivity
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxSize

/** What a gesture's changing, for as long as the indicator shows it. */
internal sealed interface VideoHud {
    class Level(val icon: ImageVector, val value: Float) : VideoHud
    class Seek(val targetMs: Long, val deltaMs: Long) : VideoHud
    class Speed(val speed: Float) : VideoHud
    class Scale(val scale: VideoScale) : VideoHud
}

/**
 * The window and the sound levels that gestures change. Brightness only ever touches this window, so the phone's own
 * setting is left alone and everything goes back as it was when the viewer closes.
 */
@Stable
internal class VideoGestureControls(context: Context, private val activity: Activity?) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val resolver = context.contentResolver

    var hud by mutableStateOf<VideoHud?>(null)
    private var hudTicket = 0

    /** Bumped when a gesture ends. A gesture handler has nothing to fade the indicator with, but the panel does. */
    var fadeRequest by mutableIntStateOf(0)
        private set

    fun requestFade() {
        fadeRequest++
    }

    fun show(next: VideoHud) {
        hudTicket++
        hud = next
    }

    /** Leaves the indicator up for a moment after the finger lifts, so the final value can be read. */
    suspend fun fadeHud() {
        val ticket = hudTicket
        delay(HudLingerMs)
        if (ticket == hudTicket) hud = null
    }

    val maxVolume: Int get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    fun volumeLevel(): Float = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume

    fun setVolumeLevel(level: Float) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, VideoGestureMath.volumeIndex(level, maxVolume), 0)
        } catch (_: SecurityException) {
            // Do Not Disturb can refuse this. The indicator just won't have moved anything.
        }
    }

    fun brightnessLevel(): Float {
        val own = activity?.window?.attributes?.screenBrightness ?: -1f
        if (own >= 0f) return own
        return try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Settings.SettingNotFoundException) {
            0.5f
        }
    }

    fun setBrightnessLevel(level: Float) {
        val window = activity?.window ?: return
        val attributes = window.attributes
        attributes.screenBrightness = VideoGestureMath.brightnessForWindow(level)
        window.attributes = attributes
    }

    fun restoreBrightness() {
        val window = activity?.window ?: return
        val attributes = window.attributes
        if (attributes.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) return
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attributes
    }

    private companion object {
        const val HudLingerMs = 700L
    }
}

@Composable
internal fun rememberVideoGestureControls(): VideoGestureControls {
    val context = LocalContext.current
    val controls = remember(context) { VideoGestureControls(context, context.findActivity()) }
    DisposableEffect(controls) {
        onDispose { controls.restoreBrightness() }
    }
    return controls
}

/**
 * The player's gestures on the page. With the controls up, sideways and vertical drags stay with the viewer, for the
 * next video and for pulling it away. With them hidden, someone is watching, so a drag up or down at the sides is
 * brightness and volume, and one sideways seeks. Touches starting at an edge are left alone for the system's own
 * gestures. Pinching works either way, as nothing else uses two fingers on a video.
 */
@OptIn(UnstableApi::class)
internal fun Modifier.videoGestures(
    session: VideoSession,
    controls: VideoGestureControls,
    isWatching: () -> Boolean,
    onSeekGestureEnd: () -> Unit = {}
): Modifier = pointerInput(session, controls) {
    val sideMargin = SafeSide.toPx()
    val topMargin = SafeTop.toPx()
    val bottomMargin = SafeBottom.toPx()
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!session.hasPlayer || session.problem != null) return@awaitEachGesture
        // Decided here, so that controls fading out halfway through don't turn someone's swipe into something else.
        val watching = isWatching()
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val fromEdge = VideoGestureMath.isInSafeZone(down.position.x, down.position.y, width, height, sideMargin, topMargin, bottomMargin)
        var drag: VideoDrag? = null
        var travelled = Offset.Zero
        var startLevel = 0f
        val startPosition = session.currentPositionNow()
        var seekTarget = startPosition
        var startSpan = 0f
        var scaleChanged = false
        do {
            val event = awaitPointerEvent()
            val fingers = event.changes.count { it.pressed }
            if (fingers >= 2) {
                val span = event.calculateCentroidSize()
                if (startSpan <= 0f) {
                    startSpan = span
                } else if (!scaleChanged && span > 0f) {
                    val next = VideoGestureMath.scaleForPinch(session.scale, span / startSpan)
                    if (next != session.scale) {
                        session.scale = next
                        scaleChanged = true
                        controls.show(VideoHud.Scale(next))
                    }
                }
                event.changes.forEach { it.consume() }
                continue
            }
            if (!watching || fromEdge || fingers == 0 || startSpan > 0f) continue
            val change = event.changes.first { it.pressed }
            travelled += change.positionChange()
            if (drag == null) {
                if (travelled.getDistance() < slop) continue
                val decided = VideoGestureMath.classify(down.position.x, width, travelled.x, travelled.y)
                drag = decided
                when (decided) {
                    VideoDrag.Brightness -> startLevel = controls.brightnessLevel()
                    VideoDrag.Volume -> startLevel = controls.volumeLevel()
                    else -> {}
                }
            }
            when (drag) {
                VideoDrag.Seek -> {
                    val delta = VideoGestureMath.seekDelta(travelled.x, width, session.durationMs)
                    seekTarget = VideoGestureMath.seekTarget(startPosition, delta, session.durationMs)
                    session.scrubTo(seekTarget)
                    controls.show(VideoHud.Seek(seekTarget, seekTarget - startPosition))
                }
                VideoDrag.Brightness -> {
                    val level = VideoGestureMath.levelAfterDrag(startLevel, -travelled.y, height)
                    controls.setBrightnessLevel(level)
                    controls.show(VideoHud.Level(AppIcons.Sun, level))
                }
                VideoDrag.Volume -> {
                    val level = VideoGestureMath.levelAfterDrag(startLevel, -travelled.y, height)
                    controls.setVolumeLevel(level)
                    controls.show(VideoHud.Level(if (level <= 0f) AppIcons.VolumeOff else AppIcons.VolumeHigh, level))
                }
                else -> {}
            }
            if (drag != VideoDrag.None) change.consume()
        } while (event.changes.any { it.pressed })
        if (drag == VideoDrag.Seek) {
            session.finishScrub(seekTarget)
            onSeekGestureEnd()
        }
        if (drag == VideoDrag.Brightness || drag == VideoDrag.Volume || drag == VideoDrag.Seek || scaleChanged) {
            controls.requestFade()
        }
    }
}

private val SafeSide = 32.dp
private val SafeTop = 56.dp
private val SafeBottom = 56.dp

/** The small panel in the middle of the picture that says what a gesture is doing. */
@Composable
internal fun VideoHudPanel(controls: VideoGestureControls, modifier: Modifier = Modifier) {
    val hud = controls.hud
    LaunchedEffect(controls.fadeRequest) {
        if (controls.fadeRequest > 0) controls.fadeHud()
    }
    AnimatedVisibility(
        visible = hud != null,
        enter = fadeIn(KobbloMotion.fadeIn()),
        exit = fadeOut(KobbloMotion.fadeOut()),
        modifier = modifier
    ) {
        // The last value stays while it fades, rather than going blank.
        var shown by remember { mutableStateOf(hud) }
        if (hud != null) shown = hud
        when (val value = shown) {
            is VideoHud.Level -> HudPill(gap = 12.dp) {
                Icon(value.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(5.dp)
                        .clip(CapsuleShape)
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(value.value.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Color.White, CapsuleShape)
                    )
                }
            }
            is VideoHud.Seek -> HudText(formatVideoTime(value.targetMs) + "   " + VideoGestureMath.signedTime(value.deltaMs))
            is VideoHud.Speed -> HudPill {
                Icon(AppIcons.FastForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.viewer_video_speed_value, value.speed.toInt().toString()),
                    style = KobbloType.callout.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White
                )
            }
            is VideoHud.Scale -> HudText(stringResource(value.scale.labelRes()))
            null -> {}
        }
    }
}

@Composable
private fun HudText(text: String) {
    HudPill {
        Text(
            text,
            style = KobbloType.callout.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = Color.White
        )
    }
}

/**
 * The capsule everything laid over a playing video is written in: what a gesture is doing, and the way
 * out of a locked player. It is dark enough to read on any picture unless given another [fill], and
 * [gap] is the room between the things in it.
 */
@Composable
private fun HudPill(
    modifier: Modifier = Modifier,
    fill: Color = Color.Black.copy(alpha = HudAlpha),
    gap: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .background(fill, CapsuleShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * Takes every touch that lands here, so nothing underneath sees it, from the first finger down until
 * the last one lifts. [onDown] and [onUp] mark those two moments.
 */
private suspend fun PointerInputScope.swallowTouches(onDown: () -> Unit, onUp: () -> Unit = {}) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        onDown()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        onUp()
    }
}

internal fun VideoScale.labelRes(): Int = when (this) {
    VideoScale.Fit -> R.string.viewer_video_fit
    VideoScale.Fill -> R.string.viewer_video_fill
    VideoScale.Stretch -> R.string.viewer_video_stretch
}

internal fun VideoRotation.labelRes(): Int = when (this) {
    VideoRotation.Automatic -> R.string.viewer_video_rotation_auto
    VideoRotation.MatchVideo -> R.string.viewer_video_rotation_match
    VideoRotation.Locked -> R.string.viewer_video_rotation_locked
}

private const val HudAlpha = 0.6f

/** Fills the screen and swallows everything, and is all a locked player has. */
@Composable
internal fun VideoLockOverlay(session: VideoSession, nudge: Int, onUnlocked: () -> Unit, modifier: Modifier = Modifier) {
    var hintShown by remember { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(nudge) {
        if (nudge > 0) hintShown = true
    }
    LaunchedEffect(hintShown, touches, nudge) {
        if (hintShown) {
            delay(LockHintMs)
            hintShown = false
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                swallowTouches(onDown = {
                    hintShown = true
                    touches++
                })
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = hintShown,
            enter = fadeIn(KobbloMotion.fadeIn()),
            exit = fadeOut(KobbloMotion.fadeOut()),
            modifier = Modifier.padding(bottom = 96.dp)
        ) {
            VideoUnlockPill(
                onUnlock = {
                    session.isLocked = false
                    onUnlocked()
                }
            )
        }
    }
}

/** Has to be held for a moment, so a tap from a pocket or a small child doesn't get through it. */
@Composable
private fun VideoUnlockPill(onUnlock: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(holding) {
        if (holding) {
            delay(UnlockHoldMs)
            onUnlock()
        }
    }
    HudPill(
        modifier = Modifier.pointerInput(Unit) { swallowTouches(onDown = { holding = true }, onUp = { holding = false }) },
        fill = if (holding) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = HudAlpha)
    ) {
        Icon(AppIcons.LockFill, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.viewer_video_hold_to_unlock),
            style = KobbloType.callout.copy(fontWeight = FontWeight.Medium),
            color = Color.White
        )
    }
}

private const val LockHintMs = 2500L
private const val UnlockHoldMs = 900L
