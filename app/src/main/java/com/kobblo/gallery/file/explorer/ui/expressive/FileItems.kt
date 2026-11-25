package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.explorer.LocalRowHold
import com.kobblo.gallery.file.explorer.explorer.drag.FileDrag
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSize
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.pressedRowFill
import com.kobblo.gallery.file.explorer.ui.design.rowDivider
import java.text.DateFormat
import java.util.Date

// A file as a list shows it: a row in an inset group, or a card in a grid.

/** Kobblo Design's tile rounds its corners by this share of its shorter side, which a flying photo keeps. */
internal const val TileCornerShare = 0.24f

/** How faint a hidden file is drawn, so it reads as there but set aside. */
private const val HiddenAlpha = 0.55f

/**
 * What the files of one list do when touched: a tap opens the file, a tap on its picture selects it,
 * and its menu comes up from the "..." button or a hold. One of these serves every row and card of a
 * list, so none of them has to be handed three callbacks of its own.
 */
internal class FileTaps(
    private val onAction: (AppAction) -> Unit,
    val open: (FileItem) -> Unit,
    val menu: (FileItem) -> Unit
) {
    fun toggleSelected(file: FileItem) = onAction(AppAction.ToggleSelection(file.id))
}

@Composable
internal fun rememberFileTaps(
    onAction: (AppAction) -> Unit,
    onFileClick: (FileItem) -> Unit,
    onFileMenuClick: (FileItem) -> Unit
): FileTaps = remember(onAction, onFileClick, onFileMenuClick) { FileTaps(onAction, onFileClick, onFileMenuClick) }

/**
 * What holding a file does. Holding one that is already chosen picks the whole selection up, to be
 * dragged somewhere. Any other hold is for the file's menu: in a list that can drag-select, the menu
 * waits for the finger to lift, since moving instead selects a run of files; elsewhere it opens at once.
 */
@Composable
private fun rememberHold(file: FileItem, isSelected: Boolean, onMenu: () -> Unit): () -> Unit {
    val haptics = LocalHaptics.current
    val listHold = LocalRowHold.current
    return {
        val pickedUp = isSelected && FileDrag.lift(file)
        if (!pickedUp) {
            haptics.tap()
            val waitsForRelease = listHold?.arm(onMenu) == true
            if (!waitsForRelease) onMenu()
        }
    }
}

/**
 * The row every file list uses. Pass [groupIndex] and [groupCount] to draw it as part of a white
 * inset group: only the group's first and last rows round their corners, and every row but the
 * last draws a separator beginning under its title.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun KobbloFileRow(
    file: FileItem,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    groupIndex: Int = 0,
    groupCount: Int = 1,
    flashed: Boolean = false
) {
    val colors = KobbloDesign.colors
    val presses = remember { MutableInteractionSource() }
    val pressed = pressedRowFill(presses)
    val hold = rememberHold(file, isSelected, onMenuClick)
    // Chosen, or pointed out after being revealed: either way the tint washes over the row.
    val wash by animateColorAsState(
        targetValue = colors.action.copy(alpha = if (isSelected || flashed) 0.12f else 0f),
        animationSpec = KobbloMotion.state(),
        label = "row_selected"
    )
    val separatorStart = KobbloSpace.inset + KobbloSize.thumbnail + KobbloSpace.leadingGap

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .widthIn(max = KobbloSpace.readableWidth)
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen)
            .clip(KobbloShapes.groupItem(groupIndex, groupCount))
            .background(colors.snow)
            .background(wash)
            .background(pressed)
            .alpha(if (file.isHidden) HiddenAlpha else 1f)
            .combinedClickable(interactionSource = presses, indication = null, onLongClick = hold, onClick = onClick)
            .rowDivider(groupIndex < groupCount - 1, separatorStart, colors.hairline)
            // At least as tall as the picture with the inset above and below, so the picture sits as far
            // from the group's top and bottom as from its side.
            .heightIn(min = KobbloSize.fileRow)
            .padding(horizontal = KobbloSpace.inset, vertical = KobbloSpace.xs)
    ) {
        // The picture is its own target: tapping it chooses the file rather than opening it.
        Box(
            Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = hold,
                onClick = onSelect
            )
        ) {
            ExpressiveFileThumbnail(file, isSelected, size = KobbloSize.thumbnail, flightCorner = KobbloSize.thumbnail * TileCornerShare)
        }
        Spacer(Modifier.width(KobbloSpace.leadingGap))
        Column(modifier = Modifier.weight(1f)) {
            NameLine(file)
            Text(
                text = if (file.type == "folder") rememberFolderMeta(file) else formatFileMeta(file),
                style = KobbloType.footnote,
                color = colors.smoke,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(KobbloSpace.xs))
        KobbloIconButton(
            icon = AppIcons.MoreHoriz,
            description = stringResource(R.string.menu_content_desc),
            onClick = onMenuClick,
            size = KobbloSize.rowButton,
            iconSize = 17.dp,
            tint = colors.smoke,
            background = colors.mist
        )
    }
}

/** The file's name, and a star after it for a favorite. A long name gives way first, so the star is never pushed off the row. */
@Composable
private fun NameLine(file: FileItem) {
    val colors = KobbloDesign.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(file.name, style = KobbloType.body, color = colors.ink, overflow = TextOverflow.Ellipsis, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
        if (file.isPinned) {
            Icon(
                AppIcons.StarFill,
                contentDescription = stringResource(R.string.screen_favorite),
                tint = colors.pewter,
                modifier = Modifier.padding(start = 4.dp).size(13.dp)
            )
        }
    }
}

/** [KobbloFileRow] for a list that answers every file the same way. */
@Composable
internal fun FileRow(
    file: FileItem,
    isSelected: Boolean,
    taps: FileTaps,
    modifier: Modifier = Modifier,
    groupIndex: Int = 0,
    groupCount: Int = 1,
    flashed: Boolean = false
) = KobbloFileRow(
    file, isSelected,
    onSelect = { taps.toggleSelected(file) },
    onClick = { taps.open(file) },
    onMenuClick = { taps.menu(file) },
    modifier = modifier,
    groupIndex = groupIndex,
    groupCount = groupCount,
    flashed = flashed
)

/**
 * Grid view: the object itself with its name centered beneath it. There is no card behind it, and
 * holding it opens its menu. With four columns there is only room for the name; with fewer, a folder
 * says how much it holds and a file when it changed and how big it is.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BrowseGridCard(
    file: FileItem,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    columns: Int = 3,
    modifier: Modifier = Modifier
) {
    val presses = remember { MutableInteractionSource() }
    val hold = rememberHold(file, isSelected, onMenuClick)
    val isFolder = file.type == "folder"
    val roomy = columns < 4

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (file.isHidden) HiddenAlpha else 1f)
            .pressScale(0.95f, presses)
            .combinedClickable(interactionSource = presses, indication = null, onLongClick = hold, onClick = onClick)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            ExpressiveFileThumbnail(
                file = file,
                isSelected = isSelected,
                // A folder fills its cell. A sheet of paper or a picture is drawn a little smaller, as a thing lying in it.
                size = if (isFolder) maxWidth else maxWidth * 0.92f,
                shape = KobbloShapes.sm,
                flightCorner = 12.dp
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = file.name,
            style = if (roomy) KobbloType.footnote.copy(fontWeight = FontWeight.Medium) else CardNoteStyle,
            color = KobbloDesign.colors.ink,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2
        )
        if (roomy && isFolder) {
            CardNote(rememberFolderMeta(file, withSize = false))
        } else if (roomy) {
            val changed = remember(file.lastModified) { shortDateOf(file) }
            // A date is never cut short: there is either room for all of it or it is clipped at the edge.
            if (changed.isNotEmpty()) CardNote(changed, overflow = TextOverflow.Clip)
            CardNote(file.size.ifEmpty { formatFileSize(file.sizeBytes) })
        }
    }
}

/** [BrowseGridCard] for a grid that answers every file the same way. */
@Composable
internal fun FileCard(
    file: FileItem,
    isSelected: Boolean,
    taps: FileTaps,
    columns: Int = 3,
    modifier: Modifier = Modifier
) = BrowseGridCard(
    file, isSelected,
    onSelect = { taps.toggleSelected(file) },
    onClick = { taps.open(file) },
    onMenuClick = { taps.menu(file) },
    columns = columns,
    modifier = modifier
)

private val CardNoteStyle = KobbloType.caption.copy(fontWeight = FontWeight.Normal)

/** One small line under a card's name. */
@Composable
private fun CardNote(text: String, overflow: TextOverflow = TextOverflow.Ellipsis) {
    Text(
        text = text,
        style = CardNoteStyle,
        color = KobbloDesign.colors.smoke,
        textAlign = TextAlign.Center,
        overflow = overflow,
        maxLines = 1
    )
}

/** The day a file changed in the language's shortest form, or whatever its listing said when it carries no timestamp. */
private fun shortDateOf(file: FileItem): String =
    if (file.lastModified > 0) DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.lastModified)) else file.changed
