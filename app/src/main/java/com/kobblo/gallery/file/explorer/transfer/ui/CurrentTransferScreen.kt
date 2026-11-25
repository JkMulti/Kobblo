package com.kobblo.gallery.file.explorer.transfer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.localsend.TransferFileItem
import com.kobblo.gallery.file.explorer.localsend.TransferStatus
import com.kobblo.gallery.file.explorer.transfer.send.TransferFiles
import com.kobblo.gallery.file.explorer.transfer.send.TransferFormat
import com.kobblo.gallery.file.explorer.ui.handleFileOpen
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.hub.HubScreen
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import java.io.File

/** Every file in the transfer, with how far each has got. */
@Composable
fun CurrentTransferScreen(state: AppState, onAction: (AppAction) -> Unit) {
    val session by NearbyShareRepository.activeTransfer.collectAsState()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val colors = KobbloDesign.colors
    val current = session
    LaunchedEffect(current == null) {
        if (current == null) Hub.back()
    }
    if (current == null) return

    // The transfer's own title holds a device's name, which is too long for a large title.
    HubScreen(title = stringResource(R.string.transfer_files_title), backLabel = stringResource(R.string.transfer_send_tab)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            )
        ) {
            item(key = "summary") {
                Text(
                    text = TransferFormat.title(context, current),
                    style = KobbloType.body.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.ink,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.content)
                )
                Text(
                    text = TransferFormat.detail(context, current),
                    style = KobbloType.footnote,
                    color = if (current.status == TransferStatus.FAILED) colors.danger else colors.smoke,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.content, vertical = 4.dp)
                )
                if (!current.status.isFinal) {
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(current.progress, modifier = Modifier.padding(horizontal = KobbloSpace.screen))
                }
                Spacer(Modifier.height(12.dp))
            }
            itemsIndexed(current.files, key = { index, item -> "$index-${item.fileId}" }) { index, item ->
                val isCurrent = index == current.currentFileIndex && !item.isFinished && !current.status.isFinal
                FileProgressRow(
                    item = item,
                    moving = isCurrent,
                    bytes = if (isCurrent) current.currentFileBytes else item.bytesTransferred,
                    canOpen = current.isIncoming && item.isFinished && item.localPath != null,
                    showDivider = index < current.files.lastIndex,
                    modifier = Modifier
                        .padding(horizontal = KobbloSpace.screen)
                        .clip(KobbloShapes.groupItem(index, current.files.size)),
                    onOpen = {
                        item.localPath?.let { path ->
                            // Viewers are drawn beneath these screens.
                            Hub.close()
                            handleFileOpen(TransferFiles.fromFile(context, File(path)), context, state, focus, onAction)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun FileProgressRow(
    item: TransferFileItem,
    moving: Boolean,
    bytes: Long,
    canOpen: Boolean,
    showDivider: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val folder = item.fileName.substringBeforeLast('/', "")
    val size = TransferFormat.size(context, item.size)
    KobbloRow(
        title = item.fileName.substringAfterLast('/'),
        subtitle = when {
            item.failure != null -> item.failure
            moving -> "${TransferFormat.size(context, bytes)} / $size"
            folder.isNotEmpty() -> "$folder · $size"
            else -> size
        },
        modifier = modifier.background(colors.snow),
        showDivider = showDivider,
        dividerInset = KobbloSpace.md,
        trailing = when {
            item.isFinished -> {
                { Icon(AppIcons.CheckCircleFill, contentDescription = stringResource(R.string.transfer_done), tint = colors.action, modifier = Modifier.size(20.dp)) }
            }
            item.failure != null -> {
                { Icon(AppIcons.Alert, contentDescription = item.failure, tint = colors.danger, modifier = Modifier.size(20.dp)) }
            }
            else -> null
        },
        below = if (moving) {
            {
                Spacer(Modifier.height(6.dp))
                ProgressLine(if (item.size > 0) bytes.toFloat() / item.size else 0f, height = 4.dp)
            }
        } else null,
        onClick = if (canOpen) onOpen else null
    )
}
