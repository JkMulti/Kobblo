package com.kobblo.gallery.file.explorer.ui

import com.kobblo.gallery.file.explorer.music.shareSong
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSheetContent
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.ambientShadow
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

// The player sits on its own colors, taken from the cover, so its text is white in either theme.
private val PlayerPrimary = Color.White
private val PlayerSecondary = Color.White.copy(alpha = 0.62f)
private val PlayerTrack = Color.White.copy(alpha = 0.22f)
private val PlayerGrabber = Color.White.copy(alpha = 0.4f)
private val PlayerButtonFill = Color.White.copy(alpha = 0.16f)

private val NoArtworkTop = Color(0xFF55555A)
private val NoArtworkBottom = Color(0xFF1C1C1E)

/** How far the player must be pulled down, or how fast, before letting go closes it. */
private val DismissDistance = 120.dp
private const val DismissVelocity = 1400f

/**
 * The full player, after a music app's Now Playing: the cover on a background made from its own
 * colors, and below it the track, a scrubber with the time remaining, transport, and volume. The
 * cover settles back when paused. Pull the screen down to return to the mini player.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FullScreenMusicPlayer(
    state: AppState,
    onAction: (AppAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val file = state.currentAudioFile ?: return
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val reduced = ExpressiveMotion.isReducedMotion()
    val isDarkTheme = KobbloDesign.colors.isDark
    val artwork = rememberArtwork(state.audioArtworkData)
    val pull = remember { Animatable(0f) }
    var showMore by remember { mutableStateOf(false) }
    var showUpNext by remember { mutableStateOf(false) }
    var showSound by remember { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val dismissPx = with(LocalDensity.current) { DismissDistance.toPx() }

    BackHandler { onAction(AppAction.SetShowFullScreenPlayer(false)) }

    // A restored queue isn't open in the player until something needs it, and this screen does: the
    // file's own lyrics and seeking both depend on it.
    LaunchedEffect(Unit) { com.kobblo.gallery.file.explorer.music.MusicPlayer.get(context).ensureLoaded() }

    // The background is dark whatever the theme, so the status bar's icons have to be light.
    val view = LocalView.current
    DisposableEffect(view, isDarkTheme) {
        val controller = (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = !isDarkTheme
            controller?.isAppearanceLightNavigationBars = !isDarkTheme
        }
    }

    val (topColor, bottomColor) = rememberArtworkGradient(artwork)
    val coverScale by animateFloatAsState(
        targetValue = if (state.isAudioPlaying) 1f else 0.82f,
        animationSpec = if (reduced) snap() else com.kobblo.gallery.file.explorer.ui.design.KobbloMotion.smooth(),
        label = "player_cover"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = pull.value
                shape = KobbloShapes.sheet
                clip = pull.value > 0f
            }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = {
                        val velocity = tracker.calculateVelocity().y
                        if (pull.value > dismissPx || velocity > DismissVelocity) {
                            onAction(AppAction.SetShowFullScreenPlayer(false))
                        } else {
                            scope.launch { pull.animateTo(0f, KobbloMotion.smooth(), velocity) }
                        }
                    },
                    onDragCancel = { scope.launch { pull.animateTo(0f, KobbloMotion.smooth()) } },
                    onVerticalDrag = { change, amount ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        scope.launch { pull.snapTo((pull.value + amount).coerceAtLeast(0f)) }
                    }
                )
            }
            // Nothing beneath the player can be reached through it.
            .quietClickable {}
            .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
    ) {
        if (artwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(110.dp)
                    .graphicsLayer { alpha = 0.55f }
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(width = 36.dp, height = 5.dp)
                    .clip(CapsuleShape)
                    .background(PlayerGrabber)
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                val side = minOf(maxWidth, maxHeight)
                Crossfade(
                    targetState = showLyrics,
                    animationSpec = if (reduced) snap() else tween(KobbloMotion.Default),
                    label = "cover_or_lyrics"
                ) { lyrics ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (lyrics) {
                            com.kobblo.gallery.file.explorer.music.LyricsPane(path = file.path, modifier = Modifier.fillMaxSize())
                        } else {
                            Cover(artwork = artwork, side = side, scale = coverScale)
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.playingTitle(),
                        style = KobbloType.title.copy(fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
                        color = PlayerPrimary,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = if (state.isAudioPlaying) Int.MAX_VALUE else 0)
                    )
                    Text(
                        text = state.playingArtist(),
                        style = KobbloType.body,
                        color = PlayerSecondary,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = if (state.isAudioPlaying) Int.MAX_VALUE else 0)
                    )
                }
                Spacer(Modifier.width(12.dp))
                PlayerButton(
                    icon = AppIcons.MoreHoriz,
                    description = stringResource(R.string.music_more),
                    size = 34.dp,
                    iconSize = 18.dp,
                    filled = true,
                    onClick = {
                        haptics.tap()
                        showMore = true
                    }
                )
            }

            Spacer(Modifier.height(16.dp))
            PlaybackScrubber(duration = state.audioDuration.coerceAtLeast(0L), onAction = onAction)
            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerButton(
                    icon = AppIcons.Rewind,
                    description = stringResource(R.string.previous_track),
                    size = 64.dp,
                    iconSize = 40.dp,
                    onClick = {
                        haptics.tap()
                        onAction(AppAction.PlayPreviousAudio)
                    }
                )
                Crossfade(
                    targetState = state.isAudioPlaying,
                    animationSpec = if (reduced) snap() else tween(KobbloMotion.Fast),
                    label = "player_play_pause"
                ) { playing ->
                    PlayerButton(
                        icon = if (playing) AppIcons.Pause else AppIcons.Play,
                        description = stringResource(if (playing) R.string.pause else R.string.play),
                        size = 84.dp,
                        iconSize = 56.dp,
                        onClick = {
                            haptics.tap()
                            onAction(AppAction.ToggleAudioPlayback)
                        }
                    )
                }
                PlayerButton(
                    icon = AppIcons.FastForward,
                    description = stringResource(R.string.next_track),
                    size = 64.dp,
                    iconSize = 40.dp,
                    onClick = {
                        haptics.tap()
                        onAction(AppAction.PlayNextAudio)
                    }
                )
            }

            Spacer(Modifier.height(24.dp))
            VolumeSlider()
            Spacer(Modifier.height(12.dp))

            ToolRow(
                duration = state.audioDuration.coerceAtLeast(0L),
                lyricsShowing = showLyrics,
                onToggleLyrics = { showLyrics = !showLyrics },
                onShowUpNext = { showUpNext = true },
                onAction = onAction
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showMore) {
        PlayerMoreSheet(
            file = file,
            title = state.audioTitle,
            onAction = onAction,
            onDismiss = { showMore = false },
            onShowSound = { showSound = true },
            context = context
        )
    }
    if (showUpNext) {
        com.kobblo.gallery.file.explorer.music.UpNextSheet(onDismiss = { showUpNext = false })
    }
    if (showSound) {
        com.kobblo.gallery.file.explorer.music.SoundSheet(onDismiss = { showSound = false })
    }
}

@Composable
private fun Cover(artwork: ImageBitmap?, side: Dp, scale: Float) {
    val shape = RoundedRectangle(14.dp)
    Box(
        modifier = Modifier
            .size(side)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .ambientShadow(shape, Color.Black.copy(alpha = 0.45f))
            .clip(shape)
            .background(Color.White.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        CoverOrNote(artwork, noteSize = side * 0.36f, noteTint = PlayerSecondary)
    }
}

/** Reads the cover's top and bottom colors, darkened so white text always stands on them. */
@Composable
private fun rememberArtworkGradient(artwork: ImageBitmap?): Pair<Color, Color> {
    val colors by produceState(NoArtworkTop to NoArtworkBottom, artwork) {
        val image = artwork ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val pixels = image.toPixelMap()
            fun averageRow(fromY: Int, toY: Int): Color {
                var r = 0f
                var g = 0f
                var b = 0f
                var count = 0
                val stepX = (pixels.width / 24).coerceAtLeast(1)
                val stepY = ((toY - fromY) / 8).coerceAtLeast(1)
                for (y in fromY until toY step stepY) {
                    for (x in 0 until pixels.width step stepX) {
                        val pixel = pixels[x, y]
                        r += pixel.red
                        g += pixel.green
                        b += pixel.blue
                        count++
                    }
                }
                return if (count == 0) NoArtworkBottom else Color(r / count, g / count, b / count)
            }
            val third = (pixels.height / 3).coerceAtLeast(1)
            lerp(averageRow(0, third), Color.Black, 0.28f) to
                lerp(averageRow(pixels.height - third, pixels.height), Color.Black, 0.62f)
        }
    }
    return colors
}

/** Reads the position itself, so a playback tick redraws this and not the whole screen. */
@Composable
private fun PlaybackScrubber(duration: Long, onAction: (AppAction) -> Unit) {
    val position by LocalAudioPosition.current.collectAsState()
    var seekFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = seekFraction ?: if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownPosition = (fraction * duration).toLong()
    val timeStyle = KobbloType.caption.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")

    Column {
        PlayerSlider(
            fraction = fraction,
            onChange = { seekFraction = it },
            onChangeFinished = {
                seekFraction?.let { onAction(AppAction.SeekAudio((it * duration).toLong())) }
                seekFraction = null
            }
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = formatPlayerTime(shownPosition), style = timeStyle, color = PlayerSecondary)
            Text(text = "-" + formatPlayerTime(duration - shownPosition), style = timeStyle, color = PlayerSecondary)
        }
    }
}

/** A slim track that swells under the finger, as a system slider does. */
@Composable
private fun PlayerSlider(
    fraction: Float,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isActive by remember { mutableStateOf(false) }
    val reduced = ExpressiveMotion.isReducedMotion()
    val direction = LocalLayoutDirection.current
    val trackHeight by animateDpAsState(
        targetValue = if (isActive) 11.dp else 6.dp,
        animationSpec = if (reduced) snap() else KobbloMotion.snappy(),
        label = "slider_height"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
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
                drawRoundRect(PlayerTrack, Offset(0f, top), Size(size.width, height), radius)
                val filled = (size.width * fraction).coerceAtLeast(if (fraction > 0f) height else 0f)
                val left = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - filled
                drawRoundRect(
                    color = if (isActive) PlayerPrimary else PlayerPrimary.copy(alpha = 0.72f),
                    topLeft = Offset(left, top),
                    size = Size(filled, height),
                    cornerRadius = radius
                )
            }
    )
}

/** The device's media volume, kept in step with its hardware buttons. */
@Composable
private fun VolumeSlider() {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(audio) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.VolumeLow, contentDescription = null, tint = PlayerSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        PlayerSlider(
            fraction = dragFraction ?: (volume.toFloat() / maxVolume),
            onChange = { fraction ->
                dragFraction = fraction
                val next = (fraction * maxVolume).roundToInt()
                if (next != volume) {
                    volume = next
                    try {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                    } catch (_: SecurityException) {
                        // Do Not Disturb can refuse this. The hardware buttons still work.
                    }
                }
            },
            onChangeFinished = { dragFraction = null },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Icon(AppIcons.VolumeHigh, contentDescription = null, tint = PlayerSecondary, modifier = Modifier.size(20.dp))
    }
}

/** Lyrics and the queue at the ends, and skips of ten seconds between them, for recordings as much as songs. */
@Composable
private fun ToolRow(
    duration: Long,
    lyricsShowing: Boolean,
    onToggleLyrics: () -> Unit,
    onShowUpNext: () -> Unit,
    onAction: (AppAction) -> Unit
) {
    val positionFlow = LocalAudioPosition.current
    val haptics = LocalHaptics.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolButton(
            icon = AppIcons.Lyrics,
            description = stringResource(R.string.music_lyrics),
            active = lyricsShowing
        ) {
            haptics.tap()
            onToggleLyrics()
        }
        SeekButton(description = stringResource(R.string.music_back_10), mirrored = false) {
            haptics.tap()
            onAction(AppAction.SeekAudio((positionFlow.value - 10_000L).coerceAtLeast(0L)))
        }
        SeekButton(description = stringResource(R.string.music_forward_10), mirrored = true) {
            haptics.tap()
            onAction(AppAction.SeekAudio((positionFlow.value + 10_000L).coerceAtMost(duration)))
        }
        ToolButton(icon = AppIcons.Queue, description = stringResource(R.string.music_up_next), active = false) {
            haptics.tap()
            onShowUpNext()
        }
    }
}

/** A quiet glyph in the tool row, which fills in behind while what it opens is showing. */
@Composable
private fun ToolButton(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(44.dp)
            .pressScale(pressedScale = 0.86f, interactionSource = source)
            .clip(CapsuleShape)
            .background(if (active) PlayerButtonFill else Color.Transparent)
            .quietClickable(source = source, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active) PlayerPrimary else PlayerSecondary,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun SeekButton(description: String, mirrored: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(44.dp)
            .pressScale(pressedScale = 0.86f, interactionSource = source)
            .quietClickable(source = source, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = AppIcons.Replay,
            contentDescription = description,
            tint = PlayerSecondary,
            modifier = Modifier
                .size(30.dp)
                .graphicsLayer { scaleX = if (mirrored) -1f else 1f }
        )
        Text(
            text = "10",
            style = TextStyle(fontSize = 9.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold),
            color = PlayerSecondary
        )
    }
}

/** A transport or toolbar button: no frame at rest, a soft circle while pressed. */
@Composable
private fun PlayerButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    filled: Boolean = false
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val halo by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(if (pressed) KobbloMotion.Fast else KobbloMotion.Slow),
        label = "player_button_halo"
    )
    Box(
        modifier = Modifier
            .size(size)
            .pressScale(pressedScale = 0.86f, interactionSource = source)
            .clip(CapsuleShape)
            .background(if (filled) PlayerButtonFill else PlayerButtonFill.copy(alpha = PlayerButtonFill.alpha * halo))
            .quietClickable(source = source, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = PlayerPrimary, modifier = Modifier.size(iconSize))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerMoreSheet(
    file: FileItem,
    title: String,
    onAction: (AppAction) -> Unit,
    onDismiss: () -> Unit,
    onShowSound: () -> Unit,
    context: Context
) {
    val colors = KobbloDesign.colors
    val store = remember { com.kobblo.gallery.file.explorer.music.MusicStore.get(context) }
    val data by store.data.collectAsState()
    val library by remember { com.kobblo.gallery.file.explorer.music.MusicLibrary.get(context) }.snapshot.collectAsState()
    val isFavorite = data.isFavorite(file.path)
    val track = library.byPath[file.path]
    val album = track?.let { t -> library.albums.firstOrNull { a -> a.tracks.any { it.path == t.path } } }
    val artist = track?.let { t -> library.artists.firstOrNull { a -> a.name.isNotBlank() && a.tracks.any { it.path == t.path } } }

    fun leavePlayerFor(route: com.kobblo.gallery.file.explorer.music.MusicRoute) {
        onDismiss()
        onAction(AppAction.SetShowFullScreenPlayer(false))
        com.kobblo.gallery.file.explorer.ui.hub.Hub.open(route)
    }

    KobbloSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        ExpressiveSheetContent(
            title = title.ifEmpty { file.name },
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            KobbloGroup {
                MoreRow(
                    stringResource(if (isFavorite) R.string.music_unfavorite else R.string.music_favorite),
                    if (isFavorite) AppIcons.StarFill else AppIcons.Star,
                    colors.ink,
                    showDivider = true
                ) {
                    store.update { it.withFavorite(file.path, !isFavorite) }
                }
                MoreRow(stringResource(R.string.music_add_to_playlist), AppIcons.Playlist, colors.ink, showDivider = true) {
                    onDismiss()
                    com.kobblo.gallery.file.explorer.music.MusicUi.addToPlaylist = listOf(
                        track ?: com.kobblo.gallery.file.explorer.music.trackFromPath(file.path, file.sizeBytes)
                    )
                }
                MoreRow(stringResource(R.string.music_sound), AppIcons.Sliders, colors.ink) {
                    onDismiss()
                    onShowSound()
                }
            }
            Spacer(Modifier.height(com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.groupGap))
            KobbloGroup {
                if (album != null) {
                    MoreRow(stringResource(R.string.music_go_to_album), AppIcons.Book, colors.ink, showDivider = true) {
                        leavePlayerFor(com.kobblo.gallery.file.explorer.music.MusicRoute.Album(album.key))
                    }
                }
                if (artist != null) {
                    MoreRow(stringResource(R.string.music_go_to_artist), AppIcons.Person, colors.ink, showDivider = true) {
                        leavePlayerFor(com.kobblo.gallery.file.explorer.music.MusicRoute.Artist(artist.key))
                    }
                }
                MoreRow(stringResource(R.string.music_share), AppIcons.Share, colors.ink, showDivider = true) {
                    onDismiss()
                    shareSong(context, file.path, inOwnTask = false)
                }
                MoreRow(stringResource(R.string.music_show_in_folder), AppIcons.FolderOpen, colors.ink) {
                    onDismiss()
                    com.kobblo.gallery.file.explorer.ui.hub.Hub.close()
                    File(file.path).parent?.let { onAction(AppAction.SetLocation(it)) }
                    onAction(AppAction.SetShowFullScreenPlayer(false))
                }
            }
            Spacer(Modifier.height(com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.groupGap))
            KobbloGroup {
                MoreRow(stringResource(R.string.music_stop_playback), AppIcons.Close, colors.danger) {
                    onDismiss()
                    onAction(AppAction.SetShowFullScreenPlayer(false))
                    onAction(AppAction.StopAudio)
                }
            }
        }
    }
}

private fun formatPlayerTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
