package com.kobblo.gallery.file.explorer.ui.root

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.CleanerScreen
import com.kobblo.gallery.file.explorer.ui.ScreenCompositionTracker
import com.kobblo.gallery.file.explorer.ui.SettingsScreen
import com.kobblo.gallery.file.explorer.ui.TabSwitchLatencyTracker
import com.kobblo.gallery.file.explorer.ui.TrashScreen
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlassSource
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveAboutScreen
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveBrowseScreen
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveCategoryDetailScreen
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveGalleryScreen
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveHomeScreen
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveLocationsScreen
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveScreenHeader
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSendScreen
import com.kobblo.gallery.file.explorer.ui.expressive.LocalSelectionActive
import com.kobblo.gallery.file.explorer.vault.VaultScreen
import com.kyant.backdrop.backdrops.LayerBackdrop

/** What every page is given besides the app's state. */
internal class PageNeeds(
    val root: RootMemory,
    val google: GoogleScreens,
    val toasts: SnackbarHostState,
    /** Where each page's scroll position and the like are kept while another page is showing. */
    val kept: SaveableStateHolder,
    val onFile: (FileItem) -> Unit,
    val onAction: (AppAction) -> Unit
) {
    val onFileMenu: (FileItem) -> Unit = root.details::open
}

/**
 * The page in front, sliding to the next one when that changes. It follows a Back gesture as it's pulled,
 * touching it puts the keyboard away, and [glass] is given what it draws, for the bars over it to show through.
 */
@Composable
internal fun PageHost(page: RootPage, state: AppState, needs: PageNeeds, glass: LayerBackdrop, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val trail = needs.root.trail
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(focus) { detectTapGestures(onPress = { focus.clearFocus() }) }
            .graphicsLayer {
                scaleX = 1f - trail.pull * 0.1f
                scaleY = scaleX
                translationX = trail.pull * 150f
                alpha = 1f - trail.pull * 0.3f
            }
    ) {
        val nothingToShow = state.files.isEmpty() && state.recentFiles.isEmpty()
        if (page == RootPage.Recents && state.errorMessage != null && nothingToShow) {
            LoadProblem(state.errorMessage, state, needs)
        } else {
            val reducedMotion = ExpressiveMotion.isReducedMotion()
            AnimatedContent(
                targetState = page,
                modifier = Modifier.fillMaxSize().clipToBounds().kobbloGlassSource(glass),
                transitionSpec = { pageChange(reducedMotion) },
                contentKey = { it.id },
                label = "pages"
            ) { shown ->
                WatchPage(shown)
                Page(shown, state, needs)
            }
        }
    }
}

/** A debug build's check on pages that pile up or drag. */
@Composable
private fun WatchPage(page: RootPage) {
    DisposableEffect(page) {
        ScreenCompositionTracker.onScreenEnter(page.id)
        TabSwitchLatencyTracker.onTransitionSettled(page.id)
        onDispose { ScreenCompositionTracker.onScreenExit(page.id) }
    }
}

/** Recents, when there's nothing to show on it and a reason why. */
@Composable
private fun LoadProblem(problem: String, state: AppState, needs: PageNeeds) {
    val question = state.recoverableAuthIntent
    Column(Modifier.fillMaxSize()) {
        ExpressiveScreenHeader(stringResource(R.string.nav_home), Modifier.padding(horizontal = KobbloSpace.screen))
        KobbloMessage(
            icon = AppIcons.Alert,
            title = stringResource(R.string.something_went_wrong),
            body = problem,
            modifier = Modifier.padding(bottom = 110.dp),
            action = {
                if (question != null) {
                    WayOut(stringResource(R.string.grant_permission)) { needs.google.askConsent(question) }
                } else {
                    WayOut(stringResource(R.string.retry)) { needs.onAction(AppAction.Reload) }
                }
            }
        )
    }
}

@Composable
private fun WayOut(label: String, onClick: () -> Unit) {
    Button(onClick = onClick) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Page(page: RootPage, state: AppState, needs: PageNeeds) {
    when (page) {
        RootPage.Cleaner -> CleanerPage(state, needs)
        RootPage.Trash -> TrashPage(state, needs.onAction)
        RootPage.Settings -> SettingsPage(state, needs)
        RootPage.About -> AboutPage(needs.root.trail)
        RootPage.Vault -> VaultPage(state, needs.onAction)
        RootPage.Gallery -> Kept(page, needs) {
            ExpressiveGalleryScreen(state, needs.onAction, needs.onFile, needs.onFileMenu, Modifier.fillMaxSize())
        }
        RootPage.Recents -> Kept(page, needs) {
            ExpressiveHomeScreen(state, needs.onAction, needs.onFile, needs.onFileMenu, Modifier.fillMaxSize())
        }
        RootPage.Locations -> Kept(page, needs) { LocationsPage(state, needs) }
        RootPage.Folder -> Kept(page, needs) { FolderPage(state, needs) }
        RootPage.Send -> ExpressiveSendScreen(state, needs.onAction, Modifier.fillMaxSize())
        is RootPage.Category -> CategoryPage(page, state, needs)
    }
}

/** Keeps a page as it was left, so that coming back to it finds it scrolled where it was. */
@Composable
private fun Kept(page: RootPage, needs: PageNeeds, content: @Composable () -> Unit) = needs.kept.SaveableStateProvider(page.id, content)

@Composable
private fun CleanerPage(state: AppState, needs: PageNeeds) {
    val onAction = needs.onAction
    BackHandler {
        if (state.currentCleanerCategory != null) onAction(AppAction.SetCleanerCategory(null))
        else onAction(AppAction.SetCleanerScreenVisible(false))
    }
    CleanerScreen(
        state = state,
        onAction = onAction,
        snackbarHostState = needs.toasts,
        onNavigateToCategory = { key ->
            needs.root.trail.cleanerWaiting = true
            onAction(AppAction.SetCleanerScreenVisible(false))
            onAction(AppAction.SetLocation(CategoryPrefix + key))
        }
    )
}

@Composable
private fun TrashPage(state: AppState, onAction: (AppAction) -> Unit) {
    val close = { onAction(AppAction.SetTrashScreenVisible(false)) }
    BackHandler(onBack = close)
    TrashScreen(state, onAction, close)
}

@Composable
private fun SettingsPage(state: AppState, needs: PageNeeds) {
    val onAction = needs.onAction
    // A tab, so Back goes Home as it does from the others.
    BackHandler {
        onAction(AppAction.SetShowSettingsScreen(false))
        onAction(AppAction.SetLocation("gallery"))
    }
    // Keeps its place while About is open, as the other tabs keep theirs.
    Kept(RootPage.Settings, needs) {
        SettingsScreen(state, onAction, onShowAbout = { needs.root.trail.aboutOpen = true })
    }
}

@Composable
private fun AboutPage(trail: PageTrail) {
    val close = { trail.aboutOpen = false }
    BackHandler(onBack = close)
    ExpressiveAboutScreen(onClose = close)
}

@Composable
private fun VaultPage(state: AppState, onAction: (AppAction) -> Unit) {
    val close = { onAction(AppAction.SetShowVaultScreen(false)) }
    BackHandler(onBack = close)
    VaultScreen(state, onAction, close)
}

/** Opens the Vault, from the tools in Browse. */
@Composable
private fun vaultOpener(onAction: (AppAction) -> Unit): () -> Unit {
    val haptics = LocalHaptics.current
    return {
        haptics.fab()
        onAction(AppAction.SetShowVaultScreen(true))
    }
}

@Composable
private fun LocationsPage(state: AppState, needs: PageNeeds) {
    ExpressiveLocationsScreen(
        state = state, onAction = needs.onAction,
        onFileClick = needs.onFile, onFileMenuClick = needs.onFileMenu,
        onAddLocation = { needs.root.forms.choosing = true },
        onOpenVault = vaultOpener(needs.onAction),
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun FolderPage(state: AppState, needs: PageNeeds) {
    val haptics = LocalHaptics.current
    ExpressiveBrowseScreen(
        state = state, onAction = needs.onAction,
        onFileClick = needs.onFile, onFileMenuClick = needs.onFileMenu,
        onCreateClick = {
            haptics.tap()
            needs.root.creating.choosing = true
        },
        onOpenVault = vaultOpener(needs.onAction),
        onLaunchDrivePicker = needs.google.pickFiles,
        onResolveAuthIntent = { state.recoverableAuthIntent?.let(needs.google::askConsent) },
        onDeleteRequest = { if (state.selectedFiles.isNotEmpty()) needs.root.deleting.askAboutSelection() },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun CategoryPage(page: RootPage.Category, state: AppState, needs: PageNeeds) {
    val trail = needs.root.trail
    CompositionLocalProvider(LocalSelectionActive provides state.isSelectionMode) {
        Kept(page, needs) {
            ExpressiveCategoryDetailScreen(
                categoryKey = page.key,
                state = state, onAction = needs.onAction,
                onBack = { trail.take(backFromCategory(trail.cleanerWaiting), needs.onAction) },
                onFileClick = needs.onFile, onFileMenuClick = needs.onFileMenu,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
