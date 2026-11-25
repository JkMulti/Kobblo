package com.kobblo.gallery.file.explorer.transfer.pick

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.localsend.NearbyPeer
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.explorer.search.SearchEngine
import com.kobblo.gallery.file.explorer.explorer.search.SearchFilters
import com.kobblo.gallery.file.explorer.transfer.core.FolderWalker
import com.kobblo.gallery.file.explorer.transfer.send.Notes
import com.kobblo.gallery.file.explorer.transfer.send.TransferFiles
import com.kobblo.gallery.file.explorer.transfer.send.TransferFormat
import com.kobblo.gallery.file.explorer.transfer.ui.NoteComposerSheet
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveFileThumbnail
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.SelectionCircle
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.hub.HubScreen
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSearchField
import com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.ambientShadow
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** One chosen thing, with what it adds up to. A folder's count takes a moment to work out. */
private data class Picked(val item: FileItem, val files: Int, val bytes: Long, val counting: Boolean = false)

@Stable
private class PickerSelection(private val context: Context, initial: List<FileItem>) {
    val picked = mutableStateMapOf<String, Picked>().apply {
        initial.forEach { put(it.path, Picked(it, 1, it.sizeBytes, counting = isFolder(it))) }
    }
    val count by derivedStateOf { picked.values.sumOf { it.files } }
    val bytes by derivedStateOf { picked.values.sumOf { it.bytes } }
    val isCounting by derivedStateOf { picked.values.any { it.counting } }

    fun isPicked(path: String) = picked.containsKey(path)

    fun isFolder(item: FileItem) = item.type == "folder" || item.kind == "folder"

    /** Adds or removes [item], and returns whether it's now in. */
    fun toggle(item: FileItem): Boolean {
        if (picked.remove(item.path) != null) return false
        picked[item.path] = Picked(item, if (isFolder(item)) 0 else 1, item.sizeBytes, counting = isFolder(item))
        return true
    }

    suspend fun countFolder(item: FileItem) {
        val (files, bytes) = withContext(Dispatchers.IO) {
            val locks = com.kobblo.gallery.file.explorer.fileops.Locks.forLists(context)
            val entries = FolderWalker.walk(File(item.path), skip = { locks.covers(it.path) })
            entries.size to entries.sumOf { it.file.length() }
        }
        picked[item.path]?.let { picked[item.path] = it.copy(files = files, bytes = bytes, counting = false) }
    }
}

/**
 * Choosing what to send. Everything is one tap from the Send tab: a tab for each kind of thing, a tap
 * to add, and a running total at the bottom that is also the button to move on.
 */
@Composable
fun PickerScreen(sendTo: List<NearbyPeer>, hasAccess: Boolean = true, onGrantAccess: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selection = remember {
        PickerSelection(context, NearbyShareRepository.stagedFiles.value)
    }
    val hadChosen = remember { NearbyShareRepository.stagedFiles.value.isNotEmpty() }
    LaunchedEffect(Unit) {
        selection.picked.values.filter { it.counting }.forEach { scope.launch { selection.countFolder(it.item) } }
    }
    var tab by rememberSaveable { mutableStateOf(0) }
    // What people mostly send comes first, in the order they mostly send it.
    val tabs = listOf(
        stringResource(R.string.transfer_tab_photos),
        stringResource(R.string.transfer_tab_videos),
        stringResource(R.string.transfer_tab_apps),
        stringResource(R.string.transfer_tab_music),
        stringResource(R.string.transfer_tab_files)
    )
    var writingNote by remember { mutableStateOf(false) }
    if (writingNote) {
        NoteComposerSheet(
            onAdd = { text ->
                writingNote = false
                Notes.write(context, text)?.let { selection.toggle(it) }
            },
            onDismiss = { writingNote = false }
        )
    }
    val toggle: (FileItem) -> Unit = { item ->
        if (selection.toggle(item) && selection.isFolder(item)) {
            scope.launch { selection.countFolder(item) }
        }
    }

    HubScreen(
        title = stringResource(R.string.transfer_picker_title),
        backLabel = stringResource(R.string.transfer_send_tab),
        trailingIcon = AppIcons.FileText,
        trailingDescription = stringResource(R.string.transfer_write_note),
        onTrailingClick = { writingNote = true }
    ) {
        KobbloSegmentedControl(
            options = tabs,
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = KobbloSpace.screen, vertical = 8.dp)
        )
        Box(modifier = Modifier.weight(1f)) {
            // Each tab keeps its place only while selected; re-reading a page is cheap.
            when {
                // Without access these would all read as an empty phone. Apps need no access, and
                // Files still offers other apps' pickers.
                !hasAccess && tab != 2 && tab != 4 -> KobbloMessage(
                    icon = AppIcons.FolderShared,
                    title = stringResource(R.string.permission_dialog_title),
                    body = stringResource(R.string.permission_dialog_message),
                    modifier = Modifier.padding(top = 64.dp),
                    action = {
                        Button(
                            onClick = onGrantAccess,
                            colors = ButtonDefaults.buttonColors(containerColor = KobbloDesign.colors.action, contentColor = KobbloDesign.colors.onAction)
                        ) {
                            Text(stringResource(R.string.permission_grant_access), fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
                tab == 0 -> MediaGridTab(MediaKind.Photos, selection, toggle)
                tab == 1 -> MediaGridTab(MediaKind.Videos, selection, toggle)
                tab == 2 -> AppsTab(selection, toggle)
                tab == 3 -> MusicTab(selection, toggle)
                else -> FilesTab(selection, toggle)
            }
            ConfirmBar(
                count = selection.count,
                bytes = selection.bytes,
                sendTo = sendTo,
                counting = selection.isCounting,
                // Taking everything back out of an earlier choice still needs a way to say so.
                showWhenEmpty = hadChosen,
                modifier = Modifier.align(Alignment.BottomCenter),
                onConfirm = {
                    NearbyShareRepository.setStagedFiles(selection.picked.values.map { it.item })
                    Hub.back()
                    if (sendTo.isNotEmpty()) NearbyShareRepository.sendStagedFiles(context, sendTo)
                }
            )
        }
    }
}

private val bottomClearance = 96.dp
private const val MAX_SHOWN = 500

@Composable
private fun listBottomPadding() = bottomClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** The running total, which is also the way forward. The count rolls with each tap. */
@Composable
private fun ConfirmBar(
    count: Int,
    bytes: Long,
    sendTo: List<NearbyPeer>,
    counting: Boolean,
    showWhenEmpty: Boolean,
    modifier: Modifier,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val reduced = ExpressiveMotion.isReducedMotion()
    AnimatedVisibility(
        visible = count > 0 || counting || showWhenEmpty,
        modifier = modifier,
        enter = fadeIn(KobbloMotion.fadeIn()) + slideInVertically(KobbloMotion.smooth()) { it / 2 },
        exit = fadeOut(KobbloMotion.fadeOut()) + slideOutVertically(KobbloMotion.smooth()) { it / 2 }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KobbloSpace.screen)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp)
                .ambientShadow(CapsuleShape, colors.ambient)
                .clip(CapsuleShape)
                .background(colors.action)
                .clickable(enabled = !counting, onClick = onConfirm)
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            val label = when {
                counting -> context.getString(R.string.transfer_picker_counting)
                sendTo.size == 1 -> context.getString(R.string.transfer_send_to_peer, sendTo.single().alias)
                else -> context.getString(R.string.transfer_picker_add)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onAction,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                AnimatedContent(
                    targetState = count,
                    transitionSpec = {
                        if (reduced) {
                            fadeIn(KobbloMotion.fadeIn()) togetherWith fadeOut(KobbloMotion.fadeOut())
                        } else {
                            val up = targetState > initialState
                            (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith
                                (slideOutVertically { if (up) -it else it } + fadeOut())
                        }
                    },
                    label = "picker_count"
                ) { value ->
                    Text(
                        text = context.resources.getQuantityString(R.plurals.transfer_item_count, value, value),
                        style = KobbloType.callout.copy(fontWeight = FontWeight.Medium),
                        color = colors.onAction
                    )
                }
                Text(
                    text = " · ${TransferFormat.size(context, bytes)}",
                    style = KobbloType.callout,
                    color = colors.onAction.copy(alpha = 0.85f)
                )
            }
        }
    }
}

// ── Apps ────────────────────────────────────────────────────────────────────

@Composable
private fun AppsTab(selection: PickerSelection, toggle: (FileItem) -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<PickApp>?>(null) { value = AppSource.load(context) }
    var query by rememberSaveable { mutableStateOf("") }
    val all = apps
    if (all == null) {
        CenteredSpinner()
        return
    }
    val shown = remember(all, query) {
        if (query.isBlank()) all else all.filter { it.label.contains(query, ignoreCase = true) }
    }
    if (all.isEmpty()) {
        KobbloMessage(
            AppIcons.Apps,
            stringResource(R.string.transfer_no_apps_title),
            stringResource(R.string.transfer_no_apps_body),
            modifier = Modifier.padding(top = 64.dp)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = listBottomPadding())
    ) {
        item(key = "search") {
            KobbloSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.transfer_search_apps),
                modifier = Modifier.padding(horizontal = KobbloSpace.screen, vertical = 8.dp)
            )
        }
        itemsIndexed(shown, key = { _, app -> app.packageName }) { index, app ->
            val item = remember(app) { TransferFiles.fromApp(context, app.packageName, app.label, app.sourcePath, app.size) }
            val picked = selection.isPicked(item.path)
            val size = TransferFormat.size(context, app.size)
            KobbloRow(
                title = app.label,
                subtitle = if (app.parts > 1) stringResource(R.string.transfer_app_split, size, app.parts) else size,
                modifier = Modifier
                    .padding(horizontal = KobbloSpace.screen)
                    .clip(KobbloShapes.groupItem(index, shown.size))
                    .background(KobbloDesign.colors.snow),
                leading = { AppIcon(app) },
                trailing = { SelectionCircle(picked, Modifier.size(24.dp), onSurface = true) },
                showDivider = index < shown.lastIndex,
                onClick = { toggle(item) }
            )
        }
    }
}

@Composable
private fun AppIcon(app: PickApp) {
    val context = LocalContext.current
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.packageName) {
        value = AppSource.icon(context, app, 96)
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(30.dp).clip(KobbloShapes.tile))
    } else {
        Icon(AppIcons.Android, contentDescription = null, tint = KobbloDesign.colors.pewter, modifier = Modifier.size(24.dp))
    }
}

// ── Photos and videos ───────────────────────────────────────────────────────

@Composable
private fun MediaGridTab(kind: MediaKind, selection: PickerSelection, toggle: (FileItem) -> Unit) {
    val context = LocalContext.current
    val pager = remember(kind) { MediaPager(context, kind) }
    val grid = rememberLazyGridState()
    LaunchedEffect(pager) {
        if (pager.items.isEmpty()) pager.loadMore()
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= pager.items.size - 40) pager.loadMore() }
    }
    if (pager.items.isEmpty()) {
        if (pager.endReached) {
            KobbloMessage(
                if (kind == MediaKind.Photos) AppIcons.Photo else AppIcons.Video,
                stringResource(if (kind == MediaKind.Photos) R.string.transfer_no_photos else R.string.transfer_no_videos),
                modifier = Modifier.padding(top = 64.dp)
            )
        } else {
            CenteredSpinner()
        }
        return
    }
    fun itemFor(media: PickMedia) = TransferFiles.fromFile(context, File(media.path), media.name).copy(sizeBytes = media.size)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        state = grid,
        modifier = Modifier
            .fillMaxSize()
            .dragToPick(grid, { pager.items }, { selection.isPicked(it.path) }) { media, on ->
                if (selection.isPicked(media.path) != on) toggle(itemFor(media))
            },
        contentPadding = PaddingValues(bottom = listBottomPadding()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(pager.items, key = { _, media -> media.id }) { _, media ->
            val picked = selection.isPicked(media.path)
            MediaTile(media, picked, kind == MediaKind.Videos) { toggle(itemFor(media)) }
        }
        if (!pager.endReached) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    com.kobblo.gallery.file.explorer.ui.design.KobbloLoader(color = KobbloDesign.colors.action, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/**
 * Choosing a run of photos by dragging, as in the photo grid. A drag that starts sideways picks everything
 * it passes, and one that starts up or down still scrolls. Starting on one that's picked takes them out
 * instead. Going back over what was just passed puts it as it was.
 */
private fun Modifier.dragToPick(
    grid: androidx.compose.foundation.lazy.grid.LazyGridState,
    items: () -> List<PickMedia>,
    isPicked: (PickMedia) -> Boolean,
    set: (PickMedia, Boolean) -> Unit
): Modifier = pointerInput(Unit) {
    fun indexAt(position: Offset): Int =
        grid.layoutInfo.visibleItemsInfo.firstOrNull {
            position.x >= it.offset.x && position.x < it.offset.x + it.size.width &&
                position.y >= it.offset.y && position.y < it.offset.y + it.size.height
        }?.index?.takeIf { it < items().size } ?: -1

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        var moved = Offset.Zero
        var anchor = -1
        var to = true
        var low = -1
        var high = -1
        var before: Set<String> = emptySet()
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (anchor < 0) {
                moved += change.positionChange()
                if (abs(moved.y) > slop && abs(moved.y) > abs(moved.x)) break
                if (abs(moved.x) <= slop) continue
                anchor = indexAt(down.position)
                if (anchor < 0) break
                val all = items()
                before = all.filter(isPicked).mapTo(HashSet()) { it.path }
                to = !isPicked(all[anchor])
                low = anchor
                high = anchor
                set(all[anchor], to)
            }
            change.consume()
            val at = indexAt(change.position)
            if (at < 0) continue
            val all = items()
            low = minOf(low, at)
            high = maxOf(high, at)
            val from = minOf(anchor, at)
            val until = maxOf(anchor, at)
            for (index in low..high) {
                set(all[index], if (index in from..until) to else all[index].path in before)
            }
        }
    }
}

@Composable
private fun MediaTile(media: PickMedia, picked: Boolean, isVideo: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val source = remember { MutableInteractionSource() }
    // Not every format decodes here, and a blank square reads as broken rather than as a real file.
    var failed by remember(media.path) { mutableStateOf(false) }
    val request = remember(media.path) {
        ImageRequest.Builder(context)
            .data(File(media.path))
            .size(240)
            .apply { if (isVideo) videoFrameMillis(1000L) }
            .crossfade(false)
            .listener(onError = { _, _ -> failed = true })
            .build()
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .pressScale(pressedScale = 0.96f, interactionSource = source)
            .background(KobbloDesign.colors.mist)
            .quietClickable(source = source) {
                haptics.tap()
                onClick()
            }
    ) {
        if (failed) {
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    if (isVideo) AppIcons.Video else AppIcons.Photo,
                    contentDescription = null,
                    tint = KobbloDesign.colors.smoke,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    media.name,
                    style = KobbloType.tab,
                    color = KobbloDesign.colors.smoke,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            AsyncImage(
                model = request,
                contentDescription = media.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (picked) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
        }
        if (isVideo && media.durationMs > 0) {
            Text(
                text = android.text.format.DateUtils.formatElapsedTime(media.durationMs / 1000),
                style = KobbloType.caption,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(CapsuleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
        SelectionCircle(picked, Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp))
    }
}

// ── Music ───────────────────────────────────────────────────────────────────

@Composable
private fun MusicTab(selection: PickerSelection, toggle: (FileItem) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val pager = remember(query) { MediaPager(context, MediaKind.Music, query) }
    val list = rememberLazyListState()
    LaunchedEffect(pager) {
        if (pager.items.isEmpty()) pager.loadMore()
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= pager.items.size - 30) pager.loadMore() }
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = listBottomPadding())
    ) {
        item(key = "search") {
            KobbloSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.transfer_search_music),
                modifier = Modifier.padding(horizontal = KobbloSpace.screen, vertical = 8.dp)
            )
        }
        if (pager.items.isEmpty() && pager.endReached) {
            item(key = "empty") {
                KobbloMessage(AppIcons.Music, stringResource(R.string.transfer_no_music), modifier = Modifier.padding(top = 48.dp))
            }
        }
        itemsIndexed(pager.items, key = { _, media -> media.id }) { index, media ->
            val picked = selection.isPicked(media.path)
            val size = TransferFormat.size(context, media.size)
            KobbloRow(
                title = media.title?.takeIf { it.isNotBlank() } ?: media.name,
                subtitle = listOfNotNull(media.artist?.takeIf { it.isNotBlank() && it != "<unknown>" }, size).joinToString(" · "),
                modifier = Modifier
                    .padding(horizontal = KobbloSpace.screen)
                    .clip(KobbloShapes.groupItem(index, pager.items.size))
                    .background(KobbloDesign.colors.snow),
                leading = { Icon(AppIcons.Music, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(24.dp)) },
                trailing = { SelectionCircle(picked, Modifier.size(24.dp), onSurface = true) },
                showDivider = index < pager.items.lastIndex,
                onClick = {
                    toggle(TransferFiles.fromFile(context, File(media.path), media.name).copy(sizeBytes = media.size))
                }
            )
        }
    }
}

// ── Files ───────────────────────────────────────────────────────────────────

@Composable
private fun FilesTab(selection: PickerSelection, toggle: (FileItem) -> Unit) {
    val context = LocalContext.current
    val root = remember { Environment.getExternalStorageDirectory().path }
    var path by rememberSaveable { mutableStateOf(root) }
    val repository = remember { FileRepository(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<FileItem>?>(null) }
    // The system's index answers at once, so nobody waits for a walk over a whole phone's storage.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            found = null
            return@LaunchedEffect
        }
        val locks = com.kobblo.gallery.file.explorer.fileops.Locks.forLists(context)
        SearchEngine.run(
            context = context,
            repository = repository,
            query = query,
            filters = SearchFilters(everywhere = true),
            location = root,
            showHidden = false,
            current = found.orEmpty()
        ) { snapshot -> found = snapshot.files.filterNot { locks.covers(it.path) }.take(MAX_SHOWN) }
    }
    val recent by produceState<List<FileItem>>(emptyList(), path) {
        if (path == root) value = RecentFiles.load(context)
    }
    val listing by produceState<List<FileItem>?>(null, path) {
        value = null
        value = try {
            val locks = com.kobblo.gallery.file.explorer.fileops.Locks.forLists(context)
            repository.getFiles(path)
                .filter { !it.name.startsWith(".") && !locks.covers(it.path) }
                .sortedWith(compareBy({ it.type != "folder" }, { it.name.lowercase() }))
        } catch (e: Exception) {
            emptyList()
        }
    }
    BackHandler(enabled = path != root) {
        path = File(path).parent ?: root
    }
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val uris = buildList {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let(::add) }
            if (isEmpty()) data.data?.let(::add)
        }
        uris.forEach { uri ->
            // Keeps access for as long as the transfer takes, even if the app is closed in the meantime.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            toggle(itemFromUri(context, uri))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = listBottomPadding())
    ) {
        item(key = "search") {
            KobbloSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.transfer_search_files),
                modifier = Modifier.padding(horizontal = KobbloSpace.screen, vertical = 8.dp)
            )
        }
        val hits = found
        if (hits != null) {
            if (hits.isEmpty()) {
                item(key = "no-hits") {
                    KobbloMessage(AppIcons.Search, stringResource(R.string.transfer_no_matches), modifier = Modifier.padding(top = 48.dp))
                }
            }
            itemsIndexed(hits, key = { _, file -> "hit-${file.path}" }) { index, file ->
                FileRow(
                    file = file,
                    picked = selection.isPicked(file.path),
                    index = index,
                    count = hits.size,
                    onToggle = { toggle(file) },
                    onOpen = {
                        query = ""
                        path = file.path
                    }
                )
            }
            return@LazyColumn
        }
        if (path == root && recent.isNotEmpty()) {
            item(key = "recent-title") {
                com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader(title = stringResource(R.string.transfer_recent_files))
            }
            itemsIndexed(recent, key = { _, file -> "recent-${file.path}" }) { index, file ->
                FileRow(
                    file = file,
                    picked = selection.isPicked(file.path),
                    index = index,
                    count = recent.size,
                    onToggle = { toggle(file) },
                    onOpen = {}
                )
            }
            item(key = "storage-title") {
                com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader(title = stringResource(R.string.transfer_files_storage))
            }
        }
        item(key = "location") {
            KobbloRow(
                title = if (path == root) stringResource(R.string.transfer_files_storage) else File(path).name,
                subtitle = if (path == root) null else stringResource(R.string.transfer_files_up),
                modifier = Modifier
                    .padding(horizontal = KobbloSpace.screen)
                    .padding(top = 8.dp, bottom = 8.dp)
                    .clip(KobbloShapes.group)
                    .background(KobbloDesign.colors.snow),
                leading = {
                    Icon(
                        if (path == root) AppIcons.Drive else AppIcons.Back,
                        contentDescription = null,
                        tint = KobbloDesign.colors.action,
                        modifier = Modifier.size(24.dp)
                    )
                },
                trailing = if (path == root) {
                    {
                        Text(
                            stringResource(R.string.transfer_files_other_apps),
                            style = KobbloType.callout,
                            color = KobbloDesign.colors.action,
                            modifier = Modifier.clickable {
                                systemPicker.launch(
                                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                        type = "*/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                    }
                                )
                            }
                        )
                    }
                } else null,
                onClick = if (path != root) ({ path = File(path).parent ?: root }) else null
            )
        }
        val rows = listing
        when {
            rows == null -> item(key = "spinner") { CenteredSpinner(Modifier.padding(top = 48.dp)) }
            rows.isEmpty() -> item(key = "empty") {
                KobbloMessage(AppIcons.Folder, stringResource(R.string.transfer_folder_empty), modifier = Modifier.padding(top = 48.dp))
            }
            else -> itemsIndexed(rows, key = { _, file -> file.path }) { index, file ->
                FileRow(
                    file = file,
                    picked = selection.isPicked(file.path),
                    index = index,
                    count = rows.size,
                    onToggle = { toggle(file) },
                    onOpen = { path = file.path }
                )
            }
        }
    }
}

@Composable
private fun FileRow(file: FileItem, picked: Boolean, index: Int, count: Int, onToggle: () -> Unit, onOpen: () -> Unit) {
    val isFolder = file.type == "folder"
    val colors = KobbloDesign.colors
    KobbloRow(
        title = file.name,
        subtitle = if (isFolder) file.changed.ifBlank { null } else "${file.size} · ${file.changed}",
        modifier = Modifier
            .padding(horizontal = KobbloSpace.screen)
            .clip(KobbloShapes.groupItem(index, count))
            .background(colors.snow),
        leading = { key(file.path) { ExpressiveFileThumbnail(file = file, isSelected = false, size = 30.dp) } },
        trailing = {
            // In a folder's row, the mark picks the folder and the rest of the row goes into it.
            // The mark is small, and the rest of the row goes into the folder, so its target is much
            // bigger than it looks. Missing it by a little mustn't open a folder of a thousand files.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .quietClickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                SelectionCircle(picked, Modifier.size(24.dp))
            }
            if (isFolder) {
                Spacer(Modifier.width(8.dp))
                KobbloChevron()
            }
        },
        showDivider = index < count - 1,
        onClick = if (isFolder) onOpen else onToggle
    )
}

private fun itemFromUri(context: Context, uri: Uri): FileItem {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    var size = 0L
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn) ?: name
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
    }
    return TransferFiles.fromUri(context, uri, name, size)
}

@Composable
private fun CenteredSpinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
        com.kobblo.gallery.file.explorer.ui.design.KobbloLoader(color = KobbloDesign.colors.action, modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
    }
}
