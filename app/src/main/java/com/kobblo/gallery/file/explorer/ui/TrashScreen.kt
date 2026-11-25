package com.kobblo.gallery.file.explorer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.explorer.FolderGridCells
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertAction
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertCancel
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoader
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTextField
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloReadableWidth
import com.kobblo.gallery.file.explorer.ui.expressive.BrowseGridCard
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveConfirmationSheet
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSwitch
import com.kobblo.gallery.file.explorer.ui.expressive.KobbloFileRow
import com.kobblo.gallery.file.explorer.ui.expressive.PageHeader

/** Whatever Recently Deleted has up over its list. Only one thing at a time. */
private sealed interface BinPrompt {
    /** What can be done with one deleted item. */
    data class ItemMenu(val file: FileItem) : BinPrompt

    /** Asking before one item is deleted for good. */
    data class DeleteForever(val file: FileItem) : BinPrompt

    /** Asking before everything is deleted for good. */
    data object DeleteEverything : BinPrompt

    /** Whether deleted files are kept at all, and for how long. */
    data object Settings : BinPrompt
}

/**
 * Recently Deleted: what's been deleted and can still be had back. Nothing here opens. Tapping an
 * item, however it's shown, offers to restore it or delete it for good, and the two quiet buttons
 * above the list do the same for everything at once.
 */
@Composable
fun TrashScreen(
    state: AppState,
    onAction: (AppAction) -> Unit,
    onClose: () -> Unit
) {
    var prompt by remember { mutableStateOf<BinPrompt?>(null) }
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val deleted = state.trashFiles
    // The bin knows each item by the name it was stored under; one without such a name can't be acted on.
    val everything = { deleted.mapNotNull { it.encodedTrashName } }

    BackHandler(onBack = onClose)
    // The list is read when the screen opens, unless an earlier visit left it filled in.
    LaunchedEffect(Unit) { if (deleted.isEmpty()) onAction(AppAction.RefreshTrash) }

    Column(modifier = Modifier.kobbloReadableWidth().navigationBarsPadding()) {
        PageHeader(
            title = stringResource(R.string.recently_deleted),
            subtitle = binSubtitle(state),
            // The bin is reached from Albums as well as from Browse, and goes back to whichever it was.
            backLabel = stringResource(if (state.location == "gallery") R.string.gallery_albums else R.string.nav_browse),
            onBack = onClose,
            actions = {
                KobbloIconButton(
                    icon = if (state.isListMode) AppIcons.Grid else AppIcons.List,
                    description = stringResource(if (state.isListMode) R.string.view_show_as_icons else R.string.view_show_as_list),
                    onClick = {
                        haptics.tap()
                        onAction(AppAction.ToggleViewMode)
                    }
                )
                KobbloIconButton(
                    icon = AppIcons.Gear,
                    description = stringResource(R.string.view_trash_settings),
                    onClick = {
                        haptics.tap()
                        prompt = BinPrompt.Settings
                    }
                )
            }
        )

        // The two things that act on everything, quiet enough not to be pressed by accident.
        if (deleted.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.xs),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = { onAction(AppAction.RestoreTrashFiles(everything())) }) {
                    Text(stringResource(R.string.view_restore_all), style = KobbloType.callout, color = colors.action)
                }
                TextButton(onClick = { prompt = BinPrompt.DeleteEverything }) {
                    Text(stringResource(R.string.view_delete_all), style = KobbloType.callout, color = colors.danger)
                }
            }
        }
        Spacer(Modifier.height(KobbloSpace.xxs))

        Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            when {
                state.trashIsLoading -> KobbloLoader(Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
                deleted.isEmpty() -> KobbloMessage(icon = AppIcons.Trash, title = stringResource(R.string.bin_is_empty))
                else -> DeletedFiles(
                    state = state,
                    // Enough to scroll the last item clear of the navigation bar, and of the mini player when there is one.
                    clearance = KobbloSpace.md + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                        if (state.currentAudioFile != null) KobbloSpace.miniPlayerClearance else 0.dp,
                    onPick = { prompt = BinPrompt.ItemMenu(it) }
                )
            }
        }
    }

    when (val open = prompt) {
        null -> Unit
        is BinPrompt.ItemMenu -> ItemMenuSheet(
            title = open.file.name,
            subtitle = open.file.size,
            choice = MenuChoice(
                label = stringResource(R.string.restore_action),
                icon = AppIcons.Restore,
                onPick = {
                    prompt = null
                    open.file.encodedTrashName?.let { onAction(AppAction.RestoreTrashFiles(listOf(it))) }
                }
            ),
            // Deleting for good is asked about first, so the menu gives way to the question.
            removal = MenuChoice(
                label = stringResource(R.string.delete_permanently_action),
                icon = AppIcons.Trash,
                onPick = { prompt = BinPrompt.DeleteForever(open.file) }
            ),
            onDismiss = { prompt = null }
        )
        is BinPrompt.DeleteForever -> ExpressiveConfirmationSheet(
            title = stringResource(R.string.view_delete_named_title, open.file.name),
            subtitle = stringResource(R.string.view_trash_delete_body),
            onConfirm = {
                haptics.delete()
                prompt = null
                open.file.encodedTrashName?.let { onAction(AppAction.PermanentlyDeleteTrashFiles(listOf(it))) }
            },
            onDismissRequest = { prompt = null }
        )
        BinPrompt.DeleteEverything -> ExpressiveConfirmationSheet(
            title = stringResource(R.string.view_trash_delete_everything_title),
            subtitle = stringResource(R.string.view_trash_delete_everything_body),
            confirmLabel = stringResource(R.string.view_delete_all),
            onConfirm = {
                haptics.delete()
                prompt = null
                onAction(AppAction.PermanentlyDeleteTrashFiles(everything()))
            },
            onDismissRequest = { prompt = null }
        )
        BinPrompt.Settings -> {
            // The dialog only asks for a number, so the unit goes back as it was when the dialog opened.
            val unit = remember { state.recycleBinRetentionUnit }
            BinSettingsDialog(
                keeping = state.isRecycleBinEnabled,
                keepFor = state.recycleBinRetentionValue,
                onSave = { keeping, keepFor ->
                    prompt = null
                    onAction(AppAction.SetRecycleBinSettings(keeping, keepFor, unit))
                },
                onDismiss = { prompt = null }
            )
        }
    }
}

/** The line under the title: how many items there are, and how long they have before they go. */
@Composable
private fun binSubtitle(state: AppState): String {
    val count = state.trashFiles.size
    val items = pluralStringResource(R.plurals.items_count, count, count)
    if (!state.isRecycleBinEnabled) return stringResource(R.string.view_trash_subtitle_not_keeping, items)
    val keptFor = retentionDurationText(state.recycleBinRetentionValue, state.recycleBinRetentionUnit)
    return stringResource(R.string.view_trash_subtitle_kept, items, keptFor)
}

/**
 * The deleted files, as rows or as tiles, whichever Browse is set to. They look as they do in a
 * folder, but every touch, the tap, the hold and the menu button alike, picks the item.
 */
@Composable
private fun DeletedFiles(state: AppState, clearance: Dp, onPick: (FileItem) -> Unit) {
    val files = state.trashFiles
    // An item's place in the list is part of its key, so no two can ever share one.
    val key = { index: Int, file: FileItem -> "${file.encodedTrashName ?: file.id}_$index" }
    val fill = Modifier.fillMaxWidth().wrapContentHeight()

    if (state.isListMode) {
        LazyColumn(modifier = fill, contentPadding = PaddingValues(bottom = clearance)) {
            itemsIndexed(files, key = key) { index, file ->
                KobbloFileRow(
                    file = file,
                    isSelected = false,
                    onSelect = { onPick(file) },
                    onClick = { onPick(file) },
                    onMenuClick = { onPick(file) },
                    modifier = Modifier.animateItem(fadeInSpec = null),
                    groupIndex = index,
                    groupCount = files.size
                )
            }
        }
    } else {
        val columns = state.gridColumns.coerceIn(2, 4)
        LazyVerticalGrid(
            columns = FolderGridCells(columns),
            modifier = fill,
            contentPadding = PaddingValues(start = KobbloSpace.screen, end = KobbloSpace.screen, bottom = clearance),
            verticalArrangement = Arrangement.spacedBy(KobbloSpace.md),
            horizontalArrangement = Arrangement.spacedBy(KobbloSpace.sm)
        ) {
            itemsIndexed(files, key = key) { _, file ->
                BrowseGridCard(
                    file = file,
                    isSelected = false,
                    onSelect = { onPick(file) },
                    onClick = { onPick(file) },
                    onMenuClick = { onPick(file) },
                    columns = columns,
                    modifier = Modifier.animateItem(fadeInSpec = null)
                )
            }
        }
    }
}

/**
 * The bin's two settings: whether deleted files are kept, and for how long. [onSave] is given both
 * as they stand when Save is pressed; the length only shows while keeping is on, but is saved
 * either way, so turning the bin back on finds it as it was left.
 */
@Composable
private fun BinSettingsDialog(
    keeping: Boolean,
    keepFor: Int,
    onSave: (keeping: Boolean, keepFor: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var keepingNow by remember { mutableStateOf(keeping) }
    var keepForTyped by remember { mutableStateOf(keepFor.toString()) }

    GradientAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recently_deleted)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KobbloSpace.md)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(stringResource(R.string.enable_bin), color = KobbloDesign.colors.ink)
                    ExpressiveSwitch(checked = keepingNow, onCheckedChange = { keepingNow = it })
                }
                if (keepingNow) {
                    KobbloTextField(
                        value = keepForTyped,
                        onValueChange = { if (BinRetention.canBeTyped(it)) keepForTyped = it },
                        placeholder = stringResource(R.string.view_trash_days_to_keep),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        },
        confirmButton = {
            KobbloAlertAction(
                label = stringResource(R.string.save_action),
                onClick = { onSave(keepingNow, BinRetention.valueOf(keepForTyped)) }
            )
        },
        dismissButton = { KobbloAlertCancel(onClick = onDismiss) }
    )
}

/**
 * How long Recently Deleted keeps files, such as "30 days", in the person's language. A unit that
 * isn't one of the known ones is shown as it was stored.
 */
@Composable
internal fun retentionDurationText(value: Int, unit: String): String {
    val plural = BinRetention.pluralFor(unit) ?: return BinRetention.plainText(value, unit)
    return pluralStringResource(plural, value, value)
}
