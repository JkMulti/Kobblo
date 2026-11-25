package com.kobblo.gallery.file.explorer.viewer.video

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.SubtitleView
import coil.compose.AsyncImage
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import com.kobblo.gallery.file.explorer.viewer.decoder.ViewerImages
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay

/** How far up subtitles need to sit to clear the viewer's controls while those are showing. */
private val ChromeClearance = 170.dp

/** How long a video plays before its controls step aside. */
const val ControlsHideDelayMillis = 3000L

private enum class SeekHint { Back, Forward }

/**
 * One video in the viewer's pager. A page out of view is only its poster: the player belongs to
 * whichever page is showing, so swiping through a feed costs one decoder at most. A tap and a pull
 * work as they do on a photo. A double tap seeks by side instead of zooming.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoPage(
    file: FileItem,
    session: VideoSession,
    isActive: Boolean,
    chromeVisible: Boolean,
    controls: VideoGestureControls,
    onTap: () -> Unit,
    onOpenWith: () -> Unit,
    onShowDetails: () -> Unit
) {
    val context = LocalContext.current
    val currentChromeVisible by rememberUpdatedState(chromeVisible)
    val currentIsActive by rememberUpdatedState(isActive)
    var hint by remember { mutableStateOf<SeekHint?>(null) }
    var hintSide by remember { mutableStateOf(SeekHint.Forward) }
    var hintTick by remember { mutableLongStateOf(0L) }
    val player = session.player.takeIf { isActive && session.file?.path == file.path }
    val currentOnTap by rememberUpdatedState(onTap)
    val problem = session.problem.takeIf { player != null }

    LaunchedEffect(hintTick) {
        if (hintTick > 0L) {
            delay(HintMillis)
            hint = null
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .videoGestures(session, controls, isWatching = { currentIsActive && !currentChromeVisible })
            .pointerInput(isActive) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = { tap ->
                        // Read now, because the player arrives after this gesture is set up.
                        if (isActive && session.hasPlayer && session.problem == null) {
                            val forward = tap.x > size.width / 2f
                            session.seekBy(if (forward) VideoSession.SeekStepMs else -VideoSession.SeekStepMs)
                            hintSide = if (forward) SeekHint.Forward else SeekHint.Back
                            hint = hintSide
                            hintTick++
                        }
                    },
                    onLongPress = {
                        if (isActive && session.hasPlayer && session.problem == null && session.isPlaying) {
                            session.holdSpeed(true)
                            controls.show(VideoHud.Speed(VideoSession.HoldSpeed))
                        }
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (session.isHoldingSpeed) {
                            session.holdSpeed(false)
                            controls.hud = null
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val aspect = session.videoAspect.takeIf { player != null && it > 0f } ?: 0f
        val boxAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 1f
        val (widthShare, heightShare) = session.scale.frameFraction(aspect, boxAspect)
        val frameWidth: Dp = maxWidth * widthShare
        val frameHeight: Dp = maxHeight * heightShare

        if (player != null && problem == null) {
            key(player) {
                VideoSurface(
                    player = player,
                    keepScreenOn = session.isPlaying,
                    // Stays out of sight until its shape is known, or a first frame can flash stretched to the box.
                    modifier = Modifier
                        .requiredSize(frameWidth, frameHeight)
                        .graphicsLayer { alpha = if (aspect > 0f) 1f else 0f }
                )
            }
        }

        // The tile's still sits over the surface until a real frame is ready, so opening a video
        // looks instant rather than flashing empty.
        if (problem == null && (player == null || !session.firstFrameShown) || session.isAudioOnly) {
            val request = remember(file.path) { ViewerImages.posterRequest(context, file, PosterSide) }
            AsyncImage(
                model = request,
                contentDescription = file.name,
                contentScale = when (session.scale) {
                    VideoScale.Fit -> ContentScale.Fit
                    VideoScale.Fill -> ContentScale.Crop
                    VideoScale.Stretch -> ContentScale.FillBounds
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (player != null && session.cues.isNotEmpty() && problem == null) {
            val belowFrame = (maxHeight - frameHeight) / 2
            val base = if (chromeVisible) (ChromeClearance - belowFrame).coerceAtLeast(16.dp) else 16.dp
            val lift = base + session.subtitlePosition.liftDp.dp
            val textSize = session.subtitleSize.sp
            AndroidView(
                factory = { SubtitleView(it).apply { setUserDefaultStyle() } },
                update = {
                    it.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize)
                    it.setCues(session.cues)
                },
                modifier = Modifier
                    .requiredSize(frameWidth, frameHeight)
                    .padding(bottom = lift)
            )
        }

        if (problem != null) {
            VideoProblemMessage(problem, onOpenWith, onShowDetails)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = hint != null,
                enter = fadeIn(KobbloMotion.fadeIn()),
                exit = fadeOut(KobbloMotion.fadeOut()),
                modifier = Modifier.align(if (hintSide == SeekHint.Back) Alignment.CenterStart else Alignment.CenterEnd)
            ) {
                SeekHintPill(forward = hintSide != SeekHint.Back)
            }
            if (isActive) {
                VideoHudPanel(controls, Modifier.align(Alignment.Center).padding(bottom = 120.dp))
                ResumePill(
                    session = session,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = ChromeClearance)
                )
            }
        }
    }
}

/** Says that it picked up where it was left, in passing, and offers the start for anyone who wanted that instead. */
@Composable
private fun ResumePill(session: VideoSession, modifier: Modifier = Modifier) {
    val resumedAt = session.resumedAtMs
    LaunchedEffect(resumedAt) {
        if (resumedAt > 0L) {
            delay(ResumeOffer.ShownForMs)
            session.dismissResumeOffer()
        }
    }
    AnimatedVisibility(
        visible = resumedAt > 0L && session.problem == null,
        enter = fadeIn(KobbloMotion.fadeIn()),
        exit = fadeOut(KobbloMotion.fadeOut()),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), CapsuleShape)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.viewer_video_resumed, formatVideoTime(resumedAt)),
                style = KobbloType.footnote,
                color = Color.White
            )
            TextButton(onClick = session::startOver) {
                Text(stringResource(R.string.viewer_video_start_over), style = KobbloType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: ExoPlayer, keepScreenOn: Boolean, modifier: Modifier) {
    // A TextureView rather than a SurfaceView, because the viewer shrinks its page with a graphics
    // layer when pulled, and only a TextureView shrinks along with it.
    AndroidView(
        factory = { context ->
            TextureView(context).also { player.setVideoTextureView(it) }
        },
        update = { it.keepScreenOn = keepScreenOn },
        onRelease = { player.clearVideoTextureView(it) },
        modifier = modifier
    )
}

@Composable
private fun SeekHintPill(forward: Boolean) {
    Row(
        modifier = Modifier
            .padding(horizontal = 28.dp)
            .background(Color.Black.copy(alpha = 0.55f), CapsuleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = if (forward) AppIcons.FastForward else AppIcons.Rewind,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = stringResource(R.string.viewer_video_seek_seconds, (VideoSession.SeekStepMs / 1000).toInt()),
            style = KobbloType.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White
        )
    }
}

@Composable
private fun VideoProblemMessage(problem: VideoProblem, onOpenWith: () -> Unit, onShowDetails: () -> Unit) {
    val (title, body) = when (problem) {
        VideoProblem.FileMissing -> stringResource(R.string.viewer_video_missing_title) to stringResource(R.string.viewer_video_missing_body)
        VideoProblem.NoAccess -> stringResource(R.string.viewer_video_no_access_title) to stringResource(R.string.viewer_video_no_access_body)
        is VideoProblem.Unsupported -> stringResource(R.string.viewer_video_unsupported_title) to stringResource(R.string.viewer_video_unsupported_body, problem.format)
        VideoProblem.Damaged -> stringResource(R.string.viewer_video_damaged_title) to stringResource(R.string.viewer_video_damaged_body)
    }
    KobbloMessage(
        icon = AppIcons.Video,
        title = title,
        body = body,
        modifier = Modifier
            .padding(horizontal = KobbloSpace.screen)
            .clip(KobbloShapes.lg)
            .background(KobbloDesign.colors.snow),
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpenWith) {
                    Text(stringResource(R.string.viewer_open_with))
                }
                TextButton(onClick = onShowDetails) {
                    Text(stringResource(R.string.viewer_details))
                }
            }
        }
    )
}

/**
 * Sits in the strip's place while a video is showing, so the viewer's chrome stays the same size:
 * play and pause, where you are, the track, and how much is left.
 */
@Composable
fun VideoScrubber(
    session: VideoSession,
    backdrop: Backdrop,
    onScrubbingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Set when there's another video that way in this folder, and there's room for a button. */
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null
) {
    val colors = KobbloDesign.colors
    val duration = session.durationMs
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val isScrubbing = scrubFraction != null

    // This only exists while the controls show, so it also has to catch up when they come back.
    LaunchedEffect(session.player, session.isPlaying, isScrubbing) {
        session.refreshPosition()
        while (session.isPlaying && !isScrubbing) {
            delay(PositionTickMillis)
            session.refreshPosition()
        }
    }

    val fraction = scrubFraction ?: if (duration > 0L) (session.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shown = (fraction * duration).toLong()
    val timeStyle = KobbloType.caption.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")

    Row(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(44.dp)
            .kobbloGlass(backdrop, CapsuleShape)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onPrevious != null) {
            ControlIcon(AppIcons.SkipPrevious, stringResource(R.string.viewer_video_previous), onPrevious)
        }
        ControlIcon(
            icon = when {
                session.isEnded -> AppIcons.Replay
                session.isPlaying -> AppIcons.Pause
                else -> AppIcons.Play
            },
            description = stringResource(if (session.isPlaying) R.string.viewer_video_pause else R.string.viewer_video_play),
            onClick = session::togglePlay
        )
        if (onNext != null) {
            ControlIcon(AppIcons.SkipNext, stringResource(R.string.viewer_video_next), onNext)
        }
        Text(formatVideoTime(shown), style = timeStyle, color = colors.ink)
        ScrubTrack(
            fraction = fraction,
            onChange = {
                scrubFraction = it
                onScrubbingChange(true)
                session.scrubTo((it * duration).toLong())
            },
            onChangeFinished = {
                scrubFraction?.let { session.finishScrub((it * duration).toLong()) }
                scrubFraction = null
                onScrubbingChange(false)
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        )
        Text("-" + formatVideoTime(duration - shown), style = timeStyle, color = colors.smoke)
        ControlIcon(
            icon = if (session.isMuted) AppIcons.VolumeOff else AppIcons.VolumeHigh,
            description = stringResource(if (session.isMuted) R.string.viewer_video_unmute else R.string.viewer_video_mute),
            onClick = session::toggleMute
        )
    }
}

@Composable
private fun ControlIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    Box(
        modifier = Modifier
            .size(36.dp)
            .quietClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = colors.ink, modifier = Modifier.size(20.dp))
    }
}

/** A slim track that swells under the finger, matching the music player's slider. */
@Composable
private fun ScrubTrack(
    fraction: Float,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KobbloDesign.colors
    val reduced = ExpressiveMotion.isReducedMotion()
    val direction = LocalLayoutDirection.current
    var isActive by remember { mutableStateOf(false) }
    val trackHeight by animateDpAsState(
        targetValue = if (isActive) 9.dp else 5.dp,
        animationSpec = if (reduced) snap() else KobbloMotion.snappy(),
        label = "video_track"
    )
    Box(
        modifier = modifier
            .height(36.dp)
            .pointerInput(direction) {
                fun toFraction(x: Float): Float {
                    val raw = (x / size.width).coerceIn(0f, 1f)
                    return if (direction == LayoutDirection.Ltr) raw else 1f - raw
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    isActive = true
                    onChange(toFraction(down.position.x))
                    drag(down.id) { change ->
                        change.consume()
                        onChange(toFraction(change.position.x))
                    }
                    isActive = false
                    onChangeFinished()
                }
            }
            .drawBehind {
                val height = trackHeight.toPx()
                val top = (size.height - height) / 2f
                val radius = CornerRadius(height / 2f)
                drawRoundRect(colors.smoke.copy(alpha = 0.28f), Offset(0f, top), Size(size.width, height), radius)
                val filled = (size.width * fraction).coerceAtLeast(if (fraction > 0f) height else 0f)
                val left = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - filled
                drawRoundRect(colors.ink, Offset(left, top), Size(filled, height), radius)
            }
    )
}

private const val HintMillis = 650L
private const val PositionTickMillis = 100L
private const val PosterSide = 1280
