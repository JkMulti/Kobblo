package com.kobblo.gallery.file.explorer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.gallery.PhotoFlightOverlay
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.haptics.rememberHapticsController
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveTheme
import com.kobblo.gallery.file.explorer.ui.expressive.KobbloBackground
import com.kobblo.gallery.file.explorer.ui.expressive.LocalIconShapeType
import com.kobblo.gallery.file.explorer.ui.root.BatchRenameSheet
import com.kobblo.gallery.file.explorer.ui.root.CaptureGuard
import com.kobblo.gallery.file.explorer.ui.root.ItemLock
import com.kobblo.gallery.file.explorer.ui.root.MainScreen
import com.kobblo.gallery.file.explorer.ui.root.MegaAccount
import com.kobblo.gallery.file.explorer.ui.root.RootTheme
import com.kobblo.gallery.file.explorer.ui.root.ViewerLayer
import com.kobblo.gallery.file.explorer.ui.root.rememberFeltActions

/**
 * The whole app on screen: the pages with what opens over them, then the viewers, then whatever covers
 * everything. The parts are in `ui.root`. [overlay] is for dialogs that belong to none of them.
 */
@Composable
fun KobbloRoot(
    state: AppState,
    onActionOrig: (AppAction) -> Unit,
    snackbarHostState: SnackbarHostState,
    overlay: @Composable () -> Unit = {},
    /** Drawn over everything, such as the welcome. */
    cover: @Composable () -> Unit = {}
) {
    val haptics = rememberHapticsController { state.haptics }
    CompositionLocalProvider(LocalHaptics provides haptics, LocalIconShapeType provides state.activeIconShape) {
        val onAction = rememberFeltActions(haptics, onActionOrig)
        // The first handler made is the last one asked, after everything below has had its turn.
        BackHandler(enabled = state.isSelectionMode) { onAction(AppAction.ClearSelection) }
        RootTheme(state) {
            overlay()
            MegaAccount(state, onAction)
            ItemLock(state, onAction)
            CaptureGuard(state)
            Surface(Modifier.fillMaxSize(), color = ExpressiveTheme.colors.bg, contentColor = MaterialTheme.colorScheme.onBackground) {
                // Drawn once, behind the pages, so it stays still while they move.
                KobbloBackground(Modifier.fillMaxSize())
                // On its side, a phone's nav bar and cutout sit beside the content, over its end buttons.
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                    MainScreen(state, snackbarHostState, onAction, Modifier.fillMaxSize())
                }
                BatchRenameSheet(state, onAction)
                ViewerLayer(state, snackbarHostState, onAction)
                PhotoFlightOverlay()
                cover()
            }
        }
    }
}
