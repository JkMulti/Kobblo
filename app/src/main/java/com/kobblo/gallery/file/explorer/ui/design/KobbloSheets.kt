package com.kobblo.gallery.file.explorer.ui.design

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import kotlinx.coroutines.delay

/**
 * The one bottom sheet. Every sheet gets the grabber, the canvas and the same corners from here, and
 * none has a Close button: dragging down, tapping outside and Back all close it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KobbloSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    KobbloSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
        content = content
    )
}

/** [KobbloSheet] for a caller that holds its own state, to close it with an animation. */
@ExperimentalMaterial3Api
@Composable
fun KobbloSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        // Without a label, a screen reader finds a button that says nothing here.
        dragHandle = {
            val label = stringResource(R.string.polish_sheet_handle)
            KobbloSheetGrabber(Modifier.semantics { contentDescription = label })
        },
        containerColor = KobbloDesign.colors.canvas,
        shape = KobbloShapes.sheet
    ) {
        // A sheet is its own window. The main window turns this off, but here the system would lay a
        // pale band over the sheet's foot, behind three-button navigation.
        val view = LocalView.current
        SideEffect {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (view.parent as? DialogWindowProvider)?.window?.isNavigationBarContrastEnforced = false
            }
        }
        content()
    }
}

/** A sheet's title: small and centered, so the list it heads stays the subject. */
@Composable
fun KobbloSheetTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    val colors = KobbloDesign.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = KobbloSpace.lg, end = KobbloSpace.lg, top = KobbloSpace.xxs, bottom = KobbloSpace.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = KobbloType.footnote,
                color = colors.smoke,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = KobbloSpace.xxs)
            )
        }
    }
}

/**
 * The foot of a sheet that asks something: Cancel on the leading side, the action on the trailing
 * side, red when it destroys something. Every confirmation in the app reads the same way round.
 */
@Composable
fun KobbloActionPair(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancelLabel: String = stringResource(R.string.cancel),
    destructive: Boolean = false,
    enabled: Boolean = true
) {
    val colors = KobbloDesign.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen),
        horizontalArrangement = Arrangement.spacedBy(KobbloSpace.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onCancel,
            modifier = Modifier.weight(1f).heightIn(min = KobbloActionHeight),
            colors = ButtonDefaults.buttonColors(containerColor = colors.snow, contentColor = colors.ink)
        ) {
            Text(cancelLabel, style = KobbloType.headline, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        Button(
            onClick = onConfirm,
            enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = KobbloActionHeight),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (destructive) colors.danger else colors.action,
                contentColor = colors.onAction,
                disabledContainerColor = colors.mist,
                disabledContentColor = colors.pewter
            )
        ) {
            Text(confirmLabel, style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** How tall the buttons at the foot of a sheet are. */
val KobbloActionHeight: Dp = 52.dp

/** Cancel in an alert: always the quiet one, and always first. */
@Composable
fun KobbloAlertCancel(onClick: () -> Unit, label: String = stringResource(R.string.cancel)) {
    TextButton(onClick = onClick) {
        Text(label, style = KobbloType.callout, color = KobbloDesign.colors.smoke)
    }
}

/** An alert's action: the tint, or red when it destroys something. */
@Composable
fun KobbloAlertAction(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true
) {
    val colors = KobbloDesign.colors
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            label,
            style = KobbloType.callout.copy(fontWeight = FontWeight.SemiBold),
            color = when {
                !enabled -> colors.pewter
                destructive -> colors.danger
                else -> colors.action
            }
        )
    }
}

/**
 * The app's spinner. One arc that grows, shrinks and turns, with nothing that pulses. It waits a moment
 * before fading in, so a folder that opens quickly never flashes one.
 */
@Composable
fun KobbloLoader(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    strokeWidth: Dp = (size * 0.09f).coerceAtLeast(2.dp),
    color: Color = KobbloDesign.colors.action
) {
    val reduced = ExpressiveMotion.isReducedMotion()
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(LoaderDelayMillis)
        if (reduced) appear.snapTo(1f) else appear.animateTo(1f, tween(KobbloMotion.Default, easing = KobbloMotion.EaseOut))
    }
    val transition = rememberInfiniteTransition(label = "kobblo_loader")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "loader_phase"
    )
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
        label = "loader_turn"
    )
    Canvas(modifier = modifier.size(size)) {
        val growing = phase < 1f
        val eased = FastOutSlowInEasing.transform(if (growing) phase else phase - 1f)
        // With motion off a turning arc would stop as a dot, so it holds as most of a ring instead.
        val sweep = when {
            reduced -> MinArc + ArcTravel
            growing -> MinArc + ArcTravel * eased
            else -> MinArc + ArcTravel * (1f - eased)
        }
        // The head turns 225 degrees a phase and the tail catches up 270 at the end, which lands on a
        // whole number of turns, so the arc doesn't jump when the phase restarts.
        val start = if (reduced) -90f else turn + phase * 225f + if (growing) 0f else ArcTravel * eased
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        drawArc(
            color = color,
            alpha = appear.value,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

private const val LoaderDelayMillis = 180L
private const val MinArc = 15f
private const val ArcTravel = 270f

/** Fills a screen or a list's place while its first results load, with an optional line beneath. */
@Composable
fun KobbloLoading(modifier: Modifier = Modifier, label: String? = null) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KobbloLoader()
            if (label != null) {
                Spacer(Modifier.height(KobbloSpace.sm))
                Text(label, style = KobbloType.footnote, color = KobbloDesign.colors.smoke, textAlign = TextAlign.Center)
            }
        }
    }
}
