package com.kobblo.gallery.file.explorer.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/** A white inset group of rows. Rows inside it draw their own separators. */
@Composable
fun KobbloGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen)
            .clip(KobbloShapes.group)
            .background(KobbloDesign.colors.snow),
        content = content
    )
}

/**
 * The title above a group. Pass [collapsed] and [onToggle] to make it fold the group away, the
 * way the sections of a Files sidebar do.
 */
@Composable
fun KobbloGroupHeader(
    title: String,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null
) {
    val colors = KobbloDesign.colors
    val reduced = ExpressiveMotion.isReducedMotion()
    val tap = if (onToggle != null) Modifier.quietClickable(onClick = onToggle) else Modifier
    Row(
        modifier = modifier
            .widthIn(max = KobbloSpace.readableWidth)
            .fillMaxWidth()
            .then(tap)
            // On the same line as the group's edge, and the screen's title above it.
            .padding(start = KobbloSpace.screen, end = KobbloSpace.screen, top = KobbloSpace.section, bottom = KobbloSpace.titleGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = KobbloType.title,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onToggle != null) {
            val rotation by animateFloatAsState(
                targetValue = if (collapsed) -90f else 0f,
                animationSpec = if (reduced) snap() else KobbloMotion.snappy(),
                label = "group_chevron"
            )
            Icon(
                imageVector = AppIcons.ChevronDown,
                contentDescription = if (collapsed) stringResource(R.string.screen_show_section, title) else stringResource(R.string.screen_hide_section, title),
                tint = colors.action,
                modifier = Modifier.size(18.dp).rotate(rotation)
            )
        }
    }
}

/** Small text beneath a group, or at the foot of a list. */
@Composable
fun KobbloGroupFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = KobbloType.footnote,
        color = KobbloDesign.colors.smoke,
        modifier = modifier
            .fillMaxWidth()
            // In line with the text of the rows it's about.
            .padding(horizontal = KobbloSpace.content, vertical = KobbloSpace.xs)
    )
}

/**
 * One row of a group. The separator starts where the title starts and stops at the group's edge,
 * so it reads as dividing the text rather than the whole row. Its badge sits [KobbloSpace.inset] from
 * the group's side and, at least as tall as [KobbloSize.row], as far from its top and bottom.
 */
@Composable
fun KobbloRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = KobbloDesign.colors.ink,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    showDivider: Boolean = false,
    dividerInset: Dp = if (leading != null) KobbloSpace.inset + KobbloSize.badge + KobbloSpace.leadingGap else KobbloSpace.inset,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val colors = KobbloDesign.colors
    val source = remember { MutableInteractionSource() }
    val fill = pressedRowFill(source)
    val tap = when {
        onClick != null -> Modifier.quietCombinedClickable(enabled = enabled, source = source, onLongClick = onLongClick, onClick = onClick)
        else -> Modifier.semantics(mergeDescendants = true) {}
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = KobbloSize.row)
            .background(fill)
            .then(tap)
            .rowDivider(showDivider, dividerInset, colors.hairline)
            // Text needs less above and below than a badge does, so two lines still fit the badge's height.
            .padding(horizontal = KobbloSpace.inset, vertical = KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Box(modifier = Modifier.size(KobbloSize.badge), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(KobbloSpace.leadingGap))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = KobbloType.body,
                color = if (enabled) titleColor else colors.pewter,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = KobbloType.footnote,
                    color = colors.smoke,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            below?.invoke(this)
        }
        if (trailing != null) {
            Spacer(Modifier.width(KobbloSpace.xs))
            trailing()
        }
    }
}

/** The fill a row takes while pressed: on at once, off gently. */
@Composable
fun pressedRowFill(source: MutableInteractionSource): Color {
    val colors = KobbloDesign.colors
    val pressed by source.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = if (pressed) colors.mist else colors.mist.copy(alpha = 0f),
        animationSpec = if (pressed) snap() else tween(KobbloMotion.Slow, easing = KobbloMotion.EaseOut),
        label = "row_press"
    )
    return fill
}

/** Draws the hairline along a row's bottom edge. */
fun Modifier.rowDivider(visible: Boolean, inset: Dp, color: Color): Modifier =
    if (!visible) this else drawBehind {
        val y = size.height - 0.5f
        val insetPx = inset.toPx()
        val (start, end) = if (layoutDirection == LayoutDirection.Ltr) insetPx to size.width else 0f to size.width - insetPx
        drawLine(color, Offset(start, y), Offset(end, y), strokeWidth = 1f)
    }

/** A row's disclosure chevron: quiet enough to read as a hint, not an action. */
@Composable
fun KobbloChevron(modifier: Modifier = Modifier) {
    Icon(
        imageVector = AppIcons.ChevronRight,
        contentDescription = null,
        tint = KobbloDesign.colors.pewter,
        modifier = modifier.size(15.dp)
    )
}

/** A white glyph on a colored continuous tile, as in a settings list. */
@Composable
fun KobbloIconBadge(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = KobbloSize.badge
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(KobbloShapes.tile)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.66f))
    }
}

/** The small handle at the top of a sheet. */
@Composable
fun KobbloSheetGrabber(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = 6.dp, bottom = 6.dp)
            .size(width = 36.dp, height = 5.dp)
            .clip(CapsuleShape)
            .background(KobbloDesign.colors.silver)
    )
}

/**
 * One labelled field in a form, as a row of a [KobbloGroup]: the label on the leading side and the
 * value beside it, like a settings form. Tapping anywhere on the row puts the cursor in the field.
 */
@Composable
fun KobbloFormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    secret: Boolean = false,
    showDivider: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = KobbloDesign.colors
    val focus = remember { FocusRequester() }
    var shown by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = KobbloSize.row)
            .quietClickable { focus.requestFocus() }
            .rowDivider(showDivider, KobbloSpace.inset, colors.hairline)
            // A button at the end brings its own room around its glyph.
            .padding(start = KobbloSpace.inset, end = if (secret || trailing != null) KobbloSpace.xxs else KobbloSpace.inset, top = KobbloSpace.xs, bottom = KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = KobbloType.body,
            color = colors.ink,
            modifier = Modifier.width(112.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtMost(1.7f))
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).focusRequester(focus),
            textStyle = KobbloType.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.action),
            singleLine = true,
            keyboardOptions = keyboardOptions,
            visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = KobbloType.body, color = colors.pewter, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                inner()
            }
        )
        if (secret) {
            KobbloIconButton(
                icon = if (shown) AppIcons.EyeOff else AppIcons.Eye,
                description = if (shown) stringResource(R.string.viewer_pdf_password_hide) else stringResource(R.string.viewer_pdf_password_show),
                onClick = { shown = !shown },
                size = 36.dp,
                tint = colors.smoke,
                background = Color.Transparent
            )
        }
        trailing?.invoke(this)
    }
}
