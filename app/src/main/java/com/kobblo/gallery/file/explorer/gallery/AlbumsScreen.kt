package com.kobblo.gallery.file.explorer.gallery

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.quietCombinedClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveScreenHeader
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.rememberLargeTitleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val AlbumTileMinWidth = 150.dp

private class AlbumsUi(val folders: List<FolderAlbum>, val smart: List<SmartAlbum>, val places: Int, val tags: Int)

/**
 * Every folder of photos as an album, with the smart albums under them. Folder albums are real
 * folders, so they match what Browse shows.
 */
@Composable
internal fun AlbumsScreen(
    state: AppState,
    onAction: (AppAction) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { GalleryStore.get(context) }
    val index = remember { MediaIndex.get(context) }
    val sort by store.albumSort.collectAsState()
    val pinned by store.pinnedAlbums.collectAsState()
    val created by store.createdAlbums.collectAsState()
    val hidden by store.hiddenAlbums.collectAsState()
    val covers by store.albumCovers.collectAsState()
    val facts by index.facts.collectAsState()
    val flags by index.flags.collectAsState()
    val details by index.details.collectAsState()
    val largeTitle = rememberLargeTitleState(height = 100.dp)
    val listState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var showOptions by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<FolderAlbum?>(null) }
    val rootName = stringResource(R.string.gallery_album_root)
    var peopleVersion by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val people = com.kobblo.gallery.file.explorer.smart.rememberPeople(peopleVersion)
    var namingPerson by remember { mutableStateOf<com.subhashh.lumen.ai.Person?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { store.pruneCreatedAlbums { File(it).isDirectory } }
    }

    val ui by produceState<AlbumsUi?>(null, state.galleryFiles, facts, flags, details, sort, pinned, created, hidden, covers) {
        val files = state.galleryFiles
        value = withContext(Dispatchers.Default) {
            val places = HashSet<Int>()
            val tags = HashSet<String>()
            if (details.isNotEmpty()) {
                for (file in files) {
                    if (file.isLocked) continue
                    val found = details[file.id] ?: continue
                    if (found.place >= 0) places.add(found.place)
                    tags.addAll(found.tags)
                }
            }
            AlbumsUi(
                folders = buildFolderAlbums(files, pinned, created, sort, rootName, hidden, covers),
                smart = buildSmartAlbums(files, facts, flags, System.currentTimeMillis(), hidden = hidden),
                places = places.size,
                tags = tags.size
            )
        }
    }

    Column(modifier = modifier.fillMaxSize().nestedScroll(largeTitle.connection)) {
        ExpressiveScreenHeader(
            title = stringResource(R.string.gallery_albums),
            modifier = Modifier.padding(horizontal = KobbloSpace.screen),
            onBack = { GalleryNav.back() },
            backLabel = stringResource(R.string.nav_home),
            rightAction = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KobbloIconButton(
                        icon = AppIcons.Add,
                        description = stringResource(R.string.gallery_album_new),
                        onClick = { showCreate = true }
                    )
                    KobbloIconButton(
                        icon = AppIcons.MoreHoriz,
                        description = stringResource(R.string.gallery_options),
                        onClick = { showOptions = true }
                    )
                }
            },
            showSearch = true,
            query = query,
            onQueryChange = { query = it },
            searchPlaceholder = stringResource(R.string.gallery_albums_search),
            largeTitle = largeTitle
        )

        val current = ui
        // Until the first load lands, an empty list only means nothing has been read yet.
        if (current == null || (state.isLoading && state.galleryFiles.isEmpty())) {
            Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.TopCenter) {
                com.kobblo.gallery.file.explorer.ui.ExpressiveCircularLoader(size = 36.dp, color = KobbloDesign.colors.action)
            }
        } else {
            val needle = query.trim().lowercase(Locale.getDefault())
            val folders = if (needle.isEmpty()) current.folders else current.folders.filter { it.name.lowercase(Locale.getDefault()).contains(needle) }
            val smartTitles = current.smart.associateWith { smartAlbumTitle(context, it.kind) }
            val smart = if (needle.isEmpty()) current.smart else current.smart.filter { smartTitles.getValue(it).lowercase(Locale.getDefault()).contains(needle) }
            val placesTitle = stringResource(R.string.gallery_places)
            val tagsTitle = stringResource(R.string.gallery_tags)
            val showPlaces = current.places > 0 && placesTitle.lowercase(Locale.getDefault()).contains(needle)
            val showTags = current.tags > 0 && tagsTitle.lowercase(Locale.getDefault()).contains(needle)

            if (folders.isEmpty() && smart.isEmpty() && !showPlaces && !showTags) {
                KobbloMessage(icon = AppIcons.Albums, title = stringResource(R.string.gallery_albums_empty))
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val perRow = ((maxWidth - KobbloSpace.screen * 2) / AlbumTileMinWidth).toInt().coerceAtLeast(2)
                    val coverPx = with(LocalDensity.current) { ((maxWidth - KobbloSpace.screen * 2) / perRow).roundToPx() }
                    val rows = remember(folders, perRow) { folders.chunked(perRow) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding)
                    ) {
                        items(rows, key = { row -> row.first().path }) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = KobbloSpace.screen, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                row.forEach { album ->
                                    AlbumTile(
                                        album = album,
                                        coverPx = coverPx,
                                        onClick = { GalleryNav.open(GalleryDest.Folder(album.path, album.name)) },
                                        onLongClick = { actionsFor = album },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        if (needle.isEmpty() && !people.isNullOrEmpty()) {
                            item(key = "people") {
                                com.kobblo.gallery.file.explorer.smart.PeopleRow(people) { namingPerson = it }
                            }
                        }
                        if (smart.isNotEmpty() || showPlaces || showTags) {
                            item(key = "smart_header") {
                                KobbloGroupHeader(title = stringResource(R.string.gallery_albums_smart))
                            }
                            item(key = "smart") {
                                KobbloGroup {
                                    if (showPlaces) {
                                        CountRow(placesTitle, AppIcons.Location, KobbloTint.Green, current.places, smart.isNotEmpty() || showTags) {
                                            GalleryNav.open(GalleryDest.Places)
                                        }
                                    }
                                    if (showTags) {
                                        CountRow(tagsTitle, AppIcons.Bookmark, KobbloTint.Purple, current.tags, smart.isNotEmpty()) {
                                            GalleryNav.open(GalleryDest.Tags)
                                        }
                                    }
                                    smart.forEachIndexed { position, album ->
                                        val look = smartAlbumLook(album.kind)
                                        CountRow(smartTitles.getValue(album), look.first, look.second, album.count, position < smart.lastIndex) {
                                            GalleryNav.open(GalleryDest.Smart(album.kind))
                                        }
                                    }
                                }
                            }
                        }
                        if (needle.isEmpty() && state.isRecycleBinEnabled) {
                            item(key = "bin") {
                                Spacer(Modifier.height(KobbloSpace.groupGap))
                                KobbloGroup {
                                    KobbloRow(
                                        title = stringResource(R.string.gallery_recently_deleted),
                                        leading = { KobbloIconBadge(AppIcons.Trash, KobbloTint.Gray) },
                                        trailing = { KobbloChevron() },
                                        onClick = { onAction(AppAction.SetTrashScreenVisible(true)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showOptions) {
        AlbumOptionsSheet(
            sort = sort,
            onSort = {
                store.setAlbumSort(it)
                showOptions = false
                scope.launch { listState.scrollToItem(0) }
            },
            onStats = {
                showOptions = false
                Hub.open(GalleryRoute.LibraryStats)
            },
            onDismiss = { showOptions = false }
        )
    }

    if (showCreate) {
        AlbumNameSheet(
            title = stringResource(R.string.gallery_album_new),
            initial = "",
            confirmLabel = stringResource(R.string.gallery_album_create),
            onConfirm = { name ->
                showCreate = false
                scope.launch {
                    when (val made = createAlbum(context, name)) {
                        is AlbumResult.Made -> GalleryNav.open(GalleryDest.Folder(made.folder.path, made.folder.name))
                        AlbumResult.Taken -> onAction(AppAction.ShowToast(context.getString(R.string.gallery_album_name_taken)))
                        AlbumResult.Failed -> onAction(AppAction.ShowToast(context.getString(R.string.gallery_album_create_failed)))
                    }
                }
            },
            onDismiss = { showCreate = false }
        )
    }

    actionsFor?.let { album ->
        FolderAlbumMenu(
            album = album,
            files = state.galleryFiles,
            onAction = onAction,
            onDismiss = { actionsFor = null }
        )
    }

    namingPerson?.let { person ->
        com.kobblo.gallery.file.explorer.smart.NamePersonSheet(person) {
            namingPerson = null
            peopleVersion++
        }
    }
}

@Composable
private fun CountRow(title: String, icon: ImageVector, tint: Color, count: Int, showDivider: Boolean, onClick: () -> Unit) {
    KobbloRow(
        title = title,
        leading = { KobbloIconBadge(icon, tint) },
        trailing = {
            Text(
                text = "%,d".format(count),
                style = KobbloType.callout,
                color = KobbloDesign.colors.smoke,
                modifier = Modifier.padding(end = 6.dp)
            )
            KobbloChevron()
        },
        showDivider = showDivider,
        onClick = onClick
    )
}

@Composable
internal fun AlbumTile(
    album: FolderAlbum,
    coverPx: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KobbloDesign.colors
    val source = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .pressScale(pressedScale = 0.97f, interactionSource = source)
            .quietCombinedClickable(source = source, onLongClick = onLongClick, onClick = onClick)
    ) {
        AlbumCover(
            files = album.covers,
            sizePx = coverPx,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(KobbloShapes.sm)
        )
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (album.isPinned) {
                Icon(AppIcons.PinFill, contentDescription = stringResource(R.string.pinned_badge), tint = colors.smoke, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
            }
            if (album.isHiddenFromHome) {
                Icon(
                    AppIcons.EyeOff,
                    contentDescription = stringResource(R.string.gallery_album_hidden),
                    tint = colors.smoke,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = album.name,
                style = KobbloType.callout.copy(fontWeight = FontWeight.Medium),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "%,d".format(album.count),
            style = KobbloType.footnote,
            color = colors.smoke,
            maxLines = 1
        )
    }
}

/**
 * Files found not to decode, so going back over an album doesn't try them again. This lasts as long as
 * the app does, which is fine: a damaged file rarely repairs itself, and a new one has a new id.
 */
private val UndecodableCovers = mutableStateMapOf<Int, Unit>()

/**
 * A square picture for an album or a row. The newest file that decodes wins, so one damaged file can't
 * blank a whole album. With nothing to show, it's a quiet glyph.
 */
@Composable
internal fun AlbumCover(files: List<FileItem>, sizePx: Int, modifier: Modifier = Modifier) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val file = files.firstOrNull { it.id !in UndecodableCovers && !it.isLocked }
    Box(modifier = modifier.background(colors.mist), contentAlignment = Alignment.Center) {
        if (file == null) {
            Icon(
                imageVector = AppIcons.Photo,
                contentDescription = null,
                tint = colors.pewter,
                modifier = Modifier.size(32.dp)
            )
        } else {
            val isVideo = file.type == "video"
            val request = remember(file.id, file.sizeBytes, file.lastModified, sizePx) {
                ImageRequest.Builder(context)
                    .data(GalleryThumb(file.id.toLong(), file.path, isVideo, sizePx))
                    .fetcherFactory(GalleryThumbFetcher.Factory(context))
                    .memoryCacheKey(MemoryCache.Key("album:${file.id}:${file.lastModified}:${file.sizeBytes}:$sizePx"))
                    .size(sizePx)
                    .crossfade(120)
                    .apply { if (isVideo) videoFrameMillis(1000L) }
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                onError = { UndecodableCovers[file.id] = Unit },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

internal fun smartAlbumTitle(context: android.content.Context, kind: SmartKind): String = context.getString(
    when (kind) {
        SmartKind.FAVORITES -> R.string.gallery_smart_favorites
        SmartKind.RECENTLY_ADDED -> R.string.gallery_smart_recent
        SmartKind.ON_THIS_DAY -> R.string.gallery_smart_on_this_day
        SmartKind.CAMERA -> R.string.gallery_smart_camera
        SmartKind.SCREENSHOTS -> R.string.gallery_smart_screenshots
        SmartKind.VIDEOS -> R.string.gallery_smart_videos
        SmartKind.GIFS -> R.string.gallery_smart_gifs
        SmartKind.RAW -> R.string.gallery_smart_raw
        SmartKind.PANORAMAS -> R.string.gallery_smart_panoramas
        SmartKind.MOTION_PHOTOS -> R.string.gallery_smart_motion
    }
)

private fun smartAlbumLook(kind: SmartKind): Pair<ImageVector, Color> = when (kind) {
    SmartKind.FAVORITES -> AppIcons.StarFill to KobbloTint.Yellow
    SmartKind.RECENTLY_ADDED -> AppIcons.Clock to KobbloTint.Blue
    SmartKind.ON_THIS_DAY -> AppIcons.Calendar to KobbloTint.Red
    SmartKind.CAMERA -> AppIcons.Camera to KobbloTint.Gray
    SmartKind.SCREENSHOTS -> AppIcons.Screenshot to KobbloTint.Teal
    SmartKind.VIDEOS -> AppIcons.PlayRect to KobbloTint.Pink
    SmartKind.GIFS -> AppIcons.Loop to KobbloTint.Green
    SmartKind.RAW -> AppIcons.Aperture to KobbloTint.Brown
    SmartKind.PANORAMAS -> AppIcons.Panorama to KobbloTint.Indigo
    SmartKind.MOTION_PHOTOS -> AppIcons.MotionPhoto to KobbloTint.Orange
}

internal sealed interface AlbumResult {
    data class Made(val folder: File) : AlbumResult
    data object Taken : AlbumResult
    data object Failed : AlbumResult
}

/** New albums live in Pictures, where the system and other gallery apps expect to find them. */
internal suspend fun createAlbum(context: android.content.Context, rawName: String): AlbumResult =
    withContext(Dispatchers.IO) {
        val name = sanitizeAlbumName(rawName) ?: return@withContext AlbumResult.Failed
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), name)
        if (folder.exists()) return@withContext AlbumResult.Taken
        if (!folder.mkdirs()) return@withContext AlbumResult.Failed
        GalleryStore.get(context).addCreatedAlbum(folder.path)
        AlbumResult.Made(folder)
    }
