package com.kobblo.gallery.file.explorer.ui.root

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.archive.ArchiveViewModel
import com.kobblo.gallery.file.explorer.explorer.ExplorerRuntime
import com.kobblo.gallery.file.explorer.explorer.drag.FileDragModifiers
import com.kobblo.gallery.file.explorer.explorer.drag.FileDragOverlay
import com.kobblo.gallery.file.explorer.fileops.FileOpProgressHost
import com.kobblo.gallery.file.explorer.fileops.JobNoticeHost
import com.kobblo.gallery.file.explorer.music.MusicHost
import com.kobblo.gallery.file.explorer.tools.ToolsRuntime
import com.kobblo.gallery.file.explorer.tools.archive.ArchiveFormats
import com.kobblo.gallery.file.explorer.transfer.ui.TransferOverlays
import com.kobblo.gallery.file.explorer.ui.FullScreenMusicPlayer
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveToastHost
import com.kobblo.gallery.file.explorer.ui.hub.HubHost
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** The pages with the bars at their foot, and every screen, sheet and dialog that opens over them, up to the viewers. */
@Composable
internal fun MainScreen(state: AppState, toasts: SnackbarHostState, onAction: (AppAction) -> Unit, modifier: Modifier = Modifier) {
    val root = remember { RootMemory() }
    val engine: ArchiveViewModel = viewModel()
    ArchiveNews(root.archives, onAction)
    val browsePlace = rememberBrowsePlace(state.location)
    FilesToOpen(root, state, onAction)
    val google = rememberGoogleScreens(root.forms, onAction)
    FileDetailsReader(root.details, onAction)
    val page = state.page(root.trail.aboutOpen)
    RootBack(page, root.trail, state, onAction)

    val pageNeeds = rememberPageNeeds(root, google, toasts, state, onAction)
    // The pages draw into this, so the glass bars can refract what scrolls beneath them.
    val canvas = KobbloDesign.colors.canvas
    val glass = rememberLayerBackdrop {
        drawRect(canvas)
        drawContent()
    }
    val footNeeds = remember(root, engine, google, browsePlace, glass, onAction) {
        FootNeeds(root, engine, google, browsePlace, glass, onAction)
    }
    Scaffold(
        modifier = with(FileDragModifiers) { modifier.host(state.isSelectionMode, state.location, state.currentFolderName, onAction) },
        containerColor = Color.Transparent,
        snackbarHost = { RootToasts(toasts, selecting = state.isSelectionMode) }
    ) { _ ->
        // Each takes the whole screen, and makes its own room for the system's bars.
        PageHost(page, state, pageNeeds, glass)
        RootFoot(page, state, footNeeds)
        ScreensOverPages(state, onAction)
        DialogsOverPages(root, engine, state, onAction)
    }
}

/** Opens what has just been unlocked, and what has just been downloaded to be opened. */
@Composable
private fun FilesToOpen(root: RootMemory, state: AppState, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val unlocked = state.unlockedFileToOpen
    LaunchedEffect(unlocked) {
        if (unlocked == null) return@LaunchedEffect
        if (unlocked.type == "folder") onAction(AppAction.SetLocation(unlocked.path, unlocked.name))
        else FileOpener(context, state, focus, onAction).open(unlocked)
        onAction(AppAction.ClearUnlockedFileToOpen)
    }
    val downloaded = state.downloadedCloudFileToOpen
    LaunchedEffect(downloaded) {
        if (downloaded == null) return@LaunchedEffect
        onAction(AppAction.ClearDownloadedCloudFileToOpen)
        if (ArchiveFormats.isArchiveName(downloaded.name)) root.archives.offer(downloaded)
        else FileOpener(context, state, focus, onAction).open(downloaded)
    }
}

@Composable
private fun rememberPageNeeds(
    root: RootMemory,
    google: GoogleScreens,
    toasts: SnackbarHostState,
    state: AppState,
    onAction: (AppAction) -> Unit
): PageNeeds {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val reducedMotion = ExpressiveMotion.isReducedMotion()
    val kept = rememberSaveableStateHolder()
    val taps = remember(context, focus, scope, root, reducedMotion, state, onAction) {
        FileTaps(context, focus, scope, root.archives, reducedMotion, state, onAction)
    }
    return remember(root, google, toasts, kept, taps, onAction) {
        PageNeeds(root, google, toasts, kept, onFile = taps::on, onAction)
    }
}

/** The app's messages, floating above whatever is at the foot of the screen. */
@Composable
private fun RootToasts(toasts: SnackbarHostState, selecting: Boolean) {
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    ExpressiveToastHost(toasts, Modifier.imePadding().padding(bottom = toastLift(navigationBar, selecting)))
}

/** The screens other parts of the app put over the pages, from the bottom up. */
@Composable
private fun ScreensOverPages(state: AppState, onAction: (AppAction) -> Unit) {
    FileDragOverlay()
    HubHost(state, onAction)
    ToolsRuntime(state, onAction)
    ExplorerRuntime(state, onAction)
    FullPlayer(state, onAction)
    MusicHost(state, onAction)
    FileOpProgressHost()
    JobNoticeHost(onOpenFolder = { folder -> onAction(AppAction.SetLocation(folder)) })
}

/** The music player, full screen, rising from the bottom and sinking back into it. */
@Composable
private fun FullPlayer(state: AppState, onAction: (AppAction) -> Unit) {
    val still = ExpressiveMotion.isReducedMotion()
    AnimatedVisibility(
        visible = state.showFullScreenPlayer,
        enter = if (still) fadeIn(KobbloMotion.fadeIn()) else slideInVertically(KobbloMotion.smooth()) { it },
        exit = if (still) fadeOut(KobbloMotion.fadeOut())
        else slideOutVertically(tween(KobbloMotion.Slow, easing = KobbloMotion.EaseIn)) { it }
    ) {
        FullScreenMusicPlayer(state, onAction)
    }
}

/** The root's own sheets and dialogs. Any of them can be open over any other, and a later one lies over an earlier. */
@Composable
private fun DialogsOverPages(root: RootMemory, engine: ArchiveViewModel, state: AppState, onAction: (AppAction) -> Unit) {
    DeleteSheet(root.deleting, state, onAction)
    DropboxAccount(root.forms, state, onAction)
    ServerForm(root.forms, state, onAction)
    ExtractDialogs(root, engine, state, onAction)
    CompressDialogs(root.archives, state, onAction)
    FileDetailsSheet(root, state, onAction)
    DownloadCover(state)
    TransferOverlays()
    NearbyRequest(state, onAction)
    ReceivedFiles(state, onAction)
    SharedIn(state, onAction)
    HideExplanation(root.hiding, state, onAction)
    HideConflict(state, onAction)
}
