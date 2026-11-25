package com.kobblo.gallery.file.explorer.ui.expressive

import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.FileDetails
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.rowDivider

/**
 * A file's menu, reached by holding it or tapping its "..." button. Actions read like a context
 * menu, with the label first and the glyph at the far edge, and the details sit below them.
 */
@Composable
fun FileInfoBottomSheet(
    file: FileItem,
    fileDetails: FileDetails?,
    onDismissRequest: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onToggleHide: () -> Unit = {},
    onToggleLock: () -> Unit = {},
    onOpenWith: (() -> Unit)? = null,
    onSendNearby: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    onCompress: (() -> Unit)? = null,
    onExtract: (() -> Unit)? = null,
    onShowInFolder: (() -> Unit)? = null
) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current

    KobbloSheet(onDismissRequest = onDismissRequest) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)
        ) {
            SheetSubject(file)

            // What would show a locked file or hand it to someone goes, or the password guards nothing.
            val canShow = !file.isLocked
            KobbloGroup {
                if (onShowInFolder != null) {
                    ActionRow(stringResource(R.string.explorer_show_in_folder), AppIcons.FolderOpen, onShowInFolder, showDivider = true)
                }
                if (canShow && onOpenWith != null) {
                    ActionRow(stringResource(R.string.explorer_open_with), AppIcons.OpenExternal, onOpenWith, showDivider = true)
                }
                if (canShow) {
                    com.kobblo.gallery.file.explorer.tools.files.FileToolRows(file, onDismissRequest)
                    ActionRow(stringResource(R.string.select_action), AppIcons.CheckCircle, onSelect, showDivider = true)
                }
                ActionRow(
                    stringResource(if (file.isPinned) R.string.remove_from_favorites else R.string.add_to_favorites),
                    if (file.isPinned) AppIcons.StarFill else AppIcons.Star,
                    onToggleFavorite
                )
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                if (canShow) {
                    ActionRow(stringResource(R.string.share), AppIcons.Share, onShare, showDivider = true)
                }
                if (canShow && onSendNearby != null) {
                    ActionRow(stringResource(R.string.transfer_send_nearby), AppIcons.Send, onSendNearby, showDivider = true)
                }
                if (onCopy != null) ActionRow(stringResource(R.string.explorer_copy), AppIcons.Copy, onCopy, showDivider = true)
                if (onMove != null) ActionRow(stringResource(R.string.explorer_move), AppIcons.Cut, onMove, showDivider = true)
                ActionRow(stringResource(R.string.rename_action), AppIcons.Pencil, onRename, showDivider = true)
                if (canShow && onExtract != null) ActionRow(stringResource(R.string.explorer_extract), AppIcons.FolderOpen, onExtract, showDivider = true)
                if (canShow && onCompress != null) ActionRow(stringResource(R.string.explorer_compress), AppIcons.FileZip, onCompress, showDivider = true)
                ActionRow(
                    if (file.isLocked) stringResource(R.string.unlock_file) else stringResource(R.string.lock_file),
                    if (file.isLocked) AppIcons.LockOpen else AppIcons.Lock,
                    onToggleLock,
                    showDivider = true
                )
                ActionRow(
                    if (file.isHidden) stringResource(R.string.screen_unhide) else stringResource(R.string.screen_hide),
                    if (file.isHidden) AppIcons.Eye else AppIcons.EyeOff,
                    onToggleHide
                )
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                ActionRow(stringResource(R.string.delete_label), AppIcons.Trash, onDelete, tint = colors.danger)
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))

            KobbloGroup {
                if (fileDetails == null) {
                    DetailsLoading()
                } else {
                    val isFolder = file.type == "folder" || fileDetails.isFolder
                    val folderSize = if (isFolder && fileDetails.path.startsWith("/")) {
                        com.kobblo.gallery.file.explorer.tools.storage.rememberFolderSize(fileDetails.path).value
                    } else null
                    val stats = listOfNotNull(
                        stringResource(R.string.tools_inspect_type) to if (isFolder) stringResource(R.string.tools_inspect_folder) else (file.type.replaceFirstChar { it.uppercase() }),
                        stringResource(R.string.viewer_pdf_detail_size) to when {
                            !isFolder -> fileDetails.size
                            folderSize != null -> formatFileSize(folderSize.bytes)
                            else -> stringResource(R.string.tools_calculating)
                        },
                        fileDetails.itemCount?.let { stringResource(R.string.screen_detail_items) to it.toString() },
                        stringResource(R.string.tools_inspect_modified) to fileDetails.changed,
                        fileDetails.format?.let { stringResource(R.string.screen_detail_format) to it },
                        fileDetails.resolution?.let { stringResource(R.string.screen_detail_resolution) to it },
                        fileDetails.duration?.let { stringResource(R.string.screen_detail_duration) to it }
                    )
                    stats.forEach { (label, value) -> DetailRow(label, value) }
                }
                // Tapping copies it. A path is hard to do anything else with, and impossible to retype.
                val location = fileDetails?.path ?: file.path
                KobbloRow(
                    title = stringResource(R.string.tools_inspect_location),
                    subtitle = location,
                    trailing = if (location.startsWith("/")) {
                        { Icon(AppIcons.Copy, contentDescription = stringResource(R.string.explorer_copy_path), tint = colors.smoke, modifier = Modifier.size(18.dp)) }
                    } else null,
                    showDivider = file.type != "folder" && file.path.startsWith("/"),
                    onClick = if (location.startsWith("/")) {
                        { com.kobblo.gallery.file.explorer.explorer.ExplorerUi.copyPath(context, location) }
                    } else null
                )
                if (file.type != "folder" && file.path.startsWith("/")) {
                    com.kobblo.gallery.file.explorer.tools.files.ChecksumRows(file.path)
                }
            }
        }
    }
}

/** What the sheet is about: the file's picture, its name, and the line a list shows under it. */
@Composable
private fun SheetSubject(file: FileItem) {
    val colors = KobbloDesign.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = KobbloSpace.screen, end = KobbloSpace.screen, top = KobbloSpace.xs, bottom = KobbloSpace.md)
    ) {
        ExpressiveFileThumbnail(file = file, isSelected = false, size = 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (file.type == "folder") rememberFolderMeta(file) else formatFileMeta(file),
                style = KobbloType.footnote,
                color = colors.smoke,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Stands in for the details until they have been read. */
@Composable
private fun DetailsLoading() {
    val colors = KobbloDesign.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        KobbloLoader(modifier = Modifier.size(18.dp), color = colors.action, strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text = stringResource(R.string.screen_loading_details), style = KobbloType.footnote, color = colors.smoke)
    }
}

/** A label and its value. The value takes the rest of the row, so every value ends at the same edge. */
@Composable
internal fun DetailRow(label: String, value: String) {
    val colors = KobbloDesign.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = com.kobblo.gallery.file.explorer.ui.design.KobbloSize.row)
            .rowDivider(true, KobbloSpace.inset, colors.hairline)
            .padding(horizontal = KobbloSpace.inset, vertical = KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = KobbloType.body, color = colors.ink, maxLines = 1)
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = KobbloType.body.copy(textAlign = TextAlign.End),
            color = colors.smoke,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    showDivider: Boolean = false,
    tint: Color = KobbloDesign.colors.ink
) {
    KobbloRow(
        title = title,
        titleColor = tint,
        trailing = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) },
        showDivider = showDivider,
        onClick = onClick
    )
}
