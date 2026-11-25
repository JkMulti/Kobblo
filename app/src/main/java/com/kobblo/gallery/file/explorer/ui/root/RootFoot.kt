package com.kobblo.gallery.file.explorer.ui.root

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.archive.ArchiveViewModel
import com.kobblo.gallery.file.explorer.gallery.GalleryNav
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.MiniMusicPlayer
import com.kobblo.gallery.file.explorer.ui.TabSwitchLatencyTracker
import com.kobblo.gallery.file.explorer.ui.UnifiedBottomPill
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveNavShell
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveTab
import com.kyant.backdrop.Backdrop

/** On a tablet the bar floats at the size it was drawn for, rather than stretching edge to edge. */
private val FootMaxWidth = 600.dp

private val FootGap = 12.dp

/** Whether the foot of the screen has a bar at all: the tabs, on the pages that keep them, or one for files in hand. */
internal fun showsFootBar(page: RootPage, state: AppState): Boolean =
    page.keepsTabBar || state.isSelectionMode || state.clipboardPaths.isNotEmpty()

/** How far up from the bottom edge messages float, so as to clear whatever bar is there. */
internal fun toastLift(navigationBar: Dp, selecting: Boolean): Dp {
    // Three buttons take the space the bar would stand in. A gesture's line leaves it to the bar.
    val threeButtons = navigationBar > 24.dp
    val barBottom = if (threeButtons) navigationBar + 25.dp else 23.dp
    val barHeight = if (selecting) 52.dp else 56.dp
    return barBottom + barHeight + 14.dp
}

/** What the foot of the screen is given besides the page and the app's state. */
internal class FootNeeds(
    val root: RootMemory,
    val engine: ArchiveViewModel,
    val google: GoogleScreens,
    /** Where the Browse tab leads back to. */
    val browsePlace: State<String>,
    /** What the glass bars show through to. */
    val glass: Backdrop,
    val onAction: (AppAction) -> Unit
)

/** The foot of the screen: the music that's playing, over the tabs or whichever bar has taken their place. */
@Composable
internal fun RootFoot(page: RootPage, state: AppState, needs: FootNeeds) {
    val root = needs.root
    val selection = remember(state.files, state.selectedFiles, state.isSelectionMode) {
        Selection(state.selectionAsListed(), selecting = state.isSelectionMode)
    }
    // Out here, so it outlasts the bar it's shown on.
    var pastingInto by remember { mutableStateOf<String?>(null) }

    // The bars for files in hand make room for the system's own bar themselves.
    val filesInHand = state.isSelectionMode || state.clipboardPaths.isNotEmpty()
    val place = if (filesInHand) Modifier else Modifier.navigationBarsPadding().padding(bottom = FootGap)
    Box(Modifier.fillMaxSize().then(place), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier.widthIn(max = FootMaxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            NowPlaying(state, needs)
            InstallCards(root.installs, selection.apps, offered = selection.isInstallable, state, needs.onAction)
            if (showsFootBar(page, state)) {
                FootBars(selection, state, needs, pastingInto, onPasteInto = { pastingInto = it })
            }
            // Out here rather than with the tabs: those aren't composed while a selection or the clipboard
            // has the bar, so + would do nothing and the sheet would pop up later.
            CreateSheet(root.creating, root.forms, needs.onAction)
            NewPlaceSheet(root.forms, needs.google, needs.onAction)
            NewThingName(root.creating, needs.onAction)
        }
    }
}

/** The little player, over the bar. It gives way to the full one, and to a selection. */
@Composable
private fun NowPlaying(state: AppState, needs: FootNeeds) {
    val still = ExpressiveMotion.isReducedMotion()
    val appearing = fadeIn(KobbloMotion.fadeIn())
    val fading = fadeOut(KobbloMotion.fadeOut())
    AnimatedVisibility(
        visible = state.currentAudioFile != null && !state.showFullScreenPlayer && !state.isSelectionMode,
        enter = if (still) appearing else slideInVertically(KobbloMotion.smooth()) { it / 2 } + appearing,
        exit = if (still) fading else slideOutVertically(KobbloMotion.smooth()) { it / 2 } + fading,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
    ) {
        MiniMusicPlayer(state, needs.onAction, needs.glass)
    }
}

/** The tabs, or the bar for whatever is in hand or under way. */
@Composable
private fun FootBars(
    selection: Selection,
    state: AppState,
    needs: FootNeeds,
    pastingInto: String?,
    onPasteInto: (String?) -> Unit
) {
    val extraction by needs.engine.archiveProgress.collectAsState()
    val tasks = SelectionTasks(selection, needs.root, state, LocalContext.current, LocalHaptics.current, onPasteInto, needs.onAction)
    UnifiedBottomPill(
        state = state,
        archiveProgress = extraction,
        onCancelExtract = needs.engine::cancelExtraction,
        onAction = needs.onAction,
        capturedTargetFolderName = pastingInto,
        onCaptureTargetFolder = onPasteInto,
        selectionModeContent = { SelectionBar(selection, tasks, state) },
        tabBar = { TabBar(selection, tasks, state, needs) },
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = FootGap)
    )
}

/** The tabs. While they give way to the selection bar they're drawn as it, which is what the rest here is for. */
@Composable
private fun TabBar(selection: Selection, tasks: SelectionTasks, state: AppState, needs: FootNeeds) {
    val haptics = LocalHaptics.current
    val trail = needs.root.trail
    val active = tabOf(state, trail.aboutOpen)
    ExpressiveNavShell(
        activeTab = active,
        onTabSelected = { tab ->
            haptics.tap()
            val move = tabMove(tab, state, trail.aboutOpen, needs.browsePlace.value)
            if (move.closesAbout) trail.aboutOpen = false
            move.destination?.let(TabSwitchLatencyTracker::onActionDispatched)
            if (tab == ExpressiveTab.GALLERY) GalleryNav.onHomeTabTapped(alreadyHome = active == tab)
            move.actions.forEach(needs.onAction)
        },
        isSelectionMode = state.isSelectionMode,
        selectedCount = state.selectedFiles.size,
        onClearSelection = tasks::clear, onSelectAll = tasks::selectAll,
        onShare = tasks::share, onCopy = tasks::copy, onMove = tasks::cut, onArchive = tasks::compress, onDelete = tasks::delete,
        onToggleHide = tasks::toggleHide, isAllHidden = selection.isHidden,
        backdrop = needs.glass
    )
}
