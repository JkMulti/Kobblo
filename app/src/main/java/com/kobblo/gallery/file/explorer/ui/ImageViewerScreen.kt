package com.kobblo.gallery.file.explorer.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import com.kobblo.gallery.file.explorer.gallery.transformed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.Velocity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.spring
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import kotlinx.coroutines.Job
import kotlin.math.abs
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlin.math.roundToInt
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveConfirmationSheet
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import com.kobblo.gallery.file.explorer.viewer.MoreActions
import com.kobblo.gallery.file.explorer.viewer.PhotoBadges
import com.kobblo.gallery.file.explorer.viewer.Slideshow
import com.kobblo.gallery.file.explorer.viewer.ViewerMoreSheet
import com.kobblo.gallery.file.explorer.viewer.enterPictureInPicture
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import com.kobblo.gallery.file.explorer.viewer.decoder.SharpRegionLayer
import com.kobblo.gallery.file.explorer.viewer.decoder.ViewerImages
import com.kobblo.gallery.file.explorer.viewer.decoder.supportsRegionDecoding
import com.kobblo.gallery.file.explorer.viewer.findActivity
import com.kobblo.gallery.file.explorer.viewer.isExternalStream
import com.kobblo.gallery.file.explorer.viewer.isInVault
import com.kobblo.gallery.file.explorer.viewer.isOpenedFromOutside
import com.kobblo.gallery.file.explorer.viewer.metadata.MediaDetails
import com.kobblo.gallery.file.explorer.viewer.metadata.MediaDetailsLoader
import com.kobblo.gallery.file.explorer.viewer.metadata.MediaDetailsPanel
import com.kobblo.gallery.file.explorer.viewer.metadata.MetadataActions
import com.kobblo.gallery.file.explorer.viewer.motion.MotionClip
import com.kobblo.gallery.file.explorer.viewer.motion.MotionClipOverlay
import com.kobblo.gallery.file.explorer.viewer.motion.MotionClips
import com.kobblo.gallery.file.explorer.viewer.motion.PhotoExtras
import com.kobblo.gallery.file.explorer.viewer.openWithChooser
import com.kobblo.gallery.file.explorer.viewer.rememberIsInPictureInPicture
import com.kobblo.gallery.file.explorer.viewer.setAsWallpaper
import com.kobblo.gallery.file.explorer.viewer.shareFile
import com.kobblo.gallery.file.explorer.viewer.toggleForcedOrientation
import com.kobblo.gallery.file.explorer.viewer.video.ControlsHideDelayMillis
import com.kobblo.gallery.file.explorer.viewer.video.VideoPage
import com.kobblo.gallery.file.explorer.viewer.video.VideoScrubber
import com.kobblo.gallery.file.explorer.viewer.video.VideoSession
import com.kobblo.gallery.file.explorer.viewer.viewerUriFor
import com.kyant.backdrop.Backdrop
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlassSource
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/** How far a photo must be pulled down, or how fast, before letting go closes the viewer. */
private val DismissDistance = 120.dp
private const val DismissVelocity = 1400f

/** How far the details must be pulled up, or how fast, before letting go keeps them open. */
private const val InfoOpenFraction = 0.3f
private const val InfoVelocity = 900f

/** The share of the screen the details panel takes when open. */
private const val InfoPanelShare = 0.56f
private const val InfoPanelAllShare = 0.86f

/** A short pull that's let go springs back, with a touch of overshoot. */
private val PullSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 380f)

/**
 * Where a photo being pulled away is. Written on every touch, without starting an animation, so it
 * stays glued to the finger; only letting go animates.
 */
@androidx.compose.runtime.Stable
private class PhotoPull {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)

    /** Where the finger first touched. */
    var pivot by mutableStateOf(Offset.Unspecified)
    var settle: Job? = null
}

/** A slideshow in progress: how long each photo stays, and whether videos are part of it. */
private class SlideshowRun(val seconds: Int, val skipVideos: Boolean)

/**
 * Viewing photos and videos, the way a photo library does. The picture fills the screen, and a tap
 * hides everything else and turns the background black. Swipe sideways for the next one, up for its
 * details, and down to put it away. The list it came from stays underneath, so it shows through
 * as it is pulled down.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    files: List<FileItem>,
    initialIndex: Int,
    onAction: (AppAction) -> Unit,
    onClose: () -> Unit,
    onDeleteClick: (FileItem) -> Unit,
    onNavigateToFolder: (String) -> Unit,
    recycleBinEnabled: Boolean = true,
    /** Everything on the device, for choosing an album to send a photo to. */
    allMedia: List<FileItem> = emptyList(),
    /** A photo that has been moved away, which should leave what's being swiped through. */
    onMovedAway: (FileItem) -> Unit = {}
) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = ExpressiveMotion.isReducedMotion()

    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { files.size })
    val currentFile = files.getOrNull(pagerState.currentPage)

    // The feed syncs itself, so the list can change while this is open. Stay on the same photo.
    val shownPath = remember { mutableStateOf(files.getOrNull(initialIndex)?.path) }
    LaunchedEffect(pagerState.settledPage) {
        files.getOrNull(pagerState.settledPage)?.let {
            shownPath.value = it.path
            com.kobblo.gallery.file.explorer.gallery.GalleryScope.viewerAt = it.path
        }
    }
    DisposableEffect(Unit) {
        onDispose { com.kobblo.gallery.file.explorer.gallery.GalleryScope.viewerAt = null }
    }
    LaunchedEffect(files) {
        val index = files.indexOfFirst { it.path == shownPath.value }
        if (index >= 0 && index != pagerState.currentPage) pagerState.scrollToPage(index)
    }

    var chromeVisible by remember { mutableStateOf(true) }
    var isZoomed by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showAlbumPicker by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    // Counts photos turned for good, whose picture has to be read again.
    var turned by remember { mutableIntStateOf(0) }
    val info = remember { Animatable(0f) }
    val pull = remember { PhotoPull() }
    val activity = remember(context) { context.findActivity() }

    val video = remember { VideoSession(context) }
    val videoControls = com.kobblo.gallery.file.explorer.viewer.video.rememberVideoGestureControls()
    var lockNudge by remember { mutableIntStateOf(0) }
    var isScrubbing by remember { mutableStateOf(false) }
    val isInPip = rememberIsInPictureInPicture()
    var slideshow by remember { mutableStateOf<SlideshowRun?>(null) }
    var orientationForced by remember { mutableStateOf(false) }

    // Per photo: which page of it shows, whether its clip is playing, and how it's turned.
    val currentPath = currentFile?.path
    val extras by produceState(PhotoExtras.None, currentPath) {
        value = PhotoExtras.None
        val target = currentFile
        if (target != null && target.type == "image") value = PhotoExtras.load(target)
    }
    // The words in the photo, once it has settled on screen, when Text in Photos is on.
    var photoText by remember(currentPath) { mutableStateOf<List<String>?>(null) }
    var showText by remember { mutableStateOf(false) }
    LaunchedEffect(currentPath) { showText = false }
    val settledFile = files.getOrNull(pagerState.settledPage)
    LaunchedEffect(settledFile?.path) {
        val target = settledFile ?: return@LaunchedEffect
        if (!com.kobblo.gallery.file.explorer.smart.SmartFeatures.isSupported) return@LaunchedEffect
        delay(300)
        photoText = com.kobblo.gallery.file.explorer.smart.readPhotoText(context, target)
    }
    var shownPage by remember(currentPath) { mutableIntStateOf(0) }
    var motionPlaying by remember(currentPath) { mutableStateOf(false) }
    var panoramaRequest by remember(currentPath) { mutableIntStateOf(0) }
    var sphereOpen by remember(currentPath) { mutableStateOf(false) }
    // Once it's failed for a photo, the flat view is what that photo gets, without offering again.
    var sphereFailed by remember(currentPath) { mutableStateOf(false) }
    var rotation by remember(currentPath) { mutableIntStateOf(0) }

    // Details cost a few file reads, so they wait until something actually asks for them.
    var details by remember(currentPath) { mutableStateOf(MediaDetails.Empty) }
    var detailsVersion by remember { mutableIntStateOf(0) }
    val wantsDetails by remember { derivedStateOf { info.value > 0f || showMoreSheet } }
    LaunchedEffect(currentPath, wantsDetails, detailsVersion) {
        val target = currentFile
        if (wantsDetails && target != null) details = MediaDetailsLoader.load(context, target)
    }

    LaunchedEffect(files) {
        if (files.isEmpty()) onClose()
    }

    // Only the page being looked at holds a player, so paging away stops it and costs nothing.
    val activeVideo = files.getOrNull(pagerState.settledPage)
        ?.takeIf { it.type == "video" && pagerState.currentPage == pagerState.settledPage }
    LaunchedEffect(activeVideo?.path) {
        if (activeVideo != null) video.open(activeVideo) else video.close()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) video.onLeftScreen()
            if (event == Lifecycle.Event.ON_START) video.onBackOnScreen()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            video.close()
            com.kobblo.gallery.file.explorer.viewer.ViewerQueue.files = null
        }
    }

    // With a video showing and its controls away, someone's watching, and gestures on the page belong to the player.
    val isWatchingVideo = currentFile?.type == "video" && !chromeVisible && video.hasPlayer && video.problem == null

    com.kobblo.gallery.file.explorer.viewer.video.VideoRotationEffect(video, isVideo = currentFile?.type == "video")
    // Brightness set for watching a video is for that, and a photo swiped to next goes back to the phone's own.
    LaunchedEffect(currentFile?.type) {
        if (currentFile?.type != "video") videoControls.restoreBrightness()
    }

    // A forced rotation is for this one video, and never outlives it.
    LaunchedEffect(currentPath) {
        if (orientationForced) {
            orientationForced = false
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(activity) {
        onDispose {
            if (orientationForced) activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Playing with the controls up, they step aside after a moment. Anything in the way of that
    // being welcome, like a sheet or a finger on the track, holds them there.
    LaunchedEffect(chromeVisible, video.isPlaying, isScrubbing, showMoreSheet, info.targetValue) {
        if (chromeVisible && video.isPlaying && !isScrubbing && !showMoreSheet && info.targetValue == 0f) {
            delay(ControlsHideDelayMillis)
            chromeVisible = false
        }
    }

    // A clip that has finished waits for what's next, which is what the controls are for.
    LaunchedEffect(video.isEnded) {
        if (video.isEnded && slideshow == null && !video.isLooping && !isInPip) chromeVisible = true
    }

    // A floating window has room for the video and nothing else.
    LaunchedEffect(isInPip) {
        chromeVisible = !isInPip
    }

    // The screen stays on through a slideshow, the same as through a video.
    val hostView = LocalView.current
    DisposableEffect(slideshow != null) {
        hostView.keepScreenOn = slideshow != null
        onDispose { hostView.keepScreenOn = false }
    }
    LaunchedEffect(slideshow) {
        val run = slideshow ?: return@LaunchedEffect
        chromeVisible = false
        if (video.isLooping) video.toggleLoop()
        while (true) {
            val page = pagerState.currentPage
            if (files.getOrNull(page)?.type == "video" && !run.skipVideos) {
                // Waits for the player to arrive, sets it going in case it was paused or had already
                // finished, and waits for it to end or fail.
                snapshotFlow { video.file?.path == files.getOrNull(page)?.path && video.hasPlayer }.first { it }
                video.play()
                snapshotFlow { video.isEnded || video.problem != null }.first { it }
                delay(SlideshowPauseAfterVideo)
            } else {
                delay(run.seconds * 1000L)
            }
            val next = Slideshow.next(page, files.size, run.skipVideos) { files.getOrNull(it)?.type == "video" }
            if (next == null) {
                slideshow = null
                chromeVisible = true
                return@LaunchedEffect
            }
            if (reduced) pagerState.scrollToPage(next) else pagerState.animateScrollToPage(next)
        }
    }

    fun setInfoOpen(open: Boolean, velocity: Float = 0f) {
        scope.launch {
            if (open) chromeVisible = true
            if (reduced) info.snapTo(if (open) 1f else 0f)
            else info.animateTo(if (open) 1f else 0f, KobbloMotion.smooth(), velocity)
        }
    }

    // With nothing else open, Back puts the photo away, and the system's back gesture starts pulling
    // it down as it's drawn, so letting go carries on into the flight home.
    val infoClosed by remember { derivedStateOf { info.value == 0f } }
    val backPutsAway = !video.isLocked && slideshow == null && !showDeleteConfirm && !showMoreSheet && infoClosed
    BackHandler(enabled = !backPutsAway) {
        when {
            video.isLocked -> lockNudge++
            slideshow != null -> {
                slideshow = null
                chromeVisible = true
            }
            showDeleteConfirm -> showDeleteConfirm = false
            showMoreSheet -> showMoreSheet = false
            info.value > 0f -> setInfoOpen(false)
        }
    }

    // The chrome fades in once the viewer is there, rather than arriving with the photo still in flight.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    // With the controls hidden, the system's bars go too, as in any full-screen photo.
    val view = LocalView.current
    val controller = remember(view) {
        (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
    }
    LaunchedEffect(chromeVisible) {
        controller ?: return@LaunchedEffect
        if (chromeVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(controller) {
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val background by animateColorAsState(
        targetValue = if (chromeVisible && !colors.isDark) Color.White else Color.Black,
        animationSpec = tween(KobbloMotion.Slow, easing = KobbloMotion.EaseInOut),
        label = "viewer_background"
    )
    val chromeShown by animateFloatAsState(
        targetValue = if (chromeVisible && entered) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(KobbloMotion.Default, easing = KobbloMotion.EaseInOut),
        label = "viewer_chrome"
    )
    val backdrop = rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val panelHeight = maxHeight * InfoPanelShare
        val panelHeightPx = with(density) { panelHeight.toPx() }
        val dismissDistancePx = with(density) { DismissDistance.toPx() }
        val chromeLiftPx = with(density) { 10.dp.toPx() }
        val screenWidthPx = with(density) { maxWidth.toPx() }

        // How far the photo has been pulled, as a share of the screen. Lambdas, read only while drawing,
        // so pulling redraws the photo without recomposing the viewer on every frame.
        val pulled = { (abs(pull.y) / screenHeightPx).coerceIn(0f, 1f) }
        val backgroundShown = { (1f - abs(pull.y) / (dismissDistancePx * 3f)).coerceIn(0f, 1f) }
        val pageScale = { (1f - info.value * InfoPanelShare) * (1f - pulled() * 0.45f) }
        // The photo shrinks around where the finger first touched, so it stays under the thumb.
        val pagePivot = {
            val at = pull.pivot
            if (at.isSpecified && (pull.x != 0f || pull.y != 0f)) at else Offset(screenWidthPx / 2f, screenHeightPx / 2f)
        }

        // Lambdas rather than local functions: release lint can't resolve a local function inside a composable lambda.
        val springBack: (Velocity) -> Unit = { velocity ->
            pull.settle?.cancel()
            pull.settle = scope.launch {
                launch { animate(pull.x, 0f, velocity.x, PullSpring) { v, _ -> pull.x = v } }
                launch { animate(pull.y, 0f, velocity.y, PullSpring) { v, _ -> pull.y = v } }
            }
        }

        // Puts the photo away. A photo grid underneath gets it back by flight, from exactly where it is
        // on screen now, still moving as fast as it was thrown. Anywhere else it shrinks and fades there.
        val putAway: (Velocity) -> Unit = { velocity ->
            val file = currentFile
            val flies = file != null && !reduced && !isZoomed && rotation == 0 && !isInPip && !sphereOpen &&
                file.kind != com.kobblo.gallery.file.explorer.viewer.ExternalMedia.External
            if (flies && file != null) {
                val aspect = video.videoAspect.takeIf { file.type == "video" && it > 0f }
                    ?: com.kobblo.gallery.file.explorer.gallery.PhotoFlight.aspectOf(context, file)
                val rect = com.kobblo.gallery.file.explorer.gallery.PhotoFlight.fittedRect(aspect, screenWidthPx, screenHeightPx)
                    .transformed(pagePivot(), pageScale(), Offset(pull.x, pull.y - info.value * panelHeightPx / 2f))
                pull.settle?.cancel()
                com.kobblo.gallery.file.explorer.gallery.PhotoFlight.beginClose(file, rect, background, backgroundShown(), velocity)
            }
            onClose()
        }

        // The gesture below is set up once, so it reads these through state and always gets the photo on screen now.
        val currentPutAway by rememberUpdatedState(putAway)
        val currentIsWatchingVideo by rememberUpdatedState(isWatchingVideo)

        // Pulling a photo down fades this out, uncovering the list beneath it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = backgroundShown() }
                .background(background)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .kobbloGlassSource(backdrop)
                // Keyed on the panel's height, which changes when the screen turns.
                // Watched before the pager and the photo see a touch, so a quick flick that drifts sideways is
                // still a pull, rather than the pager taking it once the drift passes its threshold. Taps and
                // sideways swipes are never consumed here, so they go on through untouched.
                .pointerInput(panelHeightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var total = Offset.Zero
                        // Decided once the finger has moved far enough: putting the photo away, or the details.
                        var pullingAway = false
                        var movingInfo = false

                        val release: (Velocity) -> Unit = { velocity ->
                            if (pullingAway) {
                                if (pull.y > dismissDistancePx || velocity.y > DismissVelocity) currentPutAway(velocity) else springBack(velocity)
                            } else if (movingInfo) {
                                val open = when {
                                    velocity.y < -InfoVelocity -> true
                                    velocity.y > InfoVelocity -> false
                                    else -> info.value > InfoOpenFraction
                                }
                                setInfoOpen(open, -velocity.y / panelHeightPx)
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // A second finger means pinching, which belongs to the photo.
                            if (event.changes.count { it.pressed } > 1) {
                                release(Velocity.Zero)
                                break
                            }
                            if (!change.pressed) {
                                tracker.addPosition(change.uptimeMillis, change.position)
                                release(tracker.calculateVelocity())
                                break
                            }
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val delta = change.positionChange()
                            if (pullingAway) {
                                pull.x += delta.x
                                pull.y += delta.y
                                change.consume()
                                continue
                            }
                            if (movingInfo) {
                                change.consume()
                                scope.launch { info.snapTo((info.value - delta.y / panelHeightPx).coerceIn(0f, 1f)) }
                                continue
                            }
                            // The pager has a sideways swipe, and a zoomed photo or a video's own gestures have theirs.
                            if (change.isConsumed || isZoomed || currentIsWatchingVideo) break
                            total += delta
                            val slop = viewConfiguration.touchSlop
                            if (abs(total.y) > slop && abs(total.y) > abs(total.x)) {
                                change.consume()
                                // Counted from where the finger came down, so nothing jumps as it begins.
                                if (info.value == 0f && total.y > 0f) {
                                    pullingAway = true
                                    pull.settle?.cancel()
                                    pull.pivot = down.position
                                    pull.x += total.x
                                    pull.y += total.y
                                } else {
                                    movingInfo = true
                                    val start = total.y
                                    scope.launch { info.snapTo((info.value - start / panelHeightPx).coerceIn(0f, 1f)) }
                                }
                            } else if (abs(total.x) > slop) {
                                break
                            }
                        }
                    }
                }
                .graphicsLayer {
                    // Opening the details shrinks the photo into the space above them. Pulling it
                    // away shrinks it toward the list it came from, following the finger.
                    val shrink = pageScale()
                    val pivot = pagePivot()
                    scaleX = shrink
                    scaleY = shrink
                    transformOrigin = TransformOrigin(
                        (pivot.x / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                        (pivot.y / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    )
                    translationX = pull.x
                    translationY = pull.y - info.value * panelHeightPx / 2f
                }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 16.dp,
                beyondViewportPageCount = 1,
                userScrollEnabled = !isZoomed && info.value == 0f && !isWatchingVideo
            ) { page ->
                files.getOrNull(page)?.let { file ->
                    val isCurrent = page == pagerState.currentPage
                    val onTap = {
                        when {
                            slideshow != null -> {
                                slideshow = null
                                chromeVisible = true
                            }
                            info.value > 0f -> setInfoOpen(false)
                            else -> chromeVisible = !chromeVisible
                        }
                    }
                    if (file.type == "video") {
                        VideoPage(
                            file = file,
                            session = video,
                            isActive = isCurrent && page == pagerState.settledPage,
                            chromeVisible = chromeVisible,
                            controls = videoControls,
                            onTap = onTap,
                            onOpenWith = { openWithChooser(context, file) },
                            onShowDetails = { setInfoOpen(true) }
                        )
                    } else {
                        ZoomablePhoto(
                            file = file,
                            isCurrent = isCurrent,
                            filePage = if (isCurrent) shownPage else 0,
                            rotation = if (isCurrent) rotation else 0,
                            revision = turned,
                            motion = extras.motion.takeIf { isCurrent },
                            motionPlaying = isCurrent && motionPlaying,
                            onMotionHold = { motionPlaying = it },
                            panoramaRequest = if (isCurrent) panoramaRequest else 0,
                            onOpenWith = { openWithChooser(context, file) },
                            onTap = onTap,
                            onZoomChange = { zoomed ->
                                isZoomed = zoomed
                                // A zoomed photo gets the whole screen.
                                if (zoomed) chromeVisible = false
                            }
                        )
                    }
                }
            }
        }

        currentFile?.let { file ->
            var detailsAll by remember(file.path) { mutableStateOf(false) }
            val detailsHeight by animateDpAsState(
                targetValue = if (detailsAll) maxHeight * InfoPanelAllShare else panelHeight,
                animationSpec = com.kobblo.gallery.file.explorer.ui.design.KobbloMotion.smooth(),
                label = "details_height"
            )
            MediaDetailsPanel(
                file = file,
                details = details,
                onShowAllChange = { detailsAll = it },
                onShowInFolder = {
                    File(file.path).parent?.let(onNavigateToFolder)
                    onClose()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .height(detailsHeight)
                    .graphicsLayer {
                        translationY = (1f - info.value) * size.height
                        alpha = if (info.value > 0f) 1f else 0f
                    }
            )
        }

        // The back gesture previews putting the photo away: it's pulled down as the gesture is drawn,
        // and letting go carries on into the flight home. Giving up lets it spring back.
        PredictiveBackHandler(enabled = backPutsAway) { progress ->
            try {
                progress.collect { event ->
                    pull.settle?.cancel()
                    pull.pivot = Offset.Unspecified
                    val eased = 1f - (1f - event.progress) * (1f - event.progress)
                    pull.x = 0f
                    pull.y = screenHeightPx * 0.16f * eased
                }
                putAway(Velocity.Zero)
            } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                springBack(Velocity.Zero)
                throw cancelled
            }
        }

        if (chromeShown > 0f && currentFile != null && !isInPip) {
            // Read while drawing, so pulling doesn't recompose the chrome on every frame.
            val pullFade = { (1f - abs(pull.y) / dismissDistancePx).coerceIn(0f, 1f) }

            val (date, time) = remember(currentFile.lastModified) { photoDateLines(context, currentFile.lastModified) }
            ViewerTopBar(
                // A file from another app may have no date, and its name says more anyway.
                title = date.ifEmpty { currentFile.name },
                subtitle = time,
                backdrop = backdrop,
                onBack = {
                    haptics.tap()
                    putAway(Velocity.Zero)
                },
                onMore = {
                    haptics.tap()
                    showMoreSheet = true
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        alpha = chromeShown * pullFade()
                        translationY = (chromeShown - 1f) * chromeLiftPx
                    }
            )

            PhotoBadges(
                extras = extras,
                shownPage = shownPage,
                backdrop = backdrop,
                onPageChange = { shownPage = it },
                onPlayMotion = { motionPlaying = true },
                onPanorama = { panoramaRequest++ },
                onSphere = {
                    haptics.tap()
                    sphereOpen = true
                }.takeIf { extras.sphere != null && !sphereFailed && com.kobblo.gallery.file.explorer.viewer.sphere.canDrawSphere(context) },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .graphicsLayer { alpha = chromeShown * pullFade() * (1f - info.value) }
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(bottom = 12.dp)
                    .graphicsLayer {
                        alpha = chromeShown * pullFade()
                        translationY = (1f - chromeShown) * chromeLiftPx
                    }
            ) {
                // A photo with words in it offers them, at its corner, as a phone's photo library does.
                val words = photoText
                if (!words.isNullOrEmpty() && currentFile.type == "image") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 12.dp)
                            .graphicsLayer { alpha = 1f - info.value },
                        horizontalArrangement = Arrangement.End
                    ) {
                        GlassCircle(
                            icon = AppIcons.FileText,
                            description = stringResource(R.string.smart_text_button),
                            backdrop = backdrop,
                            onClick = {
                                haptics.tap()
                                showText = true
                            },
                            size = 40.dp
                        )
                    }
                }
                // On a video its scrubber sits above the strip, and the strip stays where it is on every
                // page, so nothing jumps while swiping between photos and videos.
                if (currentFile.type == "video" && video.hasPlayer && video.problem == null) {
                    val neighbors = com.kobblo.gallery.file.explorer.viewer.video.rememberVideoNeighbors(files, pagerState.currentPage)
                    if (slideshow == null) {
                        com.kobblo.gallery.file.explorer.viewer.video.VideoUpNext(
                            session = video,
                            nextIndex = neighbors.next,
                            nextName = files.getOrNull(neighbors.next)?.name.orEmpty(),
                            backdrop = backdrop,
                            onPlay = { scope.launch { pagerState.scrollToPage(it) } }
                        )
                    }
                    // Beside the scrubber only when turned on its side. On a phone upright, swiping does the same.
                    val roomy = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 480
                    VideoScrubber(
                        session = video,
                        backdrop = backdrop,
                        onScrubbingChange = { isScrubbing = it },
                        modifier = Modifier.graphicsLayer { alpha = 1f - info.value },
                        onPrevious = neighbors.previous.takeIf { roomy && it >= 0 }?.let { target -> { scope.launch { pagerState.scrollToPage(target) } } },
                        onNext = neighbors.next.takeIf { roomy && it >= 0 }?.let { target -> { scope.launch { pagerState.scrollToPage(target) } } }
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (files.size > 1) {
                    PhotoStrip(
                        files = files,
                        pagerState = pagerState,
                        modifier = Modifier.graphicsLayer { alpha = 1f - info.value }
                    )
                    Spacer(Modifier.height(12.dp))
                }
                ViewerToolbar(
                    file = currentFile,
                    infoOpen = info.targetValue == 1f,
                    backdrop = backdrop,
                    onShare = { shareFile(context, currentFile) },
                    onToggleFavorite = { onAction(AppAction.TogglePin(currentFile.path)) },
                    onToggleInfo = { setInfoOpen(info.targetValue != 1f) },
                    onEdit = { editImage(context, currentFile) },
                    onDelete = {
                        haptics.tap()
                        showDeleteConfirm = true
                    }
                )
            }
        }

        val sphere = extras.sphere
        if (sphereOpen && sphere != null && currentPath != null) {
            com.kobblo.gallery.file.explorer.viewer.sphere.SphereViewer(
                path = currentPath,
                projection = sphere,
                onClose = { sphereOpen = false },
                onFailed = {
                    sphereFailed = true
                    sphereOpen = false
                }
            )
        }

        if (video.isLocked) {
            com.kobblo.gallery.file.explorer.viewer.video.VideoLockOverlay(video, lockNudge, onUnlocked = { chromeVisible = true })
        }
    }

    if (showText) {
        photoText?.let { com.kobblo.gallery.file.explorer.smart.PhotoTextSheet(it) { showText = false } }
    }

    if (showMoreSheet && currentFile != null) {
        val file = currentFile
        val done = { message: String -> onAction(AppAction.ShowToast(message)) }
        val failed = context.getString(R.string.viewer_metadata_failed)
        ViewerMoreSheet(
            file = file,
            video = video.takeIf { file.type == "video" },
            fileCount = files.size,
            hasLocation = details.hasLocation,
            hasMotionClip = extras.motion != null,
            onDismiss = { showMoreSheet = false },
            actions = MoreActions(
                onAddToAlbum = { showAlbumPicker = true },
                onRename = { showRename = true },
                onCopyImage = { com.kobblo.gallery.file.explorer.viewer.PhotoActions.copyImage(context, file) },
                onSetAs = { com.kobblo.gallery.file.explorer.viewer.PhotoActions.setAs(context, file) },
                onPrint = { com.kobblo.gallery.file.explorer.viewer.PhotoActions.print(context, file) },
                onSaveFrame = {
                    video.refreshPosition()
                    val at = video.positionMs
                    scope.launch {
                        try {
                            val saved = com.kobblo.gallery.file.explorer.viewer.PhotoActions.saveFrame(context, file, at)
                            done(context.getString(R.string.viewer_frame_saved, saved.name))
                        } catch (_: Exception) {
                            done(context.getString(R.string.viewer_frame_failed))
                        }
                    }
                },
                onUseAsCover = (com.kobblo.gallery.file.explorer.gallery.GalleryNav.current as? com.kobblo.gallery.file.explorer.gallery.GalleryDest.Folder)?.takeIf {
                    it.path == file.path.substringBeforeLast('/')
                }?.let { album ->
                    {
                        com.kobblo.gallery.file.explorer.gallery.GalleryStore.get(context).setAlbumCover(album.path, file.path)
                        done(context.getString(R.string.viewer_cover_set, album.name))
                    }
                },
                onOpenWith = { openWithChooser(context, file) },
                onSetWallpaper = { setAsWallpaper(context, file) },
                onRotate = {
                    when {
                        file.type == "video" -> activity?.let {
                            toggleForcedOrientation(it)
                            orientationForced = true
                        }
                        // A tag rather than the picture, so it's for good, with nothing lost, and can be turned back.
                        com.kobblo.gallery.file.explorer.viewer.metadata.PhotoRotation.canRotate(file) -> scope.launch {
                            try {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.kobblo.gallery.file.explorer.viewer.metadata.PhotoRotation.rotate(context, file.path, 90)
                                }
                                turned++
                                onAction(AppAction.Reload)
                                done(context.getString(R.string.viewer_rotated))
                            } catch (_: Exception) {
                                done(context.getString(R.string.viewer_rotate_failed))
                            }
                        }
                        else -> rotation = (rotation + 90) % 360
                    }
                },
                onShowInFolder = {
                    File(file.path).parent?.let(onNavigateToFolder)
                    onClose()
                },
                onPictureInPicture = {
                    scope.launch { info.snapTo(0f) }
                    activity?.let { enterPictureInPicture(it, video.videoAspect) }
                },
                onLockControls = {
                    scope.launch { info.snapTo(0f) }
                    chromeVisible = false
                    video.isLocked = true
                },
                onVideoRotation = { video.rotationRevision++ },
                onSubtitleAdded = { added ->
                    if (!added) done(context.getString(R.string.viewer_video_subtitles_not_readable))
                },
                onStartSlideshow = { seconds, skipVideos ->
                    scope.launch { info.snapTo(0f) }
                    slideshow = SlideshowRun(seconds, skipVideos)
                },
                onSaveMotionClip = {
                    val clip = extras.motion
                    if (clip != null) scope.launch {
                        try {
                            val saved = MotionClips.saveBeside(context, file, clip)
                            done(context.getString(R.string.viewer_motion_saved, saved.name))
                        } catch (_: Exception) {
                            done(failed)
                        }
                    }
                },
                onShareClean = {
                    scope.launch {
                        try {
                            val clean = MetadataActions.cleanCopyForSharing(context, file)
                            MetadataActions.share(context, clean, FileKinds.mimeTypeOf(clean.name))
                        } catch (_: Exception) {
                            done(failed)
                        }
                    }
                },
                onRemoveLocationCopy = {
                    scope.launch {
                        try {
                            val copy = MetadataActions.saveCopyWithoutLocation(context, file)
                            done(context.getString(R.string.viewer_location_removed_copy, copy.name))
                        } catch (_: Exception) {
                            done(failed)
                        }
                    }
                },
                onRemoveLocationReplace = {
                    scope.launch {
                        try {
                            MetadataActions.removeLocationInPlace(context, file)
                            detailsVersion++
                            done(context.getString(R.string.viewer_location_removed))
                        } catch (_: Exception) {
                            done(failed)
                        }
                    }
                }
            )
        )
    }

    if (showAlbumPicker && currentFile != null) {
        val file = currentFile
        // Opened from a folder, the Home feed may not have been read yet, and it's where the list of albums comes from.
        val source by produceState(allMedia, allMedia) {
            if (allMedia.isEmpty()) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.kobblo.gallery.file.explorer.FileRepository(context.applicationContext).getGalleryMedia(showHidden = false)
                }
            }
        }
        com.kobblo.gallery.file.explorer.gallery.AlbumPickerSheet(
            files = source,
            initiallyMoving = true,
            onPick = { folder, move ->
                showAlbumPicker = false
                com.kobblo.gallery.file.explorer.gallery.AlbumTransfer.start(context, listOf(file), folder, move) { done, failed ->
                    val message = if (failed > 0) {
                        context.resources.getQuantityString(R.plurals.gallery_transfer_failed, failed, failed)
                    } else {
                        context.resources.getQuantityString(if (move) R.plurals.gallery_moved_to else R.plurals.gallery_copied_to, done, done, folder.name)
                    }
                    onAction(AppAction.ShowToast(message))
                    onAction(AppAction.Reload)
                }
                if (move) onMovedAway(file)
            },
            onFailed = { message ->
                showAlbumPicker = false
                onAction(AppAction.ShowToast(message))
            },
            onDismiss = { showAlbumPicker = false }
        )
    }

    if (showRename && currentFile != null) {
        val file = currentFile
        val extension = file.name.substringAfterLast('.', "")
        com.kobblo.gallery.file.explorer.gallery.AlbumNameSheet(
            title = stringResource(R.string.viewer_rename),
            initial = file.name.substringBeforeLast('.'),
            confirmLabel = stringResource(R.string.viewer_rename),
            onConfirm = { typed ->
                showRename = false
                val name = com.kobblo.gallery.file.explorer.gallery.sanitizeAlbumName(typed)
                if (name != null) onAction(AppAction.RenameFile(file.path, if (extension.isEmpty()) name else "$name.$extension"))
            },
            onDismiss = { showRename = false },
            hint = stringResource(R.string.viewer_rename_hint)
        )
    }

    if (showDeleteConfirm) {
        val target = currentFile
        ExpressiveConfirmationSheet(
            title = if (target != null) stringResource(R.string.view_delete_named_title, target.name) else stringResource(R.string.view_delete_image_title),
            subtitle = if (recycleBinEnabled) stringResource(R.string.view_delete_moves_to_bin) else stringResource(R.string.delete_warning_undone),
            onConfirm = {
                haptics.delete()
                showDeleteConfirm = false
                if (target != null) onDeleteClick(target)
            },
            onDismissRequest = {
                haptics.tap()
                showDeleteConfirm = false
            }
        )
    }
}

@Composable
internal fun MoreRow(
    title: String,
    icon: ImageVector,
    tint: Color,
    showDivider: Boolean = false,
    onClick: () -> Unit
) {
    KobbloRow(
        title = title,
        trailing = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) },
        showDivider = showDivider,
        onClick = onClick
    )
}

// ── The photo ─────────────────────────────────────────────────────────────────

private const val MaxZoom = 6f
private const val DoubleTapZoom = 2.5f
private const val SlideshowPauseAfterVideo = 400L

/**
 * One photo. Pinching zooms around the fingers, and double-tapping zooms into the tapped spot. Until
 * it is zoomed, a one-finger drag is left for the pager and the viewer to read.
 */
@Composable
private fun ZoomablePhoto(
    file: FileItem,
    isCurrent: Boolean,
    filePage: Int,
    rotation: Int,
    revision: Int,
    motion: MotionClip?,
    motionPlaying: Boolean,
    onMotionHold: (Boolean) -> Unit,
    panoramaRequest: Int,
    onOpenWith: () -> Unit,
    onTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = ExpressiveMotion.isReducedMotion()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var imageSize by remember { mutableStateOf(Size.Unspecified) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var failed by remember(file.path, filePage) { mutableStateOf(false) }
    val isSideways = rotation % 180 != 0
    val currentOnTap by rememberUpdatedState(onTap)
    val currentMotion by rememberUpdatedState(motion)
    val currentMotionPlaying by rememberUpdatedState(motionPlaying)
    val currentOnMotionHold by rememberUpdatedState(onMotionHold)

    // The picture as it stands on screen, which turning it for this view swaps the sides of.
    fun displaySize(): Size = when {
        !imageSize.isSpecified -> Size.Unspecified
        isSideways -> Size(imageSize.height, imageSize.width)
        else -> imageSize
    }

    // How far to scale the image's own fit so that turned on its side it still fits the screen.
    fun rotationFit(): Float {
        val shown = displaySize()
        val box = boxSize
        if (!isSideways || !shown.isSpecified || box == IntSize.Zero || imageSize.width <= 0f || imageSize.height <= 0f) return 1f
        val upright = minOf(box.width / imageSize.width, box.height / imageSize.height)
        val turned = minOf(box.width / shown.width, box.height / shown.height)
        return turned / upright
    }

    // A panorama's own fit is a thin band, so the zoom that fills the screen's height can be large.
    fun fillHeightScale(): Float {
        val shown = displaySize()
        val box = boxSize
        if (!shown.isSpecified || shown.height <= 0f || box == IntSize.Zero) return MaxZoom
        val fit = minOf(box.width / shown.width, box.height / shown.height)
        return (box.height / (shown.height * fit)).coerceAtLeast(1f)
    }

    // The photo's edges on screen, which is what panning stops at, rather than the screen's.
    fun clamp(target: Offset, atScale: Float): Offset {
        val box = boxSize
        if (box == IntSize.Zero) return Offset.Zero
        val shown = displaySize()
        val fit = if (shown.isSpecified && shown.width > 0f && shown.height > 0f) {
            minOf(box.width / shown.width, box.height / shown.height)
        } else null
        val shownWidth = (fit?.let { shown.width * it } ?: box.width.toFloat()) * atScale
        val shownHeight = (fit?.let { shown.height * it } ?: box.height.toFloat()) * atScale
        val maxX = ((shownWidth - box.width) / 2f).coerceAtLeast(0f)
        val maxY = ((shownHeight - box.height) / 2f).coerceAtLeast(0f)
        return Offset(target.x.coerceIn(-maxX, maxX), target.y.coerceIn(-maxY, maxY))
    }

    fun animateZoom(toScale: Float, toOffset: Offset) {
        val fromScale = scale
        val fromOffset = offset
        scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = if (reduced) snap() else KobbloMotion.smooth()
            ) { progress, _ ->
                scale = fromScale + (toScale - fromScale) * progress
                offset = lerp(fromOffset, toOffset, progress)
            }
        }
    }

    // Swiping away and back returns a photo to its normal size.
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(isCurrent) {
        if (isCurrent) {
            snapshotFlow { scale > 1.01f }
                .distinctUntilChanged()
                .collect(onZoomChange)
        }
    }

    // Asked for from the badge: pan across the panorama, or come back out of it.
    LaunchedEffect(panoramaRequest) {
        if (panoramaRequest > 0) {
            if (scale > 1.01f) animateZoom(1f, Offset.Zero) else animateZoom(fillHeightScale(), Offset.Zero)
        }
    }

    val request = remember(file.path, filePage, revision) {
        ViewerImages.fullRequest(context, file, filePage)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            // Keyed on the rotation because panning limits depend on which way the photo is turned.
            .pointerInput(rotation) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            animateZoom(1f, Offset.Zero)
                        } else {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            animateZoom(DoubleTapZoom, clamp((center - tap) * (DoubleTapZoom - 1f), DoubleTapZoom))
                        }
                    },
                    // Holding a motion photo plays it for as long as the finger stays.
                    onLongPress = { if (currentMotion != null) currentOnMotionHold(true) },
                    onPress = {
                        tryAwaitRelease()
                        if (currentMotion != null && currentMotionPlaying) currentOnMotionHold(false)
                    }
                )
            }
            .pointerInput(rotation) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // The event that lifts the last finger has no centroid, and doing sums with
                        // it would leave the photo at a position of not-a-number.
                        val centroid = event.calculateCentroid()
                        if ((fingers >= 2 || scale > 1.01f) && centroid.isSpecified) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val focus = centroid - Offset(size.width / 2f, size.height / 2f)
                            val newScale = (scale * zoom).coerceIn(0.85f, maxOf(MaxZoom, fillHeightScale()))
                            // Keeps whatever is under the fingers under the fingers.
                            val moved = offset + pan - (focus - offset) * (newScale / scale - 1f)
                            scale = newScale
                            offset = clamp(moved, newScale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1f) animateZoom(1f, Offset.Zero)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (failed) {
            KobbloMessage(
                icon = AppIcons.Photo,
                title = stringResource(R.string.viewer_cant_show_title),
                body = stringResource(R.string.viewer_cant_show_body),
                // It has to read on the black a hidden chrome leaves, as well as on white.
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clip(com.kobblo.gallery.file.explorer.ui.design.KobbloShapes.lg)
                    .background(KobbloDesign.colors.snow),
                action = {
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        com.kobblo.gallery.file.explorer.ui.design.Button(onClick = onOpenWith) {
                            Text(stringResource(R.string.viewer_open_with))
                        }
                        // Its name may simply be wrong, and Inspect shows what it really is.
                        com.kobblo.gallery.file.explorer.ui.design.TextButton(
                            onClick = { com.kobblo.gallery.file.explorer.ui.hub.Hub.open(com.kobblo.gallery.file.explorer.tools.ToolsRoute.Inspect(file.path)) }
                        ) {
                            Text(stringResource(R.string.tools_inspect), color = KobbloDesign.colors.action)
                        }
                    }
                }
            )
        } else {
            AsyncImage(
                model = request,
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                onSuccess = {
                    imageSize = it.painter.intrinsicSize
                    failed = false
                    // The shape the flight home lands with, for photos the index hasn't measured.
                    if (filePage == 0 && imageSize.isSpecified) {
                        com.kobblo.gallery.file.explorer.gallery.PhotoFlight.rememberAspect(file.path, imageSize.width, imageSize.height)
                    }
                },
                onError = { failed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val turned = rotationFit()
                        scaleX = scale * turned
                        scaleY = scale * turned
                        translationX = offset.x
                        translationY = offset.y
                        rotationZ = rotation.toFloat()
                    }
            )
            if (isCurrent && rotation == 0 && !file.isExternalStream && supportsRegionDecoding(file.name)) {
                androidx.compose.runtime.key(revision) {
                    SharpRegionLayer(path = file.path, scale = scale, offset = offset, boxSize = boxSize)
                }
            }
            if (motionPlaying && motion != null) {
                MotionClipOverlay(
                    file = file,
                    clip = motion,
                    onEnded = { onMotionHold(false) },
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                )
            }
        }
    }
}

// ── The top bar ───────────────────────────────────────────────────────────────

/** The bar every viewer shares: back, what you are looking at, and more. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ViewerTopBar(
    title: String,
    subtitle: String,
    backdrop: Backdrop,
    onBack: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KobbloDesign.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            // On its side a phone's nav bar is here, over the More button, and tapping it would miss.
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        GlassCircle(
            icon = AppIcons.Back,
            description = stringResource(R.string.back),
            backdrop = backdrop,
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 56.dp)
                .kobbloGlass(backdrop, CapsuleShape)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = KobbloType.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = KobbloType.caption.copy(fontWeight = FontWeight.Normal),
                    color = colors.smoke,
                    maxLines = 1
                )
            }
        }
        GlassCircle(
            icon = AppIcons.MoreHoriz,
            description = stringResource(R.string.more_options),
            backdrop = backdrop,
            onClick = onMore,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

private fun photoDateLines(context: Context, millis: Long): Pair<String, String> {
    if (millis <= 0L) return "" to ""
    val date = when {
        DateUtils.isToday(millis) -> context.getString(R.string.recents_today)
        DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.recents_yesterday)
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }
    return date to android.text.format.DateFormat.getTimeFormat(context).format(Date(millis))
}

@Composable
internal fun GlassCircle(
    icon: ImageVector,
    description: String,
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    tint: Color = KobbloDesign.colors.ink
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .pressScale(pressedScale = 0.9f, interactionSource = source)
            .kobbloGlass(backdrop, CapsuleShape)
            .quietClickable(source = source, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

// ── The thumbnail strip ───────────────────────────────────────────────────────

private val StripHeight = 44.dp
private val StripItemWidth = 30.dp
private val StripCurrentWidth = 52.dp
private val StripGap = 2.dp

/** Who moves the strip: the pager it follows, a finger or fling on it, or a glide to a tapped photo. */
private enum class StripLead { Pager, Finger, Glide }

/**
 * Every photo in the folder as a row of slivers, the one on screen widened at the center, as a phone's
 * photo library has it. The strip's position is one number that follows the pager exactly, even halfway
 * through a swipe, and each sliver's width and place are worked out from it while laying out. So the
 * widened one grows and shrinks as it passes the middle rather than jumping, and nothing recomposes as
 * it moves. Dragging the strip scrubs through the photos with a light tick for each, and a fling carries
 * on and settles on one. Tapping a sliver shows that photo and glides the strip to it.
 */
@Composable
private fun PhotoStrip(
    files: List<FileItem>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    modifier: Modifier = Modifier
) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val reduced = ExpressiveMotion.isReducedMotion()
    val last = files.lastIndex.coerceAtLeast(0)
    var lead by remember { mutableStateOf(StripLead.Pager) }
    var stripAt by remember { mutableFloatStateOf(pagerState.currentPage.toFloat()) }
    val motion = remember { arrayOfNulls<Job>(1) }

    // Read while laying out only, so following a swipe re-lays the strip without recomposing it.
    val position: () -> Float = {
        if (lead == StripLead.Pager) (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, last.toFloat())
        else stripAt
    }

    // A finger or a fling leads: the photo on screen changes as each sliver reaches the middle.
    LaunchedEffect(lead) {
        if (lead != StripLead.Finger) return@LaunchedEffect
        snapshotFlow { stripAt.roundToInt().coerceIn(0, last) }
            .distinctUntilChanged()
            .collect { page ->
                if (page != pagerState.currentPage) {
                    haptics.tick()
                    pagerState.scrollToPage(page)
                }
            }
    }

    fun glideTo(index: Int) {
        motion[0]?.cancel()
        val from = position()
        lead = StripLead.Glide
        stripAt = from
        motion[0] = scope.launch {
            try {
                pagerState.scrollToPage(index)
                if (!reduced) animate(from, index.toFloat(), animationSpec = StripSpring) { v, _ -> stripAt = v }
            } finally {
                lead = StripLead.Pager
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val itemPx = with(density) { StripItemWidth.toPx() }
        val extraPx = with(density) { (StripCurrentWidth - StripItemWidth).toPx() }
        val pitchPx = itemPx + with(density) { StripGap.toPx() }
        val widthPx = constraints.maxWidth.toFloat()
        // Only the slivers that can be on screen are built, a few either side of the middle.
        val reach = (widthPx / pitchPx / 2f).toInt() + 2
        val center by remember { derivedStateOf { position().roundToInt() } }
        val shown = (center - reach).coerceAtLeast(0)..(center + reach).coerceAtMost(last)

        androidx.compose.ui.layout.Layout(
            content = {
                for (index in shown) {
                    val file = files[index]
                    androidx.compose.runtime.key(file.path) {
                        val request = remember(file.path) {
                            ImageRequest.Builder(context).data(ViewerImages.model(file)).size(180).build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = file.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .layoutId(index)
                                .clip(RoundedRectangle(3.dp))
                                .background(colors.mist)
                                .quietClickable {
                                    haptics.tap()
                                    glideTo(index)
                                }
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(StripHeight)
                .clipToBounds()
                .pointerInput(last, pitchPx) {
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            motion[0]?.cancel()
                            tracker.resetTracking()
                            stripAt = position()
                            lead = StripLead.Finger
                        },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            // A little give past either end, the way a list overscrolls.
                            stripAt = (stripAt - dx / pitchPx).coerceIn(-StripOverscroll, last + StripOverscroll)
                        },
                        onDragEnd = {
                            val velocity = -tracker.calculateVelocity().x / pitchPx
                            motion[0] = scope.launch {
                                try {
                                    // Where a fling would coast to, then the nearest photo to that, settling on it.
                                    val coast = androidx.compose.animation.core.exponentialDecay<Float>(frictionMultiplier = 2.2f)
                                        .calculateTargetValue(stripAt, velocity)
                                    val target = coast.roundToInt().coerceIn(0, last).toFloat()
                                    if (reduced) stripAt = target
                                    else animate(stripAt, target, velocity, StripSpring) { v, _ -> stripAt = v }
                                    if (pagerState.currentPage != target.toInt()) pagerState.scrollToPage(target.toInt())
                                } finally {
                                    lead = StripLead.Pager
                                }
                            }
                        },
                        onDragCancel = {
                            motion[0] = scope.launch {
                                try {
                                    val target = stripAt.roundToInt().coerceIn(0, last)
                                    pagerState.scrollToPage(target)
                                } finally {
                                    lead = StripLead.Pager
                                }
                            }
                        }
                    )
                }
        ) { measurables, constraints ->
            val f = position()
            val height = constraints.maxHeight
            val mid = constraints.maxWidth / 2f
            val placed = measurables.map { measurable ->
                val index = measurable.layoutId as Int
                val d = index - f
                // The widened sliver is whichever is nearest the middle, growing as it arrives, and its
                // neighbours make room for exactly the width it gains, so the gaps never change.
                val grow = (1f - kotlin.math.abs(d)).coerceIn(0f, 1f)
                val width = itemPx + extraPx * grow
                val middle = mid + d * pitchPx + extraPx / 2f * d.coerceIn(-1f, 1f)
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width.roundToInt(), height))
                placeable to (middle - width / 2f).roundToInt()
            }
            layout(constraints.maxWidth, height) {
                placed.forEach { (placeable, x) -> placeable.place(x, 0) }
            }
        }
    }
}

/** Gently, with no bounce: a strip that overshoots reads as wobbling. */
private val StripSpring = spring<Float>(dampingRatio = 1f, stiffness = 420f)

/** How far past the first or last photo the strip gives under a finger, in slivers. */
private const val StripOverscroll = 0.35f

// ── The toolbar ───────────────────────────────────────────────────────────────

@Composable
private fun ViewerToolbar(
    file: FileItem,
    infoOpen: Boolean,
    backdrop: Backdrop,
    onShare: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleInfo: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    // Straight from where favorites live, so the star answers the tap rather than the feed's next rebuild.
    val favorites by remember(context) { com.kobblo.gallery.file.explorer.gallery.Favorites.paths(context) }.collectAsState()
    val isFavorite = file.path in favorites
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // A stream from another app isn't ours to favorite, change or delete. Nor is a Vault file from
        // here: its own menu deletes it for good, where this would move it to the Bin in shared storage.
        val isStream = file.isExternalStream || file.isInVault
        val canDelete = !isStream && !file.isOpenedFromOutside
        GlassCircle(icon = AppIcons.Share, description = stringResource(R.string.share), backdrop = backdrop, onClick = onShare, size = 52.dp)
        Row(
            modifier = Modifier
                .height(52.dp)
                .kobbloGlass(backdrop, CapsuleShape)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isStream) {
                ToolbarAction(
                    icon = if (isFavorite) AppIcons.StarFill else AppIcons.Star,
                    description = stringResource(if (isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites),
                    tint = if (isFavorite) colors.action else colors.ink,
                    onClick = onToggleFavorite
                )
            }
            ToolbarAction(
                icon = AppIcons.Info,
                description = stringResource(R.string.viewer_details),
                tint = if (infoOpen) colors.action else colors.ink,
                onClick = onToggleInfo
            )
            if (!isStream && file.type == "image") {
                ToolbarAction(icon = AppIcons.Pencil, description = stringResource(R.string.edit_action), tint = colors.ink, onClick = onEdit)
            }
        }
        if (!canDelete) {
            Spacer(Modifier.size(52.dp))
        } else {
            GlassCircle(
                icon = AppIcons.Trash,
                description = stringResource(R.string.delete_label),
                backdrop = backdrop,
                onClick = onDelete,
                size = 52.dp,
                tint = colors.danger
            )
        }
    }
}

@Composable
internal fun ToolbarAction(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    val haptics = LocalHaptics.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 44.dp)
            .pressScale(pressedScale = 0.88f, interactionSource = source)
            .clip(CapsuleShape)
            .quietClickable(source = source) {
                haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

// ── Handing a photo to other apps ─────────────────────────────────────────────

private fun launchChooser(context: Context, intent: Intent, title: String) {
    try {
        context.startActivity(Intent.createChooser(intent, title))
    } catch (_: Exception) {
    }
}

private fun editImage(context: Context, file: FileItem) {
    if (com.kobblo.gallery.file.explorer.gallery.editor.PhotoEditorActivity.open(context, file.path)) return
    launchChooser(
        context,
        Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(viewerUriFor(context, file), "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        context.getString(R.string.view_edit_image)
    )
}
