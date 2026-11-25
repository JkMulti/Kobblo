package com.kobblo.gallery.file.explorer.ui.hub

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.gallery.GalleryRoute
import com.kobblo.gallery.file.explorer.gallery.GalleryRouteContent
import com.kobblo.gallery.file.explorer.music.MusicRoute
import com.kobblo.gallery.file.explorer.music.MusicRouteContent
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.tools.ToolsRouteContent
import com.kobblo.gallery.file.explorer.transfer.TransferRoute
import com.kobblo.gallery.file.explorer.transfer.TransferRouteContent
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveScreenHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.kobbloReadableWidth
import com.kobblo.gallery.file.explorer.viewer.ViewerRoute
import com.kobblo.gallery.file.explorer.viewer.ViewerRouteContent

/** A full screen belonging to one feature area. Each area declares its own routes and draws them. */
interface HubRoute

/**
 * The stack of full screens above the tabs, so a feature can add a screen without touching
 * `KobbloRoot`. Open one from anywhere with `Hub.open(route)`. Back pops it.
 */
object Hub {
    private val stack = mutableStateListOf<HubRoute>()

    val current: HubRoute? get() = stack.lastOrNull()
    val depth: Int get() = stack.size

    fun open(route: HubRoute) {
        stack.add(route)
    }

    fun back() {
        stack.removeLastOrNull()
    }

    fun close() {
        stack.clear()
    }
}

@Composable
fun HubHost(state: AppState, onAction: (AppAction) -> Unit) {
    val current = Hub.current
    val reduced = ExpressiveMotion.isReducedMotion()
    // The newest handler answers Back first. Registering anew per route keeps the screen on top ahead
    // of anything under it that registered since the app started, such as a tab returned to.
    key(Hub.depth) { BackHandler(enabled = current != null) { Hub.back() } }

    // Which way the last change went, worked out once per change. The transition asks twice, for the
    // screen coming in and the one leaving, so asking it to work this out made every leaving screen
    // leave as if going deeper: a quarter to the left, where Back should slide it off to the right.
    val direction = remember { HubDirection() }
    if (current != direction.route) {
        direction.deeper = Hub.depth >= direction.depth
        direction.route = current
        direction.depth = Hub.depth
    }
    AnimatedContent(
        targetState = current,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { hubTransition(reduced, direction.deeper) },
        label = "hub"
    ) { route ->
        when (route) {
            // Nothing open is an empty screen, not nothing. AnimatedContent animates between the sizes of
            // what it shows, so with nothing there every screen opened over a tab grew out of a corner, and
            // shrank into the middle on Back. An empty Box takes no touches.
            null -> Box(Modifier.fillMaxSize())
            is GalleryRoute -> GalleryRouteContent(route, state, onAction)
            is MusicRoute -> MusicRouteContent(route, state, onAction)
            is ViewerRoute -> ViewerRouteContent(route, state, onAction)
            is TransferRoute -> TransferRouteContent(route, state, onAction)
            is ToolsRoute -> ToolsRouteContent(route, state, onAction)
            is com.kobblo.gallery.file.explorer.ui.welcome.WelcomeRoute -> com.kobblo.gallery.file.explorer.ui.welcome.WelcomeRouteContent(route, onAction)
            else -> Unit
        }
    }
}

private class HubDirection {
    var route: HubRoute? = null
    var depth = 0
    var deeper = true
}

/**
 * Every screen slides, the one way the app moves between places: in from the right going deeper, and
 * off to the right going back, over the screen it uncovers. Only a photo flies, and that's the viewer's.
 */
private fun AnimatedContentTransitionScope<HubRoute?>.hubTransition(
    reduced: Boolean,
    goingDeeper: Boolean
): ContentTransform {
    if (reduced) return fadeIn(KobbloMotion.fadeIn()) togetherWith fadeOut(KobbloMotion.fadeOut())
    return if (goingDeeper) {
        slideInHorizontally(KobbloMotion.smooth()) { it } togetherWith
            slideOutHorizontally(KobbloMotion.smooth()) { -it / 4 }
    } else {
        // The screen leaving stays on top as it slides off, uncovering the one under it.
        (slideInHorizontally(KobbloMotion.smooth()) { -it / 4 } togetherWith
            slideOutHorizontally(KobbloMotion.smooth()) { it }).apply { targetContentZIndex = -1f }
    }
}

/**
 * The frame of a pushed screen: canvas, status bar inset, and the same toolbar as a folder, with
 * Back naming where it goes.
 */
@Composable
fun HubScreen(
    title: String,
    modifier: Modifier = Modifier,
    backLabel: String = stringResource(R.string.back),
    trailingIcon: ImageVector? = null,
    trailingDescription: String = "",
    onTrailingClick: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(KobbloDesign.colors.canvas)
    ) {
        Column(modifier = Modifier.kobbloReadableWidth()) {
            ExpressiveScreenHeader(
                title = title,
                modifier = Modifier.padding(horizontal = KobbloSpace.screen),
                onBack = { Hub.back() },
                backLabel = backLabel,
                rightIcon = trailingIcon,
                onRightClick = onTrailingClick,
                rightDescription = trailingDescription
            )
            content()
        }
    }
}
