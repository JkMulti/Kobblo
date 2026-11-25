package com.kobblo.gallery.file.explorer.ui.root

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.kobblo.gallery.file.explorer.ui.TabSwitchLatencyTracker
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion

/** How far, as a share of the screen, a tab drifts as another takes its place. */
private const val TabDrift = 8

/** How far, as a share of the screen, a page is pushed aside by the one that opens over it. */
private const val PushedAside = 4

/** How faint a page gets while it lies under the one over it. */
private const val UnderneathAlpha = 0.5f

/** One page giving way to another. With motion reduced they simply change places. */
internal fun AnimatedContentTransitionScope<RootPage>.pageChange(reducedMotion: Boolean): ContentTransform {
    if (reducedMotion) return fadeIn(snap()) togetherWith fadeOut(snap())
    TabSwitchLatencyTracker.onTransitionStart(initialState.id, targetState.id)
    return when (pageMove(initialState, targetState)) {
        PageMove.TabAfter -> tabChange(toward = 1)
        PageMove.TabBefore -> tabChange(toward = -1)
        PageMove.Deeper -> goingDeeper()
        PageMove.Back -> comingBack()
    }
}

/** A short parallax: tabs are siblings, so nothing should feel pushed. */
private fun tabChange(toward: Int) = ContentTransform(
    targetContentEnter = slideInHorizontally(KobbloMotion.smooth()) { it * toward / TabDrift } + fadeIn(KobbloMotion.fadeIn()),
    initialContentExit = slideOutHorizontally(KobbloMotion.smooth()) { -it * toward / TabDrift } + fadeOut(KobbloMotion.fadeOut()),
    targetContentZIndex = 1f
)

/** The new page arrives from the edge, over the old one. */
private fun goingDeeper() = ContentTransform(
    targetContentEnter = slideInHorizontally(KobbloMotion.smooth()) { it } + fadeIn(KobbloMotion.fadeIn()),
    initialContentExit = slideOutHorizontally(KobbloMotion.smooth()) { -it / PushedAside } +
        fadeOut(tween(KobbloMotion.Slow, easing = KobbloMotion.EaseOut), targetAlpha = UnderneathAlpha),
    targetContentZIndex = 1f
)

/** Going back retraces the same path. */
private fun comingBack() = ContentTransform(
    targetContentEnter = slideInHorizontally(KobbloMotion.smooth()) { -it / PushedAside } +
        fadeIn(tween(KobbloMotion.Default, easing = KobbloMotion.EaseOut), initialAlpha = UnderneathAlpha),
    initialContentExit = slideOutHorizontally(KobbloMotion.smooth()) { it } + fadeOut(KobbloMotion.fadeOut()),
    targetContentZIndex = -1f
)
