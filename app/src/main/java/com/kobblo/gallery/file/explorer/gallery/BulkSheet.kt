package com.kobblo.gallery.file.explorer.gallery

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloActionPair
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheetTitle
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.viewer.metadata.PhotoRotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CopySides = listOf(0, 2048, 1080)

private class DateOptions(val corrections: List<Pair<FileItem, Long>>, val canUndo: Boolean)

/**
 * What the selection bar's More offers on the photo grid: the things that only make sense for many photos at once.
 * Everything here keeps the selection out of the way once it's underway, and each one says how it went.
 */
@Composable
internal fun BulkActionsSheet(
    selected: List<FileItem>,
    onAction: (AppAction) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var savingCopies by remember { mutableStateOf(false) }
    var fixingDates by remember { mutableStateOf(false) }
    // Worked out before the sheet opens, since a sheet that grows afterwards leaves its last row off the bottom.
    val dates by produceState<DateOptions?>(null, selected) {
        value = withContext(Dispatchers.IO) {
            val index = MediaIndex.get(context)
            val facts = index.facts.value.ifEmpty { index.readFactsNow() }
            val now = System.currentTimeMillis()
            DateOptions(
                corrections = selected.mapNotNull { file ->
                    DateFromName.correction(file, facts[file.id]?.takenMillis ?: 0L, now)?.let { file to it }
                },
                canUndo = DateFixRecord.read(context).isNotEmpty()
            )
        }
    }
    val options = dates ?: return
    val corrections = options.corrections
    val canUndoDates = options.canUndo

    fun finish(block: () -> Unit) {
        onDismiss()
        onAction(AppAction.ClearSelection)
        block()
    }

    if (savingCopies) {
        GallerySheet(onDismiss) { SaveCopiesPage(selected, onCancel = onDismiss, onSave = { format, side -> finish { startSaveCopies(context, selected, format, side, onAction) } }) }
        return
    }

    if (fixingDates) {
        GallerySheet(onDismiss) {
            Column(modifier = Modifier.fillMaxWidth()) {
                KobbloSheetTitle(stringResource(R.string.gallery_bulk_fix_dates))
                KobbloGroupFooter(
                    if (selected.size == 1) stringResource(R.string.gallery_bulk_fix_date_note)
                    else pluralStringResource(R.plurals.gallery_bulk_fix_dates_note, corrections.size, corrections.size, selected.size)
                )
                Spacer(Modifier.height(8.dp))
                KobbloActionPair(
                    confirmLabel = stringResource(R.string.gallery_bulk_fix_dates_action),
                    onCancel = onDismiss,
                    onConfirm = { finish { startFixDates(context, corrections, onAction) } }
                )
            }
        }
        return
    }

    GallerySheet(onDismiss) {
        KobbloSheetTitle(pluralStringResource(R.plurals.gallery_bulk_title, selected.size, selected.size))
        KobbloGroup {
            val allFavorites = selected.isNotEmpty() && selected.all { it.isPinned }
            BulkRow(
                stringResource(if (allFavorites) R.string.remove_from_favorites else R.string.add_to_favorites),
                if (allFavorites) AppIcons.StarFill else AppIcons.Star,
                showDivider = true
            ) {
                finish { onAction(AppAction.SetFavorites(selected.map { it.path }, !allFavorites)) }
            }
            val turnable = selected.any { PhotoRotation.canRotate(it) }
            if (turnable) {
                BulkRow(stringResource(R.string.gallery_bulk_rotate_left), AppIcons.RotateLeft, showDivider = true) {
                    finish { startRotate(context, selected, 270, onAction) }
                }
                BulkRow(stringResource(R.string.gallery_bulk_rotate_right), AppIcons.Rotate, showDivider = true) {
                    finish { startRotate(context, selected, 90, onAction) }
                }
            }
            BulkRow(stringResource(R.string.viewer_share_clean), AppIcons.Share, showDivider = true) {
                finish { startShareClean(context, selected, onAction) }
            }
            BulkRow(stringResource(R.string.gallery_bulk_save_copies), AppIcons.Copy, showDivider = corrections.isNotEmpty() || canUndoDates) { savingCopies = true }
            if (corrections.isNotEmpty()) {
                BulkRow(stringResource(R.string.gallery_bulk_fix_dates), AppIcons.Calendar, showDivider = canUndoDates) { fixingDates = true }
            }
            if (canUndoDates) {
                BulkRow(stringResource(R.string.gallery_bulk_undo_dates), AppIcons.Undo) {
                    finish { startUndoDates(context, onAction) }
                }
            }
        }
    }
}

@Composable
private fun BulkRow(title: String, icon: ImageVector, showDivider: Boolean = false, onClick: () -> Unit) {
    KobbloRow(
        title = title,
        trailing = { Icon(icon, contentDescription = null, tint = KobbloDesign.colors.ink, modifier = Modifier.size(22.dp)) },
        showDivider = showDivider,
        onClick = onClick
    )
}

@Composable
private fun SaveCopiesPage(
    selected: List<FileItem>,
    onCancel: () -> Unit,
    onSave: (CopyFormat, Int) -> Unit
) {
    var format by remember { mutableIntStateOf(0) }
    var side by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxWidth()) {
        KobbloSheetTitle(stringResource(R.string.gallery_bulk_save_copies))
        KobbloGroupHeader(stringResource(R.string.gallery_bulk_format))
        KobbloSegmentedControl(
            options = CopyFormat.entries.map { if (it == CopyFormat.WEBP) "WebP" else it.name },
            selectedIndex = format,
            onSelect = { format = it },
            modifier = Modifier.padding(horizontal = KobbloSpace.screen)
        )
        KobbloGroupHeader(stringResource(R.string.gallery_bulk_size))
        KobbloSegmentedControl(
            options = CopySides.map { if (it == 0) stringResource(R.string.gallery_bulk_size_original) else "$it px" },
            selectedIndex = side,
            onSelect = { side = it },
            modifier = Modifier.padding(horizontal = KobbloSpace.screen)
        )
        KobbloGroupFooter(pluralStringResource(R.plurals.gallery_bulk_save_note, selected.size, selected.size))
        Spacer(Modifier.height(8.dp))
        KobbloActionPair(
            confirmLabel = stringResource(R.string.gallery_bulk_save),
            onCancel = onCancel,
            onConfirm = { onSave(CopyFormat.entries[format], CopySides[side]) }
        )
    }
}

private fun startFixDates(context: android.content.Context, corrections: List<Pair<FileItem, Long>>, onAction: (AppAction) -> Unit) {
    BulkWork.fixDates(context, corrections) { done, failed ->
        val res = context.resources
        val fixed = res.getQuantityString(R.plurals.gallery_bulk_dates_fixed, done, done)
        onAction(AppAction.ShowToast(if (failed > 0) fixed + ". " + res.getQuantityString(R.plurals.gallery_bulk_dates_failed, failed, failed) else fixed))
        onAction(AppAction.Reload)
    }
}

private fun startUndoDates(context: android.content.Context, onAction: (AppAction) -> Unit) {
    BulkWork.undoDateFix(context) { done, _ ->
        onAction(AppAction.ShowToast(context.resources.getQuantityString(R.plurals.gallery_bulk_dates_restored, done, done)))
        onAction(AppAction.Reload)
    }
}

private fun startRotate(context: android.content.Context, files: List<FileItem>, degrees: Int, onAction: (AppAction) -> Unit) {
    BulkWork.rotate(context, files, degrees) { done, skipped, failed ->
        val res = context.resources
        val message = when {
            failed > 0 -> res.getQuantityString(R.plurals.gallery_bulk_rotate_failed, failed, failed)
            skipped > 0 -> res.getQuantityString(R.plurals.gallery_bulk_rotated, done, done) + ". " +
                res.getQuantityString(R.plurals.gallery_bulk_rotate_skipped, skipped, skipped)
            else -> res.getQuantityString(R.plurals.gallery_bulk_rotated, done, done)
        }
        onAction(AppAction.ShowToast(message))
        onAction(AppAction.Reload)
    }
}

private fun startSaveCopies(context: android.content.Context, files: List<FileItem>, format: CopyFormat, side: Int, onAction: (AppAction) -> Unit) {
    BulkWork.saveCopies(context, files, format, side) { done, failed ->
        val res = context.resources
        val saved = res.getQuantityString(R.plurals.gallery_bulk_saved, done, done)
        onAction(AppAction.ShowToast(if (failed > 0) saved + ". " + res.getQuantityString(R.plurals.gallery_bulk_save_failed, failed, failed) else saved))
        onAction(AppAction.Reload)
    }
}

private fun startShareClean(context: android.content.Context, files: List<FileItem>, onAction: (AppAction) -> Unit) {
    GalleryWorkScope.launch {
        val (clean, skipped) = BulkWork.cleanCopies(context, files)
        if (clean.isEmpty()) {
            onAction(AppAction.ShowToast(context.getString(R.string.viewer_metadata_failed)))
            return@launch
        }
        if (skipped > 0) {
            onAction(AppAction.ShowToast(context.resources.getQuantityString(R.plurals.gallery_bulk_share_skipped, skipped, skipped)))
        }
        val uris = ArrayList(clean.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.viewer_share_clean)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
