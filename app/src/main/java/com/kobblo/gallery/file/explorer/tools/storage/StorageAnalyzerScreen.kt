package com.kobblo.gallery.file.explorer.tools.storage

import androidx.compose.foundation.layout.heightIn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.tools.RouteMemory
import com.kobblo.gallery.file.explorer.tools.ToolConfirm
import com.kobblo.gallery.file.explorer.tools.ToolLoading
import com.kobblo.gallery.file.explorer.tools.ToolMenuRow
import com.kobblo.gallery.file.explorer.tools.ToolSheet
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.tools.volumeIcon
import com.kobblo.gallery.file.explorer.ui.design.quietCombinedClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveScreenHeader
import com.kobblo.gallery.file.explorer.ui.expressive.formatFileSize
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.StorageMeter
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.pressedRowFill
import com.kobblo.gallery.file.explorer.ui.design.rowDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

private class SizedEntry(val file: File, val isFolder: Boolean, val bytes: Long?)

/**
 * Where the space went: a volume's folders biggest first, each one drilling down the same way. The
 * Cleaner sorts files by kind; this answers the other question, which is which folder is big. Sizes
 * come from [FolderSizes], so going back is instant and Browse benefits from every walk made here.
 */
@Composable
internal fun StorageAnalyzerScreen(startPath: String?, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val scope = rememberCoroutineScope()
    val volumes by StorageVolumes.volumes.collectAsState()
    val memoryKey = "analyzer:$startPath"
    var path by remember(startPath) { mutableStateOf(RouteMemory.get(memoryKey) ?: startPath) }
    LaunchedEffect(path) { RouteMemory.put(memoryKey, path) }
    val entries = remember { mutableStateListOf<SizedEntry>() }
    var scanning by remember { mutableStateOf(false) }
    var rescan by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<SizedEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<SizedEntry?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val trashPath = remember { FileRepository(context).trashDir.path }

    // With one volume, its list of folders is the natural first screen.
    LaunchedEffect(volumes) {
        if (path == null && volumes.size == 1) path = volumes.first().path
    }
    val current = path
    val volume = current?.let { StorageVolumes.forPath(it) }
    val isVolumeRoot = volume != null && volume.path == current
    val isStart = current == startPath && startPath != null

    fun leave() {
        RouteMemory.put(memoryKey, null)
        Hub.back()
    }

    fun goUp() {
        val at = current ?: return leave()
        when {
            isStart || (isVolumeRoot && volumes.size == 1) -> leave()
            isVolumeRoot -> path = null
            else -> path = File(at).parent
        }
    }

    BackHandler { goUp() }

    LaunchedEffect(current, rescan) {
        entries.clear()
        val at = current ?: return@LaunchedEffect
        scanning = true
        val children = withContext(Dispatchers.IO) {
            File(at).listFiles().orEmpty().map { child ->
                val folder = child.isDirectory
                SizedEntry(child, folder, if (folder) FolderSizes.peek(child.path)?.bytes else child.length())
            }
        }
        fun sorted(list: List<SizedEntry>) = list.sortedByDescending { it.bytes ?: -1L }
        entries.addAll(sorted(children))
        // Folders are measured one at a time, so the list fills in and reorders as sizes arrive.
        children.filter { it.isFolder }
            .sortedByDescending { it.bytes ?: Long.MAX_VALUE }
            .forEach { child ->
                coroutineContext.ensureActive()
                val measured = FolderSizes.measure(child.file.path).bytes
                val index = entries.indexOfFirst { it.file.path == child.file.path }
                if (index >= 0) {
                    val updated = entries.toMutableList()
                    updated[index] = SizedEntry(child.file, true, measured)
                    entries.clear()
                    entries.addAll(sorted(updated))
                }
            }
        scanning = false
    }

    val total = entries.sumOf { it.bytes ?: 0L }
    // On the phone's own storage the gap is what Android keeps out of reach. On a card it's only
    // the file system's own overhead, which isn't worth a row.
    val systemOther = if (isVolumeRoot && volume?.isPrimary == true && !scanning) (volume.usedBytes - total).coerceAtLeast(0) else 0L
    val scale = (maxOf(total, 0L) + systemOther).coerceAtLeast(1L).toFloat()
    val title = when {
        current == null -> stringResource(R.string.tools_storage_analyzer)
        isVolumeRoot -> volume!!.name
        else -> File(current).name
    }
    val backLabel = when {
        current == null || (isVolumeRoot && volumes.size == 1) || isStart -> stringResource(R.string.back)
        isVolumeRoot -> stringResource(R.string.tools_storage_analyzer)
        else -> File(current).parentFile?.let { parent ->
            StorageVolumes.snapshot.firstOrNull { it.path == parent.path }?.name ?: parent.name
        } ?: stringResource(R.string.back)
    }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        ExpressiveScreenHeader(
            title = title,
            subtitle = when {
                current == null -> ""
                scanning -> stringResource(R.string.tools_calculating)
                isVolumeRoot -> stringResource(R.string.tools_analyzer_used, formatFileSize(volume!!.usedBytes), formatFileSize(volume.totalBytes))
                else -> formatFileSize(total)
            },
            modifier = Modifier.padding(horizontal = KobbloSpace.screen),
            onBack = { goUp() },
            backLabel = backLabel
        )

        if (current == null) {
            KobbloGroup {
                volumes.forEachIndexed { index, item ->
                    KobbloRow(
                        title = item.name,
                        subtitle = stringResource(R.string.tools_analyzer_used, formatFileSize(item.usedBytes), formatFileSize(item.totalBytes)),
                        leading = { Icon(volumeIcon(item), contentDescription = null, tint = colors.action, modifier = Modifier.size(26.dp)) },
                        trailing = { KobbloChevron() },
                        below = {
                            StorageMeter(
                                fraction = item.usedBytes.toFloat() / item.totalBytes.coerceAtLeast(1).toFloat(),
                                height = 4.dp,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                            )
                        },
                        showDivider = index < volumes.lastIndex,
                        onClick = { path = item.path }
                    )
                }
            }
            return@Column
        }

        if (entries.isEmpty()) {
            if (scanning) ToolLoading() else KobbloMessage(icon = AppIcons.FolderOpen, title = stringResource(R.string.tools_analyzer_empty))
            return@Column
        }

        // A list keeps its place by key, so as rows reorder it would follow whichever row was on top.
        LaunchedEffect(entries.firstOrNull()?.file?.path, scanning) {
            if (scanning && !listState.isScrollInProgress && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = navBottom + 16.dp)
        ) {
            val shown = entries.toList()
            val rowCount = shown.size + if (systemOther > 0) 1 else 0
            itemsIndexed(shown, key = { _, entry -> entry.file.path }) { index, entry ->
                // The trash is reached through its own screen, and can't be moved into itself.
                val isTrash = entry.file.path == trashPath
                AnalyzerRow(
                    name = if (isTrash) stringResource(R.string.recently_deleted) else entry.file.name,
                    isFolder = entry.isFolder,
                    bytes = entry.bytes,
                    fraction = (entry.bytes ?: 0L) / scale,
                    index = index,
                    count = rowCount,
                    modifier = Modifier.animateItem(),
                    onClick = {
                        when {
                            isTrash -> {
                                RouteMemory.put(memoryKey, null)
                                Hub.close()
                                onAction(AppAction.SetTrashScreenVisible(true))
                            }
                            entry.isFolder -> path = entry.file.path
                            else -> menuFor = entry
                        }
                    },
                    onHold = if (isTrash) null else ({ menuFor = entry })
                )
            }
            if (systemOther > 0) {
                item(key = "system_other") {
                    AnalyzerRow(
                        name = stringResource(R.string.tools_analyzer_system),
                        isFolder = false,
                        bytes = systemOther,
                        fraction = systemOther / scale,
                        index = rowCount - 1,
                        count = rowCount,
                        modifier = Modifier,
                        onClick = null,
                        onHold = null
                    )
                }
            }
            if (systemOther > 0) {
                item(key = "system_note") { KobbloGroupFooter(stringResource(R.string.tools_analyzer_system_note)) }
            }
        }
    }

    menuFor?.let { entry ->
        ToolSheet(title = entry.file.name, onDismiss = { menuFor = null }) {
            KobbloGroup {
                ToolMenuRow(stringResource(R.string.tools_analyzer_show_in_browse), AppIcons.FolderOpen, showDivider = true) {
                    menuFor = null
                    val folder = if (entry.isFolder) entry.file else entry.file.parentFile
                    folder?.let {
                        RouteMemory.put(memoryKey, null)
                        Hub.close()
                        onAction(AppAction.SetLocation(it.path))
                    }
                }
                ToolMenuRow(stringResource(R.string.tools_inspect), AppIcons.Info) {
                    menuFor = null
                    Hub.open(ToolsRoute.Inspect(entry.file.path))
                }
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                ToolMenuRow(stringResource(R.string.tools_analyzer_move_to_trash), AppIcons.Trash, tint = colors.danger) {
                    menuFor = null
                    confirmDelete = entry
                }
            }
        }
    }

    confirmDelete?.let { entry ->
        val movedMessage = stringResource(R.string.tools_analyzer_moved, entry.file.name)
        ToolConfirm(
            title = stringResource(R.string.tools_analyzer_delete_title, entry.file.name),
            message = stringResource(R.string.tools_analyzer_delete_body),
            confirmLabel = stringResource(R.string.tools_analyzer_move_to_trash),
            danger = true,
            onConfirm = {
                confirmDelete = null
                // Deleting something locked takes its password, and this screen has nowhere to ask.
                if (com.kobblo.gallery.file.explorer.fileops.Locks.covers(context, entry.file.path)) {
                    onAction(AppAction.ShowToast(context.getString(R.string.safety_lock_delete_elsewhere)))
                    return@ToolConfirm
                }
                scope.launch {
                    val report = FileRepository(context).moveToTrash(listOf(entry.file.path))
                    FolderSizes.invalidate(entry.file.path)
                    StorageVolumes.refresh(context)
                    onAction(AppAction.ShowToast(if (report.ok) movedMessage else com.kobblo.gallery.file.explorer.fileops.FileOpText.deleteFailed(context, 0, report.failed)))
                    onAction(AppAction.Reload)
                    rescan++
                }
            },
            onDismiss = { confirmDelete = null }
        )
    }
}

@Composable
private fun AnalyzerRow(
    name: String,
    isFolder: Boolean,
    bytes: Long?,
    fraction: Float,
    index: Int,
    count: Int,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    onHold: (() -> Unit)?
) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val source = remember { MutableInteractionSource() }
    val fill = pressedRowFill(source)
    val tap = if (onClick != null) {
        Modifier.quietCombinedClickable(
            source = source,
            onClick = onClick,
            onLongClick = onHold?.let {
                {
                    haptics.tap()
                    it()
                }
            }
        )
    } else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen)
            .clip(KobbloShapes.groupItem(index, count))
            .background(colors.snow)
            .background(fill)
            .then(tap)
            .rowDivider(index < count - 1, KobbloSpace.inset + com.kobblo.gallery.file.explorer.ui.design.KobbloSize.badge + KobbloSpace.leadingGap, colors.hairline)
            .heightIn(min = com.kobblo.gallery.file.explorer.ui.design.KobbloSize.row)
            .padding(horizontal = KobbloSpace.inset, vertical = KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isFolder) AppIcons.Folder else if (onClick == null) AppIcons.Phone else AppIcons.File,
            contentDescription = null,
            tint = if (isFolder) colors.action else colors.smoke,
            // A glyph in the middle of a badge's room, so the text starts where a badge row's does.
            modifier = Modifier.size(com.kobblo.gallery.file.explorer.ui.design.KobbloSize.badge).padding(3.dp)
        )
        Spacer(Modifier.width(KobbloSpace.leadingGap))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = KobbloType.body,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = bytes?.let(::formatFileSize) ?: "…",
                    style = KobbloType.callout,
                    color = colors.smoke,
                    maxLines = 1
                )
            }
            StorageMeter(
                fraction = fraction.coerceIn(0f, 1f),
                height = 4.dp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (isFolder) {
            Spacer(Modifier.width(8.dp))
            KobbloChevron()
        }
    }
}
