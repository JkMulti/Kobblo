package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ReceivedFilesPrompt
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.localsend.TransferStatus
import com.kobblo.gallery.file.explorer.transfer.send.InstallOutcome
import com.kobblo.gallery.file.explorer.transfer.send.ReceivedActions
import com.kobblo.gallery.file.explorer.transfer.ui.SmallCapsule
import com.kobblo.gallery.file.explorer.transfer.ui.WideButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheetTitle
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.launch
import java.io.File

/**
 * What arrived, saying how much of it: "12 of 12 received", or plainly fewer if the transfer stopped
 * early. Open, Install, Show in Folder and Share are all one tap from here, and an app that has just
 * arrived offers Install first, which still takes the system's own confirmation.
 */
@Composable
fun ReceivedFilesBottomSheet(
    prompt: ReceivedFilesPrompt,
    onOpen: (FileItem) -> Unit,
    onOpenFolder: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val files = prompt.files
    val single = files.singleOrNull()
    // A whole folder lands in subfolders, so the place to open is what they all have in common.
    val targetFolder = remember(files) {
        files.mapNotNull { File(it.path).parent }.reduceOrNull { shared, next ->
            var candidate = File(shared)
            while (next != candidate.path && !next.startsWith(candidate.path + File.separator)) {
                candidate = candidate.parentFile ?: break
            }
            candidate.path
        }.orEmpty()
    }
    // The transfer is still on hand, and knows how many were meant to arrive.
    val session = remember { NearbyShareRepository.activeTransfer.value?.takeIf { it.isIncoming && prompt.expected == null } }
    val expected = prompt.expected ?: session?.files?.size?.coerceAtLeast(files.size) ?: files.size
    val stoppedEarly = files.size < expected
    val problem = session?.takeIf { it.status == TransferStatus.FAILED }?.errorMessage
    val totalBytes = files.sumOf { it.sizeBytes }
    val apps = files.filter { ReceivedActions.canInstall(it.name) }
    var installing by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(setOf<String>()) }
    var installError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun install(file: FileItem) {
        installing = true
        installError = null
        scope.launch {
            when (val outcome = ReceivedActions.install(context, File(file.path))) {
                InstallOutcome.Installed -> installed = installed + file.path
                InstallOutcome.HandedOver -> Unit
                is InstallOutcome.Failed -> installError = outcome.message
            }
            installing = false
        }
    }

    KobbloSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp)) {
            KobbloSheetTitle(
                text = stringResource(R.string.transfer_received_count, files.size, expected),
                subtitle = "${stringResource(R.string.transfer_from_peer, prompt.peerAlias)} · ${com.kobblo.gallery.file.explorer.data.core.FileSizes.label(totalBytes)}"
            )

            if (problem != null) {
                Text(
                    text = problem,
                    style = KobbloType.footnote,
                    color = colors.danger,
                    modifier = Modifier.padding(start = KobbloSpace.content, end = KobbloSpace.content, bottom = 12.dp)
                )
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                itemsIndexed(files, key = { _, file -> file.path }) { index, file ->
                    KobbloRow(
                        title = file.name,
                        subtitle = file.size,
                        modifier = Modifier
                            .padding(horizontal = KobbloSpace.screen)
                            .clip(KobbloShapes.groupItem(index, files.size))
                            .background(colors.snow),
                        leading = {
                            Icon(iconFor(file.type), contentDescription = null, tint = colors.action, modifier = Modifier.size(24.dp))
                        },
                        trailing = if (ReceivedActions.canInstall(file.name) && files.size > 1) {
                            {
                                SmallCapsule(
                                    stringResource(if (file.path in installed) R.string.transfer_installed else R.string.transfer_install),
                                    onClick = { install(file) },
                                    enabled = !installing && file.path !in installed
                                )
                            }
                        } else null,
                        showDivider = index < files.lastIndex,
                        onClick = { onOpen(file) }
                    )
                }
            }

            installError?.let {
                Text(
                    text = it,
                    style = KobbloType.footnote,
                    color = colors.danger,
                    modifier = Modifier.padding(horizontal = KobbloSpace.content, vertical = 8.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            // The one thing people most likely came for.
            when {
                single != null && apps.isNotEmpty() -> WideButton(
                    text = stringResource(if (single.path in installed) R.string.transfer_installed else R.string.transfer_install),
                    icon = AppIcons.Android,
                    enabled = !installing && single.path !in installed,
                    onClick = { install(single) }
                )
                single != null -> WideButton(
                    text = stringResource(R.string.transfer_open),
                    onClick = { onOpen(single) }
                )
                else -> WideButton(
                    text = stringResource(R.string.transfer_show_in_folder),
                    icon = AppIcons.FolderOpen,
                    onClick = { onOpenFolder(targetFolder) }
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                if (single != null) {
                    SmallCapsule(stringResource(R.string.transfer_show_in_folder), onClick = { onOpenFolder(targetFolder) })
                }
                SmallCapsule(stringResource(R.string.transfer_share), onClick = { ReceivedActions.share(context, files) })
            }
        }
    }
}

private fun iconFor(type: String): ImageVector = when (type) {
    "image" -> AppIcons.Photo
    "video" -> AppIcons.PlayRect
    "audio" -> AppIcons.Music
    "doc" -> AppIcons.FileText
    "apk" -> AppIcons.Android
    "archive" -> AppIcons.FileZip
    else -> AppIcons.File
}
