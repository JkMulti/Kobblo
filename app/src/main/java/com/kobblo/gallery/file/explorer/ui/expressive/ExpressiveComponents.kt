package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloBackButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloSearchField
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheetTitle
import com.kobblo.gallery.file.explorer.ui.design.KobbloSize
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.LargeTitleState
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kyant.shapes.RoundedRectangle

/** The corner roundness the theme hands down. It is one fixed value: corners are not a setting. */
val LocalCornerRoundness = compositionLocalOf { 0.5f }

/** A section's title where the section isn't a group of rows: a card, a chart, a grid. */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = KobbloDesign.colors.ink
) {
    Text(text = title, style = KobbloType.title, color = color, modifier = modifier)
}

// ── Screen header ─────────────────────────────────────────────────────────────

/**
 * The header of every screen: a toolbar, a large title, and optionally a search field. Pass
 * [largeTitle] to fold the title and search away with the content below them; the small title then
 * fades into the toolbar so the screen never loses its name.
 *
 * The toolbar's leading side is the way back when [onBack] is given, and otherwise [leftIcon] if there
 * is one. Its trailing side is [rightAction] when given, and otherwise [rightIcon].
 */
@Composable
fun ExpressiveScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    onSubtitleClick: (() -> Unit)? = null,
    subtitleChevron: Boolean = false,
    onBack: (() -> Unit)? = null,
    backLabel: String = stringResource(R.string.back),
    leftIcon: ImageVector? = null,
    onLeftClick: () -> Unit = {},
    leftDescription: String = "",
    rightIcon: ImageVector? = null,
    onRightClick: () -> Unit = {},
    rightDescription: String = "",
    rightAction: (@Composable () -> Unit)? = null,
    showSearch: Boolean = false,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    searchPlaceholder: String = stringResource(R.string.search_files_hint),
    largeTitle: LargeTitleState? = null,
    belowTitle: (@Composable ColumnScope.() -> Unit)? = null,
    onSearchFocusChanged: ((Boolean) -> Unit)? = null,
    onSearchSubmit: (() -> Unit)? = null,
    searchFocusRequester: FocusRequester? = null,
    backModifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().statusBarsPadding()) {
        HeaderToolbar(
            // Only a title that can fold away needs a second, small one to take its place.
            smallTitle = title.takeIf { largeTitle != null },
            smallTitleAlpha = { smallTitleAlpha(largeTitle?.fraction ?: 0f) },
            leading = {
                when {
                    onBack != null -> KobbloBackButton(onClick = onBack, label = backLabel, modifier = backModifier)
                    leftIcon != null -> KobbloIconButton(icon = leftIcon, description = leftDescription, onClick = onLeftClick)
                }
            },
            trailing = {
                when {
                    rightAction != null -> rightAction()
                    rightIcon != null -> KobbloIconButton(icon = rightIcon, description = rightDescription, onClick = onRightClick)
                }
            }
        )

        val folding = if (largeTitle == null) Modifier else with(largeTitle) { Modifier.foldingTitle() }
        Column(modifier = folding.padding(bottom = 8.dp)) {
            Text(
                text = title,
                style = KobbloType.largeTitle,
                color = KobbloDesign.colors.ink,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1
            )
            if (subtitle.isNotEmpty()) HeaderSubtitle(subtitle, withChevron = subtitleChevron, onClick = onSubtitleClick)
            if (showSearch) {
                Spacer(Modifier.height(KobbloSpace.sm))
                KobbloSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    placeholder = searchPlaceholder,
                    focusRequester = searchFocusRequester,
                    onFocusChanged = onSearchFocusChanged,
                    onSubmit = onSearchSubmit
                )
            }
            belowTitle?.invoke(this)
        }
    }
}

/**
 * How visible the toolbar's small title is for a large title folded by [foldFraction]. It stays out
 * of sight until the large one is mostly gone, so the two are never read at once.
 */
internal fun smallTitleAlpha(foldFraction: Float): Float =
    ((foldFraction - SmallTitleStart) / SmallTitleSpan).coerceIn(0f, 1f)

/** How far the large title has folded when the small one starts to show, and how much further until it is fully there. */
private const val SmallTitleStart = 0.6f
private const val SmallTitleSpan = 0.4f

/** Room kept clear on each side of the small title for two toolbar buttons and the gap between them. */
private val SmallTitleSideRoom = 104.dp

@Composable
private fun HeaderToolbar(
    smallTitle: String?,
    smallTitleAlpha: () -> Float,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().height(KobbloSize.toolbar)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading() }
        if (smallTitle != null) {
            val centered = Modifier.align(Alignment.Center).padding(horizontal = SmallTitleSideRoom)
            Text(
                text = smallTitle,
                modifier = centered.graphicsLayer { alpha = smallTitleAlpha() },
                style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                color = KobbloDesign.colors.ink,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1
            )
        }
        Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/** The line under a large title. With [onClick] it is a control, which the chevron hints at. */
@Composable
private fun HeaderSubtitle(text: String, withChevron: Boolean, onClick: (() -> Unit)?) {
    val smoke = KobbloDesign.colors.smoke
    val tap = if (onClick == null) Modifier else Modifier.clip(RoundedRectangle(8.dp)).clickable(onClick = onClick)
    Row(modifier = tap.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = text, style = KobbloType.callout, color = smoke, overflow = TextOverflow.Ellipsis, maxLines = 1)
        if (withChevron) {
            Icon(AppIcons.ChevronDown, contentDescription = stringResource(R.string.switch_storage), tint = smoke, modifier = Modifier.size(16.dp))
        }
    }
}

/** [ExpressiveScreenHeader] for a screen reached from another: a way back, and its actions in a row. */
@Composable
fun PageHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    backLabel: String = stringResource(R.string.back),
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    ExpressiveScreenHeader(
        title = title,
        modifier = modifier.padding(horizontal = KobbloSpace.screen),
        subtitle = subtitle,
        onBack = onBack,
        backLabel = backLabel,
        rightAction = if (actions == null) null else {
            { HeaderActions(actions) }
        }
    )
}

@Composable
private fun HeaderActions(buttons: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = buttons)
}

// ── Controls ──────────────────────────────────────────────────────────────────

private val SwitchWidth = 51.dp
private val SwitchHeight = 31.dp
private val SwitchKnob = 27.dp
private val SwitchInset = 2.dp

/** How far the knob slides: the track's width less the knob and the inset at each end. */
private val SwitchTravel = SwitchWidth - SwitchKnob - SwitchInset * 2

/** The app's on/off switch: a white knob that slides along a track, which turns green when on. */
@Composable
fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val knobShift by animateDpAsState(
        targetValue = if (checked) SwitchTravel else 0.dp,
        animationSpec = KobbloMotion.snappy(),
        label = "switch_knob"
    )
    val track by animateColorAsState(
        targetValue = if (checked) KobbloTint.Green else KobbloDesign.colors.mist,
        animationSpec = KobbloMotion.state(),
        label = "switch_track"
    )
    Box(
        modifier = modifier
            .size(width = SwitchWidth, height = SwitchHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CapsuleShape)
            .background(track)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = onCheckedChange
            )
            .padding(SwitchInset)
    ) {
        Box(
            Modifier
                .size(SwitchKnob)
                .offset(x = knobShift)
                .clip(CapsuleShape)
                .background(Color.White)
        )
    }
}

/** A capsule that is either chosen or not: a filter, a quick destination, a category. */
@Composable
fun ExpressiveChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconSize: Dp = 18.dp,
    iconSpacing: Dp = 6.dp
) {
    val colors = KobbloDesign.colors
    val fill by animateColorAsState(if (selected) colors.action else colors.snow, KobbloMotion.state(), label = "chip_bg")
    val content by animateColorAsState(if (selected) colors.onAction else colors.ink, KobbloMotion.state(), label = "chip_text")
    val presses = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(36.dp)
            .pressScale(pressedScale = 0.95f, interactionSource = presses)
            .clip(CapsuleShape)
            .background(fill)
            .clickable(interactionSource = presses, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let { glyph ->
            Icon(glyph, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(iconSpacing))
        }
        Text(
            text = label,
            style = KobbloType.callout.copy(fontWeight = FontWeight.Medium),
            color = content,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1
        )
    }
}

// ── Sheets ────────────────────────────────────────────────────────────────────

/** What goes inside a sheet: its title, then [content], kept clear of the navigation bar. */
@Composable
fun ExpressiveSheetContent(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        // A sheet leaves the navigation bar to its content, and the last row would sit under it.
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        KobbloSheetTitle(title)
        content()
    }
}
