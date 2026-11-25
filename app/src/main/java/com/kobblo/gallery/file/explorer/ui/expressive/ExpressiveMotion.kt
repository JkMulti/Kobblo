package com.kobblo.gallery.file.explorer.ui.expressive

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion

/**
 * The motion specs screens share, and the one place that decides whether the phone wants motion at all.
 *
 * There are three movements, each offered for every value type that gets animated with it:
 * a spring for whatever settles into place, a tween for whatever arrives, and a slightly shorter
 * tween for whatever leaves. The spring is the same one as `KobbloMotion.snappy()`.
 */
object ExpressiveMotion {

    private const val ArriveMillis = 200
    private const val LeaveMillis = 180

    private fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 0.75f, stiffness = 400f)
    private fun <T> arrive(): TweenSpec<T> = tween(ArriveMillis, easing = FastOutSlowInEasing)
    private fun <T> leave(): TweenSpec<T> = tween(LeaveMillis, easing = FastOutSlowInEasing)

    val SpringSpec: SpringSpec<Float> = settle()
    val DpSpringSpec: SpringSpec<Dp> = settle()
    val IntOffsetSpringSpec: SpringSpec<IntOffset> = settle()
    val IntSizeSpringSpec: SpringSpec<IntSize> = settle()

    val FastTween: TweenSpec<Float> = arrive()
    val FastDpTween: TweenSpec<Dp> = arrive()
    val IntOffsetFastTween: TweenSpec<IntOffset> = arrive()
    val IntSizeFastTween: TweenSpec<IntSize> = arrive()
    val ColorTween: TweenSpec<Color> = arrive()

    val FadeTween: TweenSpec<Float> = leave()
    val ExitTween: TweenSpec<Float> = leave()
    val IntOffsetExitTween: TweenSpec<IntOffset> = leave()
    val IntSizeExitTween: TweenSpec<IntSize> = leave()

    /**
     * True when animations should be skipped: the phone has them turned off, or this is a layout
     * preview, which has no time to run them in. The answer is read once per screen, not watched.
     */
    @Composable
    fun isReducedMotion(): Boolean {
        val context = LocalContext.current
        return LocalInspectionMode.current || remember(context) { systemMotionOff(context) }
    }
}

/** The two scales Developer options and "Remove animations" set to zero. */
private val AnimationScales = listOf(Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.ANIMATOR_DURATION_SCALE)

private fun systemMotionOff(context: Context): Boolean {
    val scales = try {
        val settings = context.contentResolver
        AnimationScales.map { Settings.Global.getFloat(settings, it, 1f) }
    } catch (e: Exception) {
        // Not every device lets an app read these, and then the animator's own switch is all there is.
        emptyList()
    }
    // areAnimatorsEnabled rather than getDurationScale: that one needs Android 13, and every
    // pressable thing on every screen asks this.
    return motionIsOff(scales, ValueAnimator.areAnimatorsEnabled())
}

/** Whether motion is off, given the system's animation [scales] and whether animators run at all. */
internal fun motionIsOff(scales: List<Float>, animatorsEnabled: Boolean): Boolean =
    !animatorsEnabled || scales.any { it == 0f }

/**
 * Makes anything tappable give under a finger: it shrinks to [pressedScale] while held and springs
 * back on release. Pass the [interactionSource] the click uses, or the press is never seen. With
 * motion off it stays at full size.
 */
fun Modifier.pressScale(
    pressedScale: Float = 0.97f,
    interactionSource: MutableInteractionSource? = null
): Modifier = composed {
    val size by pressedSize(interactionSource ?: remember { MutableInteractionSource() }, pressedScale)
    graphicsLayer {
        scaleX = size
        scaleY = size
    }
}

@Composable
private fun pressedSize(presses: InteractionSource, pressedScale: Float): State<Float> {
    val held by presses.collectIsPressedAsState()
    val still = ExpressiveMotion.isReducedMotion()
    return animateFloatAsState(
        targetValue = if (held && !still) pressedScale else 1f,
        animationSpec = if (still) snap() else KobbloMotion.press(),
        label = "press_scale"
    )
}
