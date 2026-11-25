package com.kobblo.gallery.file.explorer.ui.expressive

import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.explorer.drag.dragActionTarget
import com.kobblo.gallery.file.explorer.explorer.drag.dragBlocker
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle

private val SelectionBarRadius = 28.dp

/** How far the actions sit inside the bar, at its sides and its foot alike. */
private val SelectionBarInset = 8.dp
private val SelectionBarShape = RoundedRectangle(SelectionBarRadius)

/** The actions at either end sit in the bar's lower corners, so they round with them. */
private val SelectionActionShape = com.kobblo.gallery.file.explorer.ui.design.KobbloShapes.concentric(SelectionBarRadius, SelectionBarInset)

/**
 * Selection mode's action bar. It floats over the list so selected rows stay visible, and every
 * action is one tap away.
 */
@Composable
fun ExpressiveSelectionBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onBatchRename: () -> Unit,
    onArchive: (() -> Unit)? = null,
    onShare: () -> Unit,
    onCopy: (() -> Unit)?,
    onCut: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onInstallApks: (() -> Unit)? = null,
    isSingleApk: Boolean = false,
    onPaste: (() -> Unit)? = null,
    onToggleHide: (() -> Unit)? = null,
    isAllHidden: Boolean = false,
    backdrop: Backdrop? = null,
    onAddToAlbum: (() -> Unit)? = null,
    /** Whatever else the place you're selecting in can do with many files. */
    onMore: (() -> Unit)? = null,
    onSendNearby: (() -> Unit)? = null,
    onExtract: (() -> Unit)? = null,
    /** Everything that can be chosen from, with what's chosen, for the sheet behind the count. */
    selectable: List<com.kobblo.gallery.file.explorer.FileItem> = emptyList(),
    selected: Set<Int> = emptySet(),
    onSelect: (Set<Int>) -> Unit = {}
) {
    val colors = KobbloDesign.colors
    val haptics = com.kobblo.gallery.file.explorer.haptics.LocalHaptics.current
    var showOptions by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val allSelected = androidx.compose.runtime.remember(selectable, selected) {
        selectable.isNotEmpty() && selectable.all { it.isLocked || it.id in selected }
    }
    if (showOptions) {
        com.kobblo.gallery.file.explorer.explorer.SelectOptionsSheet(
            files = selectable,
            selected = selected,
            onSelect = onSelect,
            onDismiss = { showOptions = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .dragBlocker()
            .kobbloGlass(backdrop, SelectionBarShape)
            .padding(top = 12.dp, bottom = SelectionBarInset)
    ) {
        val toggleAll: @Composable () -> Unit = {
            SelectionTextButton(stringResource(if (allSelected) R.string.explorer_select_none else R.string.select_all)) {
                haptics.tap()
                if (allSelected) onSelectNone() else onSelectAll()
            }
        }
        val done: @Composable () -> Unit = {
            SelectionTextButton(stringResource(R.string.done_action), emphasized = true) {
                haptics.tap()
                onClearSelection()
            }
        }
        val canOpen = selectable.isNotEmpty()
        val openOptions = {
            haptics.tap()
            showOptions = true
        }
        // The header is one line, or two at a large font, and either way sits as far inside the bar.
        val header = Modifier.fillMaxWidth().padding(horizontal = SelectionBarInset)
        if (selectionCountStacks()) {
            Column(header) {
                SelectionCount(selectedCount, canOpen, openOptions, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    toggleAll()
                    done()
                }
            }
        } else {
            Row(header, verticalAlignment = Alignment.CenterVertically) {
                toggleAll()
                SelectionCount(selectedCount, canOpen, openOptions, Modifier.weight(1f))
                done()
            }
        }

        Spacer(Modifier.height(8.dp))

        val share = stringResource(R.string.share)
        val album = stringResource(R.string.gallery_album_action)
        val send = stringResource(R.string.transfer_send)
        val copy = stringResource(R.string.copy_action)
        val cut = stringResource(R.string.cut_action)
        val extract = stringResource(R.string.explorer_extract)
        val compress = stringResource(R.string.explorer_compress)
        val actions = buildList {
            add(BarAction(AppIcons.Share, share, onShare))
            if (onAddToAlbum != null) add(BarAction(AppIcons.Albums, album, onAddToAlbum))
            if (onMore != null) add(BarAction(AppIcons.MoreHoriz, stringResource(R.string.gallery_more), onMore))
            if (onSendNearby != null) add(BarAction(AppIcons.Send, send, onSendNearby))
            if (onCopy != null) add(BarAction(AppIcons.Copy, copy, onCopy))
            if (onCut != null) add(BarAction(AppIcons.Cut, cut, onCut))
            add(BarAction(AppIcons.Pencil, stringResource(R.string.rename_action), onBatchRename, takesDrop = false))
            if (onPaste != null) add(BarAction(AppIcons.Paste, stringResource(R.string.screen_paste), onPaste, takesDrop = false))
            if (onExtract != null) add(BarAction(AppIcons.FolderOpen, extract, onExtract))
            if (onArchive != null) add(BarAction(AppIcons.FileZip, compress, onArchive))
            if (onToggleHide != null) add(BarAction(if (isAllHidden) AppIcons.Eye else AppIcons.EyeOff, if (isAllHidden) stringResource(R.string.screen_unhide) else stringResource(R.string.screen_hide), onToggleHide))
            if (onInstallApks != null) add(BarAction(if (isSingleApk) AppIcons.Android else AppIcons.Apps, if (isSingleApk) stringResource(R.string.transfer_install) else stringResource(R.string.screen_batch_install), onInstallApks))
        }

        // Everything fits when it can, and otherwise the rest scrolls beside Delete, which stays put:
        // it's the one action that must never be half off the edge of the bar.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SelectionBarInset)
        ) {
            val slots = actions.size + 1
            val fits = maxWidth / slots >= MinActionWidth
            val width = if (fits) maxWidth / slots else ScrollingActionWidth
            Row(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                ) {
                    actions.forEach { SelectionAction(it.icon, it.label, it.onClick, width = width, takesDrop = it.takesDrop) }
                }
                SelectionAction(AppIcons.Trash, stringResource(R.string.delete_label), onDelete, tint = colors.danger, width = width)
            }
        }
    }
}

/** @param takesDrop whether a stack can be let go on it. Naming things or pasting has nothing to do with a drag. */
private class BarAction(val icon: ImageVector, val label: String, val onClick: () -> Unit, val takesDrop: Boolean = true)

private val MinActionWidth = 56.dp
private val ScrollingActionWidth = 62.dp

/** The header's three labels at the base font, with a three-digit count, and what doesn't grow around them. */
private val HeaderTextWidth = 208.dp
private val HeaderPadding = 68.dp

/** The bar stops at this width on a wide screen, and its header sits this far inside it in all. */
private val BarMaxWidth = 600.dp
private val HeaderInset = 40.dp

/**
 * Whether the count takes a line of its own. Text grows with the font and padding doesn't, so at a large font
 * the three no longer fit in a row, and the count would be cut down to a number.
 */
@Composable
private fun selectionCountStacks(): Boolean {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.coerceAtMost(BarMaxWidth) - HeaderInset
    return width < HeaderPadding + HeaderTextWidth * fontScale
}

/** What a list adds to its clearance while selecting, since this bar is taller than the tab bar and grows with the font. */
@Composable
fun selectionBarClearance(): androidx.compose.ui.unit.Dp {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    return (if (selectionCountStacks()) 54.dp else 30.dp) * fontScale
}

/**
 * The count rolls rather than swapping, so each tap visibly registers. Tapping it offers the other ways of
 * choosing, which would only crowd a bar that's mostly used one tap at a time.
 */
@Composable
private fun SelectionCount(selectedCount: Int, canOpen: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = KobbloDesign.colors
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(CapsuleShape)
            .clickable(interactionSource = source, indication = null, enabled = canOpen, onClick = onOpen),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedContent(
            targetState = selectedCount,
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically(tween(KobbloMotion.Default, easing = KobbloMotion.EaseOut)) { if (up) it / 2 else -it / 2 } +
                    fadeIn(KobbloMotion.fadeIn()))
                    .togetherWith(
                        slideOutVertically(tween(KobbloMotion.Fast, easing = KobbloMotion.EaseIn)) { if (up) -it / 2 else it / 2 } +
                            fadeOut(KobbloMotion.fadeOut())
                    )
            },
            contentAlignment = Alignment.Center,
            label = "selection_count"
        ) { count ->
            Text(
                text = pluralStringResource(R.plurals.tools_selected_count, count, count),
                style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 1
            )
        }
        if (canOpen) {
            Spacer(Modifier.width(4.dp))
            Icon(AppIcons.ChevronDown, contentDescription = stringResource(R.string.explorer_select_title), tint = colors.smoke, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SelectionTextButton(label: String, emphasized: Boolean = false, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(pressedScale = 0.94f, interactionSource = source)
            .clip(CapsuleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = KobbloType.body.copy(fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal),
            color = colors.action,
            maxLines = 1
        )
    }
}

@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = KobbloDesign.colors.action,
    width: androidx.compose.ui.unit.Dp = ScrollingActionWidth,
    takesDrop: Boolean = true
) {
    val haptics = com.kobblo.gallery.file.explorer.haptics.LocalHaptics.current
    val source = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(width)
            .then(
                if (takesDrop) {
                    Modifier.dragActionTarget(label, tint, SelectionActionShape) {
                        haptics.tap()
                        onClick()
                    }
                } else Modifier
            )
            .pressScale(pressedScale = 0.92f, interactionSource = source)
            .clip(SelectionActionShape)
            .clickable(interactionSource = source, indication = null) {
                haptics.tap()
                onClick()
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = KobbloType.tab,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
