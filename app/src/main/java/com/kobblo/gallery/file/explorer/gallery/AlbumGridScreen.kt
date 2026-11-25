package com.kobblo.gallery.file.explorer.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveScreenHeader
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import com.kobblo.gallery.file.explorer.ui.design.rememberLargeTitleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One album, as the same grid Home uses, with the same selection and menu. */
@Composable
internal fun AlbumGridScreen(
    dest: GalleryDest,
    isCurrent: Boolean,
    state: AppState,
    columns: Int,
    labels: SectionLabels,
    onColumnsChange: (Int) -> Unit,
    onAction: (AppAction) -> Unit,
    onFileClick: (FileItem) -> Unit,
    onFileMenuClick: (FileItem) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val store = remember { GalleryStore.get(context) }
    val index = remember { MediaIndex.get(context) }
    val facts by index.facts.collectAsState()
    val flags by index.flags.collectAsState()
    val details by index.details.collectAsState()
    val pinned by store.pinnedAlbums.collectAsState()
    val hidden by store.hiddenAlbums.collectAsState()
    val photoSorts by store.photoSorts.collectAsState()
    val sortKey = remember(dest) { dest.sortKey() }
    val sort = sortKey?.let { photoSorts[it] } ?: PhotoSort.NEWEST
    var showSort by remember { mutableStateOf(false) }
    var sortChanged by remember { mutableStateOf(false) }
    val largeTitle = rememberLargeTitleState(height = 64.dp)
    val gridState = rememberLazyGridState()
    var showMenu by remember { mutableStateOf(false) }

    val feed by produceState<GalleryFeed?>(null, dest, state.galleryFiles, facts, flags, details, hidden, sort) {
        val files = state.galleryFiles
        value = withContext(Dispatchers.Default) {
            val members = when (dest) {
                is GalleryDest.Folder -> files.filter { it.path.substringBeforeLast('/') == dest.path }
                is GalleryDest.Smart -> smartAlbumFiles(dest.kind, files, facts, flags, System.currentTimeMillis(), hidden = hidden)
                is GalleryDest.Place -> files.filter { !it.isLocked && details[it.id]?.place == dest.place }
                is GalleryDest.Country -> com.kobblo.gallery.file.explorer.gallery.places.PlaceTable.get(context).let { table ->
                    files.filter { file -> !file.isLocked && (details[file.id]?.place ?: -1).let { it >= 0 && table.countryCode(it) == dest.code } }
                }
                is GalleryDest.Tag -> files.filter { !it.isLocked && details[it.id]?.tags?.contains(dest.name) == true }
                is GalleryDest.Person -> com.kobblo.gallery.file.explorer.smart.SmartPeople.mediaIds(context, dest.id).let { ids ->
                    files.filter { !it.isLocked && it.id.toLong() in ids }
                }
                else -> emptyList()
            }
            buildAlbumFeed(members, sort, labels)
        }
    }

    val title = when (dest) {
        is GalleryDest.Folder -> dest.name
        is GalleryDest.Smart -> remember(dest.kind) { smartAlbumTitle(context, dest.kind) }
        is GalleryDest.Place -> dest.name
        is GalleryDest.Country -> dest.name
        is GalleryDest.Tag -> dest.name
        is GalleryDest.Person -> dest.name
        else -> ""
    }
    var naming by remember { mutableStateOf(false) }

    // Select All and swiping through photos keep to this album while it's the one on screen.
    val current = feed
    LaunchedEffect(current, isCurrent) {
        if (isCurrent && current != null) GalleryScope.setVisible(current.files)
    }

    // A new order starts from its beginning, rather than wherever the old one had been scrolled to.
    LaunchedEffect(current) {
        if (sortChanged && current != null) {
            sortChanged = false
            gridState.scrollToItem(0)
        }
    }

    Column(modifier = modifier.fillMaxSize().nestedScroll(largeTitle.connection)) {
        ExpressiveScreenHeader(
            title = title,
            modifier = Modifier.padding(horizontal = KobbloSpace.screen),
            onBack = { GalleryNav.back() },
            backLabel = when (dest) {
                is GalleryDest.Place, is GalleryDest.Country -> stringResource(R.string.gallery_places)
                is GalleryDest.Tag -> stringResource(R.string.gallery_tags)
                is GalleryDest.Person -> stringResource(R.string.smart_people_title)
                else -> stringResource(R.string.gallery_albums)
            },
            rightAction = {
                KobbloIconButton(
                    icon = AppIcons.MoreHoriz,
                    description = stringResource(R.string.gallery_options),
                    onClick = { showMenu = true }
                )
            },
            largeTitle = largeTitle
        )

        when {
            current == null -> Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.TopCenter) {
                com.kobblo.gallery.file.explorer.ui.ExpressiveCircularLoader(size = 36.dp, color = KobbloDesign.colors.action)
            }

            current.files.isEmpty() -> KobbloMessage(
                icon = AppIcons.Albums,
                title = stringResource(R.string.gallery_album_empty),
                body = if (dest is GalleryDest.Folder) stringResource(R.string.gallery_album_empty_body) else null
            )

            else -> GalleryGrid(
                feed = current,
                gridState = gridState,
                columns = columns,
                onColumnsChange = onColumnsChange,
                selected = state.selectedFiles,
                selecting = state.isSelectionMode,
                onFileClick = onFileClick,
                onFileMenuClick = onFileMenuClick,
                onSelectionChange = { onAction(AppAction.SelectAll(it)) },
                bottomPadding = bottomPadding
            )
        }
    }

    if (showMenu) {
        val selectAll: () -> Unit = {
            current?.files?.filterNot { it.isLocked }?.map { it.id }?.let { onAction(AppAction.SelectAll(it)) }
        }
        when (dest) {
            is GalleryDest.Folder -> FolderAlbumMenu(
                album = FolderAlbum(
                    path = dest.path,
                    name = dest.name,
                    count = current?.files?.size ?: 0,
                    covers = emptyList(),
                    latestMillis = 0L,
                    isPinned = dest.path in pinned,
                    isHiddenFromHome = com.kobblo.gallery.file.explorer.fileops.LockedPaths(hidden).covers(dest.path)
                ),
                files = state.galleryFiles,
                onAction = onAction,
                onDismiss = { showMenu = false },
                onSelectAll = selectAll,
                onSort = {
                    showMenu = false
                    showSort = true
                }
            )

            else -> GallerySheet(onDismiss = { showMenu = false }) {
                com.kobblo.gallery.file.explorer.ui.design.KobbloGroup {
                    if (dest is GalleryDest.Person) {
                        com.kobblo.gallery.file.explorer.ui.design.KobbloRow(
                            title = stringResource(R.string.smart_people_rename_title),
                            trailing = {
                                Icon(AppIcons.Pencil, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(22.dp))
                            },
                            onClick = {
                                showMenu = false
                                naming = true
                            },
                            showDivider = true
                        )
                    }
                    com.kobblo.gallery.file.explorer.ui.design.KobbloRow(
                        title = stringResource(R.string.gallery_select_all),
                        trailing = {
                            Icon(AppIcons.CheckCircle, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(22.dp))
                        },
                        onClick = {
                            showMenu = false
                            selectAll()
                        },
                        showDivider = true
                    )
                    com.kobblo.gallery.file.explorer.ui.design.KobbloRow(
                        title = stringResource(R.string.gallery_sort),
                        trailing = {
                            Icon(AppIcons.Sort, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(22.dp))
                        },
                        onClick = {
                            showMenu = false
                            showSort = true
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (naming && dest is GalleryDest.Person) {
        val person by produceState<com.subhashh.lumen.ai.Person?>(null, dest.id) {
            value = withContext(Dispatchers.IO) { com.kobblo.gallery.file.explorer.smart.SmartPeople.all(context).firstOrNull { it.id == dest.id } }
        }
        person?.let {
            com.kobblo.gallery.file.explorer.smart.NamePersonSheet(it) { name ->
                naming = false
                // The title is the album's name, so the album is swapped for one under the new name.
                if (name != null) GalleryNav.replace(GalleryDest.Person(dest.id, name))
            }
        }
    }

    if (showSort) {
        PhotoSortSheet(
            sort = sort,
            onSort = { chosen ->
                showSort = false
                if (chosen != sort && sortKey != null) {
                    sortChanged = true
                    store.setPhotoSort(sortKey, chosen)
                }
            },
            onDismiss = { showSort = false }
        )
    }
}

/** A floating capsule above the tab bar while media moves into an album. */
@Composable
internal fun TransferPill(progress: AlbumTransfer.Progress, modifier: Modifier = Modifier) {
    WorkPill(
        title = stringResource(
            if (progress.moving) R.string.gallery_transfer_moving else R.string.gallery_transfer_copying,
            progress.albumName
        ),
        done = progress.done,
        total = progress.total,
        modifier = modifier,
        onCancel = { AlbumTransfer.cancel() }
    )
}

/** The same capsule for any work on many photos. */
@Composable
internal fun WorkPill(title: String, done: Int, total: Int, modifier: Modifier = Modifier, onCancel: () -> Unit) {
    val colors = KobbloDesign.colors
    val fraction = if (total > 0) done.toFloat() / total else 0f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .kobbloGlass(null, CapsuleShape)
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = KobbloType.callout.copy(fontWeight = FontWeight.Medium),
                color = colors.ink,
                maxLines = 1
            )
            Text(
                text = stringResource(R.string.gallery_transfer_count, done, total),
                style = KobbloType.footnote.copy(fontFeatureSettings = "tnum"),
                color = colors.smoke,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            com.kobblo.gallery.file.explorer.ui.design.StorageMeter(fraction = fraction, height = 3.dp)
        }
        // A square as tall as the text, the same gap from the pill's end as from its top and bottom, is
        // centered on the pill's round end. The button in the middle of it shares that center, so it sits
        // in the curve with an even gap all round, however tall a larger font makes the pill.
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1f, matchHeightConstraintsFirst = true),
            contentAlignment = Alignment.Center
        ) {
            KobbloIconButton(
                icon = AppIcons.Close,
                description = stringResource(R.string.gallery_cancel),
                onClick = onCancel,
                size = 32.dp,
                iconSize = 15.dp,
                tint = colors.smoke,
                background = colors.mist
            )
        }
    }
}
