package com.kobblo.gallery.file.explorer.viewer

import androidx.compose.runtime.Composable
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.ui.hub.HubRoute

/** The full screens of this area. Open one with `Hub.open(...)`. */
sealed interface ViewerRoute : HubRoute

@Composable
fun ViewerRouteContent(route: ViewerRoute, state: AppState, onAction: (AppAction) -> Unit) {
    // Draw each route here with a when (route) once the first one exists.
}
