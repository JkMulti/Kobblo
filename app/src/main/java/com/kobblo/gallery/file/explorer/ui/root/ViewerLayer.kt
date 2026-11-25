package com.kobblo.gallery.file.explorer.ui.root

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.gallery.GalleryScope
import com.kobblo.gallery.file.explorer.gallery.PhotoFlight
import com.kobblo.gallery.file.explorer.isCloudPath
import com.kobblo.gallery.file.explorer.ui.DocumentViewerScreen
import com.kobblo.gallery.file.explorer.ui.FileViewerScreen
import com.kobblo.gallery.file.explorer.ui.ImageViewerScreen
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveToastHost
import com.kobblo.gallery.file.explorer.viewer.ExternalMedia
import com.kobblo.gallery.file.explorer.viewer.ViewerQueue
import com.kobblo.gallery.file.explorer.viewer.findActivity

/** The full-screen viewers, over everything but what covers the whole app. */
@Composable
internal fun ViewerLayer(state: AppState, toasts: SnackbarHostState, onAction: (AppAction) -> Unit) {
    val file = state.viewingFile
    PhotoTakeoff(file, onAction)

    // A photo in flight is its own animation, so then the viewer appears and leaves with none.
    val flying = PhotoFlight.file != null
    val (arrive, leave) = viewerSlide()
    AnimatedVisibility(
        visible = file != null && !PhotoFlight.holds(file.path),
        enter = if (flying) EnterTransition.None else arrive,
        exit = if (flying) ExitTransition.None else leave
    ) {
        if (file != null) {
            Viewer(file, state, onAction)
            // The viewers cover the app's own toast host, and what they do has results to report.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                ExpressiveToastHost(toasts, Modifier.navigationBarsPadding().padding(bottom = 88.dp))
            }
        }
    }
}

/**
 * Lets a photo tapped in a grid grow out of its tile first. The viewer is only built once it fills the
 * screen, and Back before then turns it round.
 */
@Composable
private fun PhotoTakeoff(file: FileItem?, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val backdrop = if (KobbloDesign.colors.isDark) Color.Black else Color.White
    LaunchedEffect(file?.path) {
        if (file == null || PhotoFlight.requested != file.path) return@LaunchedEffect
        PhotoFlight.open(context, file, backdrop)
        PhotoFlight.handOver()
    }
    BackHandler(enabled = PhotoFlight.growing) {
        PhotoFlight.turnBack()
        onAction(AppAction.CloseFileViewer)
    }
}

/**
 * How a viewer comes and goes: in from the right like any other screen, and off to the right again. With
 * motion reduced it's simply there, and then not.
 */
@Composable
private fun viewerSlide(): Pair<EnterTransition, ExitTransition> =
    if (ExpressiveMotion.isReducedMotion()) {
        fadeIn(snap()) to fadeOut(snap())
    } else {
        slideInHorizontally(KobbloMotion.smooth()) { it } to slideOutHorizontally(KobbloMotion.smooth()) { it }
    }

private val FileItem.isPicture: Boolean get() = type == "image" || type == "video"

@Composable
private fun Viewer(file: FileItem, state: AppState, onAction: (AppAction) -> Unit) {
    val activity = LocalContext.current.findActivity()
    // Opened from another app, Back belongs to that app.
    val fromOutside = file.kind == ExternalMedia.External
    val leave = {
        onAction(AppAction.CloseFileViewer)
        if (fromOutside) activity?.finish()
    }
    when {
        file.isPicture -> PictureViewer(file, alone = fromOutside, state, onAction, onClose = leave)
        // Going on to edit or inspect a document puts it away, and stays in this app.
        readsAsDocument(file.name, file.type) -> {
            val close = { onAction(AppAction.CloseFileViewer) }
            DocumentViewerScreen(file, onClose = close, onLeave = leave, onAction, state.cornerRoundness)
        }
        else -> FileViewerScreen(file, onClose = leave, state.cornerRoundness)
    }
}

/**
 * The viewer for photos and videos, with the ones around [opened] to swipe to. One that came [alone], or
 * whose neighbours would each have to be downloaded, is the only one in it.
 */
@Composable
private fun PictureViewer(opened: FileItem, alone: Boolean, state: AppState, onAction: (AppAction) -> Unit, onClose: () -> Unit) {
    val remote = isCloudPath(opened.path) || isRemotePlace(state.location)
    val around = remember(state.files, state.location, remote, opened.path) {
        ViewerQueue.files ?: when {
            remote || alone -> listOf(opened)
            else -> GalleryScope.viewerFiles(state.location, state.files).filter { it.isPicture }
        }
    }
    // A deleted photo leaves at once. The feed catches up later, and viewerList keeps the photo that was
    // opened, so the one you just deleted would otherwise stay on screen.
    val gone = remember { mutableStateListOf<String>() }
    val pictures = remember(around, opened.path, gone.size) {
        GalleryScope.viewerList(opened, around).filterNot { it.path in gone }
    }
    key(opened.path) {
        ImageViewerScreen(
            files = pictures,
            initialIndex = pictures.indexOfFirst { it.path == opened.path }.coerceAtLeast(0),
            allMedia = state.galleryFiles,
            recycleBinEnabled = state.isRecycleBinEnabled,
            onAction = onAction,
            onClose = onClose,
            onNavigateToFolder = { folder -> onAction(AppAction.SetLocation(folder)) },
            onDeleteClick = { picture ->
                gone.add(picture.path)
                onAction(AppAction.DeleteFiles(listOf(picture)))
            },
            onMovedAway = { picture -> gone.add(picture.path) }
        )
    }
}
