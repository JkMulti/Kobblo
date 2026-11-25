package com.kobblo.gallery.file.explorer.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.storage.StorageVolumes
import com.kobblo.gallery.file.explorer.tools.storage.VolumeInfo
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Chooses a destination folder. Going up from a volume's top lists the volumes, when there are several. */
@Composable
internal fun ToolFolderPicker(
    title: String,
    confirmLabel: String,
    initialPath: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val colors = KobbloDesign.colors
    val volumes by StorageVolumes.volumes.collectAsState()
    var path by remember { mutableStateOf<String?>(initialPath) }
    val current = path

    val folders by produceState<List<File>?>(initialValue = null, current) {
        value = null
        value = if (current == null) emptyList() else withContext(Dispatchers.IO) {
            File(current).listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
        }
    }

    fun goUp() {
        val at = current ?: return onDismiss()
        val isVolumeRoot = volumes.any { it.path == at }
        path = when {
            !isVolumeRoot -> File(at).parent
            volumes.size > 1 -> null
            else -> at
        }
    }

    val isTop = current == null || (volumes.size <= 1 && volumes.any { it.path == current })

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(colors.canvas)) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Box(modifier = Modifier.fillMaxWidth().height(com.kobblo.gallery.file.explorer.ui.design.KobbloSize.toolbar).padding(horizontal = KobbloSpace.xs)) {
                    if (isTop) {
                        // A cross in a round button, where the back button sits once inside a folder.
                        com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton(
                            icon = AppIcons.Close,
                            description = stringResource(R.string.cancel),
                            onClick = onDismiss,
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = KobbloSpace.xs)
                        )
                    } else {
                        com.kobblo.gallery.file.explorer.ui.design.KobbloBackButton(
                            onClick = { goUp() },
                            // The bar's own 8dp, and this, put the button on the screen's 16dp margin.
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = KobbloSpace.xs)
                        )
                    }
                    Text(
                        text = title,
                        style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 88.dp)
                    )
                }
                Text(
                    text = current?.let(::displayPath) ?: stringResource(R.string.locations_title),
                    style = KobbloType.largeTitle,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = KobbloSpace.screen).padding(bottom = 12.dp)
                )

                // Read once: the list's content runs again by itself when this changes, ahead of the
                // recomposition that would have taken it away, so it can't read the state directly.
                val list = folders
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        current == null -> VolumeList(volumes) { path = it.path }
                        list == null -> ToolLoading()
                        list.isEmpty() -> KobbloMessage(
                            icon = AppIcons.FolderOpen,
                            title = stringResource(R.string.tools_no_subfolders)
                        )
                        else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                            itemsIndexed(list, key = { _, file -> file.path }) { index, file ->
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = KobbloSpace.screen)
                                        .clip(KobbloShapes.groupItem(index, list.size))
                                        .background(colors.snow)
                                ) {
                                    KobbloRow(
                                        title = file.name,
                                        leading = { Icon(AppIcons.Folder, contentDescription = null, tint = colors.action, modifier = Modifier.size(26.dp)) },
                                        trailing = { KobbloChevron() },
                                        showDivider = index < list.lastIndex,
                                        onClick = { path = file.path }
                                    )
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = { current?.let(onPick) },
                    enabled = current != null && File(current).canWrite(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen, vertical = 8.dp)
                ) {
                    Text(confirmLabel, style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
private fun VolumeList(volumes: List<VolumeInfo>, onOpen: (VolumeInfo) -> Unit) {
    val colors = KobbloDesign.colors
    KobbloGroup {
        volumes.forEachIndexed { index, volume ->
            KobbloRow(
                title = volume.name,
                leading = { Icon(volumeIcon(volume), contentDescription = null, tint = colors.action, modifier = Modifier.size(26.dp)) },
                trailing = { KobbloChevron() },
                showDivider = index < volumes.lastIndex,
                onClick = { onOpen(volume) }
            )
        }
    }
}

internal fun volumeIcon(volume: VolumeInfo) = when {
    volume.isPrimary -> AppIcons.Phone
    volume.isUsb -> AppIcons.Usb
    else -> AppIcons.SdCard
}

private fun displayPath(path: String): String =
    StorageVolumes.snapshot.firstOrNull { it.path == path }?.name ?: File(path).name
