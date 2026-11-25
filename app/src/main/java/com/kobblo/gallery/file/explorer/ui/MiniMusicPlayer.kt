package com.kobblo.gallery.file.explorer.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * How far into the song playback has got, in milliseconds. It travels as a flow of its own rather than
 * inside [AppState], so a tick redraws the players' progress and recomposes nothing else. MainActivity
 * provides the real one; without it, playback stands at zero.
 */
val LocalAudioPosition = staticCompositionLocalOf<StateFlow<Long>> { MutableStateFlow(0L) }

/** The song's name as both players show it, with a stand-in for a file that carries no title. */
@Composable
internal fun AppState.playingTitle(): String = audioTitle.ifEmpty { stringResource(R.string.now_playing) }

/** The artist as both players show it, with a stand-in for a file that names none. */
@Composable
internal fun AppState.playingArtist(): String = audioArtist.ifEmpty { stringResource(R.string.music_unknown_artist) }

/** Plenty for a cover filling the width of a phone, and far less memory than a 3000 pixel scan. */
private const val MaxArtworkSide = 1200

/** Decodes the embedded cover off the main thread. */
@Composable
internal fun rememberArtwork(data: ByteArray?): ImageBitmap? {
    val artwork by produceState<ImageBitmap?>(null, data) {
        value = withContext(Dispatchers.Default) {
            data?.let {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MaxArtworkSide) sample *= 2
                    BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    return artwork
}

/** How far the player must be swiped sideways, as a share of its width, before it stops playback. */
private const val StopSwipeShare = 0.4f

/**
 * The mini player: a glass capsule that sits on the tab bar and shares its margins and material.
 * Playback fills the capsule from behind, in the same quiet tone as the tab bar's own capsule. Tap
 * it to open the player, and swipe it away sideways to stop.
 */
@Composable
fun MiniMusicPlayer(
    state: AppState,
    onAction: (AppAction) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier
) {
    if (state.currentAudioFile == null) return
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val reduced = ExpressiveMotion.isReducedMotion()
    // Only drawing reads this, so a playback tick redraws the fill without recomposing the row.
    val position by LocalAudioPosition.current.collectAsState()
    val artwork = rememberArtwork(state.audioArtworkData)
    val swipe = remember { Animatable(0f) }
    val source = remember { MutableInteractionSource() }
    val duration = state.audioDuration

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .graphicsLayer {
                translationX = swipe.value
                alpha = if (size.width > 0f) (1f - abs(swipe.value) / size.width).coerceIn(0f, 1f) else 1f
            }
            .pressScale(pressedScale = 0.98f, interactionSource = source)
            .kobbloGlass(backdrop, CapsuleShape)
            .clip(CapsuleShape)
            .drawBehind {
                if (duration > 0L) {
                    val width = size.width * (position.toFloat() / duration).coerceIn(0f, 1f)
                    drawRect(
                        color = colors.ink.copy(alpha = if (colors.isDark) 0.12f else 0.06f),
                        topLeft = Offset(if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - width, 0f),
                        size = Size(width, size.height)
                    )
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(swipe.value) > size.width * StopSwipeShare) {
                                val away = if (swipe.value > 0f) size.width.toFloat() else -size.width.toFloat()
                                swipe.animateTo(away, if (reduced) snap() else tween(KobbloMotion.Default, easing = KobbloMotion.EaseIn))
                                onAction(AppAction.StopAudio)
                                swipe.snapTo(0f)
                            } else {
                                swipe.animateTo(0f, if (reduced) snap() else KobbloMotion.smooth())
                            }
                        }
                    },
                    onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        scope.launch { swipe.snapTo(swipe.value + amount) }
                    }
                )
            }
            .quietClickable(source = source) {
                haptics.tap()
                onAction(AppAction.SetShowFullScreenPlayer(true))
            }
            .padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(artwork = artwork, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.playingTitle(),
                style = KobbloType.callout.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = state.playingArtist(),
                style = KobbloType.footnote,
                color = colors.smoke,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(4.dp))
        Crossfade(
            targetState = state.isAudioPlaying,
            animationSpec = if (reduced) snap() else tween(KobbloMotion.Fast),
            label = "mini_play_pause"
        ) { playing ->
            MiniAction(
                icon = if (playing) AppIcons.Pause else AppIcons.Play,
                description = stringResource(if (playing) R.string.pause else R.string.play),
                onClick = { onAction(AppAction.ToggleAudioPlayback) }
            )
        }
        MiniAction(
            icon = AppIcons.FastForward,
            description = stringResource(R.string.next_track),
            onClick = { onAction(AppAction.PlayNextAudio) }
        )
    }
}

@Composable
private fun MiniAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val haptics = LocalHaptics.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(44.dp)
            .pressScale(pressedScale = 0.85f, interactionSource = source)
            .quietClickable(source = source) {
                haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = KobbloDesign.colors.ink, modifier = Modifier.size(26.dp))
    }
}

/** The cover, or a quiet placeholder for a track with none. */
@Composable
internal fun Artwork(artwork: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    val colors = KobbloDesign.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CapsuleShape)
            .background(colors.mist),
        contentAlignment = Alignment.Center
    ) {
        CoverOrNote(artwork, noteSize = size * 0.5f, noteTint = colors.smoke)
    }
}

/**
 * What goes inside a cover's frame: the picture, cropped to fill it, or a musical note for a song
 * that has none. The frame says how big and what color its note is.
 */
@Composable
internal fun CoverOrNote(artwork: ImageBitmap?, noteSize: Dp, noteTint: Color) {
    if (artwork == null) {
        Icon(AppIcons.Music, contentDescription = null, tint = noteTint, modifier = Modifier.size(noteSize))
    } else {
        Image(
            bitmap = artwork,
            contentDescription = stringResource(R.string.album_art),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}
