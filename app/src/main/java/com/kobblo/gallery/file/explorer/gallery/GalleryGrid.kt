package com.kobblo.gallery.file.explorer.gallery

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.size.Precision
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.explorer.drag.DragSelection
import com.kobblo.gallery.file.explorer.explorer.drag.DragSource
import com.kobblo.gallery.file.explorer.explorer.drag.FileDrag
import com.kobblo.gallery.file.explorer.explorer.drag.GridDragList
import com.kobblo.gallery.file.explorer.explorer.drag.RegisterDragSource
import com.kobblo.gallery.file.explorer.explorer.drag.TileStyle
import com.kobblo.gallery.file.explorer.explorer.drag.fileDragList
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.design.quietCombinedClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.LocalSelectionActive
import com.kobblo.gallery.file.explorer.ui.expressive.SelectionCircle
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

const val MinGridColumns = 2
const val MaxGridColumns = 6

private val TileGap = 2.dp
/** Below this many screens of photos, a scroll handle would only get in the way. */
private const val HandleMinRows = 40
private const val HandleLingerMillis = 1_100L

@Stable
private class GridPinch(var columns: Int) {
    var active by mutableStateOf(false)
    var scale by mutableFloatStateOf(1f)
    var origin by mutableStateOf(Offset.Zero)
}

/**
 * The photo grid behind Home and every album. Pinching changes the column count around the fingers,
 * a thin handle appears on the right while scrolling a long library, and in selection mode a sideways
 * drag across tiles selects the run, while a vertical one still scrolls.
 */
@Composable
internal fun GalleryGrid(
    feed: GalleryFeed,
    gridState: LazyGridState,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    selected: ImmutableSet<Int>,
    selecting: Boolean,
    onFileClick: (FileItem) -> Unit,
    onFileMenuClick: (FileItem) -> Unit,
    onSelectionChange: (Set<Int>) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    footer: String? = null
) {
    val colors = KobbloDesign.colors
    val scope = rememberCoroutineScope()
    val reduced = ExpressiveMotion.isReducedMotion()
    val haptics = LocalHaptics.current
    val pinch = remember { GridPinch(columns) }
    val settle = remember { Animatable(1f) }
    // Composition lags a frame behind the fingers, so the pinch keeps its own count while it runs.
    SideEffect { if (!pinch.active) pinch.columns = columns }
    val currentFeed by rememberUpdatedState(feed)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnColumns by rememberUpdatedState(onColumnsChange)
    val currentOnSelection by rememberUpdatedState(onSelectionChange)
    val bottomClearancePx = with(LocalDensity.current) { bottomPadding.toPx() }
    val drag = remember(gridState) { GalleryDragSource(gridState) }
    drag.feed = feed
    drag.selected = selected
    RegisterDragSource(drag, enabled = selecting)

    // The viewer turns pages over this grid, and pulling it down uncovers the grid. So the grid keeps up as you
    // swipe, and a photo you've reached is where you'd expect it when you come back.
    LaunchedEffect(gridState) {
        snapshotFlow { GalleryScope.viewerAt }.filterNotNull().collectLatest { path ->
            val shown = currentFeed
            val fileIndex = withContext(Dispatchers.Default) { shown.files.indexOfFirst { it.path == path } }
            if (fileIndex < 0) return@collectLatest
            val gridIndex = shown.gridIndexForFileIndex(fileIndex)
            val info = gridState.layoutInfo
            val tile = info.visibleItemsInfo.firstOrNull { it.index == gridIndex }
            val inView = tile != null && tile.offset.y >= info.viewportStartOffset &&
                tile.offset.y + tile.size.height <= info.viewportEndOffset - bottomClearancePx
            if (!inView) gridState.scrollToItem(gridIndex, -(info.viewportSize.height / 3))
        }
    }

    CompositionLocalProvider(LocalSelectionActive provides selecting) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(Unit) {
                    detectGridPinch(
                        onStart = { origin ->
                            scope.launch {
                                settle.stop()
                                gridState.stopScroll()
                            }
                            pinch.origin = origin
                            pinch.active = true
                        },
                        onPinch = { zoom, centroid ->
                            pinch.scale = (pinch.scale * zoom).coerceIn(0.55f, 1.9f)
                            val target = (pinch.columns / pinch.scale).roundToInt().coerceIn(MinGridColumns, MaxGridColumns)
                            if (target != pinch.columns) {
                                // Tiles keep their on-screen size across the switch, and the tile under
                                // the fingers stays under them, so the change reads as one movement.
                                val anchor = tileUnderPinch(gridState, pinch, centroid)
                                val rebased = pinch.scale * target / pinch.columns
                                val tile = size.width.toFloat() / target
                                pinch.scale = rebased
                                pinch.columns = target
                                currentOnColumns(target)
                                haptics.tap()
                                if (anchor != null) {
                                    scope.launch {
                                        val y = pinch.origin.y + (centroid.y - pinch.origin.y) / rebased
                                        gridState.scrollToItem(anchor.first, -(y - anchor.second * tile).roundToInt())
                                    }
                                }
                            }
                            // Past the first and last counts it can stretch a little, and no more.
                            pinch.scale = when (pinch.columns) {
                                MinGridColumns -> pinch.scale.coerceAtMost(1.18f)
                                MaxGridColumns -> pinch.scale.coerceAtLeast(0.86f)
                                else -> pinch.scale
                            }
                        },
                        onEnd = {
                            pinch.active = false
                            scope.launch {
                                if (reduced) {
                                    pinch.scale = 1f
                                } else {
                                    settle.snapTo(pinch.scale)
                                    settle.animateTo(1f, KobbloMotion.snappy()) { pinch.scale = value }
                                }
                            }
                        }
                    )
                }
                .pointerInput(selecting) {
                    if (selecting) {
                        detectRangeSelection(
                            scope = scope,
                            gridState = gridState,
                            feed = { currentFeed },
                            selected = { currentSelected },
                            onSelection = { currentOnSelection(it) }
                        )
                    }
                }
        ) {
            val thumbPx = thumbnailPx(constraints.maxWidth, columns)
            drag.thumbPx = thumbPx
            val previousPx = remember { intArrayOf(thumbPx) }
            val compact = columns >= 5

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .fileDragList(drag.list)
                    .graphicsLayer {
                        val scale = pinch.scale
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(
                            (pinch.origin.x / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                            (pinch.origin.y / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        )
                    },
                horizontalArrangement = Arrangement.spacedBy(TileGap),
                verticalArrangement = Arrangement.spacedBy(TileGap),
                contentPadding = PaddingValues(bottom = bottomPadding)
            ) {
                feed.sections.forEach { section ->
                    item(
                        key = "section_${section.title}",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "header"
                    ) {
                        // The feed's index maths counts one item per section, so an untitled one still takes a slot.
                        if (section.title.isEmpty()) {
                            Spacer(Modifier.height(TileGap))
                        } else {
                            SectionHeader(
                                title = section.title,
                                selecting = selecting,
                                allSelected = selecting && isSectionSelected(selected, section),
                                onToggle = { onSelectionChange(toggleSection(selected, section)) }
                            )
                        }
                    }
                    items(section.files, key = { it.path }, contentType = { "tile" }) { file ->
                        GalleryTile(
                            file = file,
                            isSelected = selected.contains(file.id),
                            thumbPx = thumbPx,
                            placeholderPx = previousPx[0],
                            compact = compact,
                            onClick = onFileClick,
                            onLongClick = onFileMenuClick
                        )
                    }
                }
                item(
                    key = "gallery_footer",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "footer"
                ) {
                    Text(
                        text = footer ?: galleryCountLabel(feed.photoCount, feed.videoCount),
                        style = KobbloType.footnote,
                        color = colors.smoke,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 8.dp)
                    )
                }
            }
            LaunchedEffect(thumbPx) { previousPx[0] = thumbPx }

            if (feed.files.size / columns >= HandleMinRows) {
                GalleryScrollHandle(
                    feed = feed,
                    gridState = gridState,
                    bottomPadding = bottomPadding,
                    suppressed = pinch.active
                )
            }
        }
    }
}

/** Lets a grid's selection be picked up. There's no folder here to drop on, so the selection bar is where it goes. */
private class GalleryDragSource(gridState: LazyGridState) : DragSource() {
    lateinit var feed: GalleryFeed
    lateinit var selected: Set<Int>
    var thumbPx = 0

    override val list = GridDragList(gridState, TileStyle.Square, 0) { feed.files.getOrNull(feed.fileIndexForGridIndex(it.index)) }
    override val folder: String? get() = null

    override fun selection() = DragSelection(selected, feed.files)

    @Composable
    override fun Thumbnail(file: FileItem, size: Dp) {
        val context = LocalContext.current
        val isVideo = file.type == "video"
        val colors = KobbloDesign.colors
        // The tile's own request, so it's the tile's own picture out of the cache.
        val request = remember(file.id, file.path, thumbPx) {
            ImageRequest.Builder(context)
                .data(GalleryThumb(file.id.toLong(), file.path, isVideo, thumbPx))
                .fetcherFactory(GalleryThumbFetcher.Factory(context))
                .memoryCacheKey(thumbCacheKey(file, thumbPx))
                .size(thumbPx)
                .apply { if (isVideo) videoFrameMillis(1000L) }
                .build()
        }
        Box(modifier = Modifier.size(size).background(colors.mist), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (isVideo) {
                Icon(
                    AppIcons.Play,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(13.dp)
                )
            }
        }
    }

    override fun pictureRequest(context: Context, file: FileItem, px: Int): ImageRequest.Builder? {
        if (file.isLocked) return null
        val isVideo = file.type == "video"
        return ImageRequest.Builder(context)
            .data(GalleryThumb(file.id.toLong(), file.path, isVideo, px, forViewer = false))
            .fetcherFactory(GalleryThumbFetcher.Factory(context))
            // The tile's key, so a picture that's already in memory is what's used, whatever size this asks for.
            .memoryCacheKey(thumbCacheKey(file, thumbPx))
            .memoryCachePolicy(CachePolicy.READ_ONLY)
            .size(px)
            .precision(Precision.INEXACT)
            .apply { if (isVideo) videoFrameMillis(1000L) }
    }
}

/** The grid index of the tile under a pinch, and how far down that tile the fingers are. */
private fun tileUnderPinch(gridState: LazyGridState, pinch: GridPinch, centroid: Offset): Pair<Int, Float>? {
    val x = pinch.origin.x + (centroid.x - pinch.origin.x) / pinch.scale
    val y = pinch.origin.y + (centroid.y - pinch.origin.y) / pinch.scale
    val items = gridState.layoutInfo.visibleItemsInfo
    if (items.isEmpty()) return null
    val hit = items.firstOrNull {
        x >= it.offset.x && x < it.offset.x + it.size.width && y >= it.offset.y && y < it.offset.y + it.size.height
    } ?: items.minByOrNull { abs(it.offset.y + it.size.height / 2f - y) } ?: return null
    return hit.index to ((y - hit.offset.y) / hit.size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
}

/**
 * Watches for two fingers before the grid sees them. It leaves one-finger touches alone, so scrolling
 * and tapping work as usual, and takes over only once the fingers have clearly moved apart or together.
 */
private suspend fun PointerInputScope.detectGridPinch(
    onStart: (Offset) -> Unit,
    onPinch: (zoom: Float, centroid: Offset) -> Unit,
    onEnd: () -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        var pending = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            // Someone above already has these fingers, which is a selection being carried off.
            if (!pinching && event.changes.any { it.isConsumed }) return@awaitEachGesture
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) {
                val zoom = event.calculateZoom()
                val centroid = event.calculateCentroid(useCurrent = true)
                if (!pinching) {
                    pending *= zoom
                    if (abs(pending - 1f) > 0.04f) {
                        pinching = true
                        onStart(centroid)
                        onPinch(pending, centroid)
                    }
                } else {
                    onPinch(zoom, centroid)
                }
            }
            if (pinching) event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        if (pinching) onEnd()
    }
}

/**
 * Selection mode's drag. Moving sideways first starts selecting from the tile under the finger, as a
 * photo library's Select mode does. Moving up or down first is left to the grid, which scrolls. Near
 * the top or bottom edge the grid scrolls on its own to carry the selection further.
 */
private suspend fun PointerInputScope.detectRangeSelection(
    scope: CoroutineScope,
    gridState: LazyGridState,
    feed: () -> GalleryFeed,
    selected: () -> Set<Int>,
    onSelection: (Set<Int>) -> Unit
) {
    val edge = 72.dp.toPx()
    val slop = viewConfiguration.touchSlop

    val gap = TileGap.toPx()

    // A finger landing in the hairline between two tiles is on one of them as far as anyone can tell. With an
    // even number of columns the middle of the screen is exactly that hairline.
    fun fileIndexAt(position: Offset): Int {
        val current = feed()
        val items = gridState.layoutInfo.visibleItemsInfo
        for (slack in floatArrayOf(0f, gap)) {
            for (item in items) {
                if (position.x >= item.offset.x - slack && position.x < item.offset.x + item.size.width + slack &&
                    position.y >= item.offset.y - slack && position.y < item.offset.y + item.size.height + slack
                ) {
                    val index = current.fileIndexForGridIndex(item.index)
                    if (index >= 0) return index
                }
            }
        }
        return -1
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val anchor = fileIndexAt(down.position)
        if (anchor < 0) return@awaitEachGesture

        var started = false
        var pointer: PointerInputChange = down
        while (!started) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } != 1) return@awaitEachGesture
            pointer = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            // Someone above already has this finger, which is a selection being carried off.
            if (pointer.isConsumed) return@awaitEachGesture
            val moved = pointer.position - down.position
            when {
                abs(moved.x) > slop && abs(moved.x) > abs(moved.y) -> started = true
                abs(moved.y) > slop -> return@awaitEachGesture
            }
        }

        val base = selected().toHashSet()
        val files = feed().files
        val adding = files.getOrNull(anchor)?.id !in base
        var last = anchor
        onSelection(rangeSelection(base, files, anchor, anchor, adding))
        pointer.consume()

        var position = pointer.position
        var autoScroll: Job? = null
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                change.consume()
                position = change.position

                val index = fileIndexAt(position)
                if (index >= 0 && index != last) {
                    last = index
                    onSelection(rangeSelection(base, files, anchor, index, adding))
                }

                val speed = when {
                    position.y < edge -> -(edge - position.y) / edge
                    position.y > size.height - edge -> (position.y - (size.height - edge)) / edge
                    else -> 0f
                }
                if (speed == 0f) {
                    autoScroll?.cancel()
                    autoScroll = null
                } else if (autoScroll == null) {
                    autoScroll = scope.launch {
                        while (isActive) {
                            val y = position.y
                            val rate = when {
                                y < edge -> -(edge - y) / edge
                                y > size.height - edge -> (y - (size.height - edge)) / edge
                                else -> 0f
                            }
                            gridState.scrollBy(rate * 28.dp.toPx())
                            val under = fileIndexAt(position)
                            if (under >= 0 && under != last) {
                                last = under
                                onSelection(rangeSelection(base, files, anchor, under, adding))
                            }
                            delay(16)
                        }
                    }
                }
            }
        } finally {
            autoScroll?.cancel()
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    selecting: Boolean,
    allSelected: Boolean,
    onToggle: () -> Unit
) {
    val colors = KobbloDesign.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selecting) Modifier.quietClickable(onClick = onToggle) else Modifier)
            .padding(start = KobbloSpace.screen, end = KobbloSpace.screen, top = KobbloSpace.md, bottom = KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = KobbloType.title.copy(fontWeight = FontWeight.Bold),
            color = colors.ink,
            modifier = Modifier.weight(1f)
        )
        if (selecting) {
            SelectionCircle(selected = allSelected, modifier = Modifier.size(24.dp), onSurface = true)
        }
    }
}

@Composable
private fun GalleryTile(
    file: FileItem,
    isSelected: Boolean,
    thumbPx: Int,
    placeholderPx: Int,
    compact: Boolean,
    onClick: (FileItem) -> Unit,
    onLongClick: (FileItem) -> Unit
) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val selecting = LocalSelectionActive.current
    val source = remember { MutableInteractionSource() }
    val isVideo = file.type == "video"
    val badge = remember(file.name, file.type) { tileBadge(file) }
    val request = remember(file.id, file.path, file.sizeBytes, file.lastModified, thumbPx) {
        ImageRequest.Builder(context)
            .data(GalleryThumb(file.id.toLong(), file.path, isVideo, thumbPx))
            .fetcherFactory(GalleryThumbFetcher.Factory(context))
            .memoryCacheKey(thumbCacheKey(file, thumbPx))
            .apply {
                // While a pinch moves to a sharper size, the old picture holds the tile.
                if (placeholderPx != thumbPx) placeholderMemoryCacheKey(thumbCacheKey(file, placeholderPx))
            }
            .size(thumbPx)
            .crossfade(120)
            .apply { if (isVideo) videoFrameMillis(1000L) }
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .flightCell(file.path)
            .background(colors.mist)
            .quietCombinedClickable(
                source = source,
                onClick = { onClick(file) },
                onLongClick = {
                    // Holding what's chosen picks all of it up. Anything else is a hold as it's always been.
                    if (!isSelected || !FileDrag.lift(file)) {
                        haptics.tap()
                        onLongClick(file)
                    }
                }
            )
            .pressScale(pressedScale = 0.96f, interactionSource = source),
        contentAlignment = Alignment.Center
    ) {
        // A locked file stays private here too.
        if (file.isLocked) {
            Icon(AppIcons.Lock, contentDescription = stringResource(R.string.locked_badge), tint = colors.smoke, modifier = Modifier.size(if (compact) 18.dp else 26.dp))
        } else {
            var undecodable by remember(request) { mutableStateOf(false) }
            AsyncImage(
                model = request,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                onSuccess = {
                    val shape = it.painter.intrinsicSize
                    if (shape.isSpecified) PhotoFlight.rememberAspect(file.path, shape.width, shape.height)
                },
                onError = { undecodable = true },
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isVideo || file.isPinned || (badge != null && !compact)) Modifier.bottomScrim() else Modifier)
            )
            // A file that won't decode still has a place in the feed, and an empty square reads as broken.
            if (undecodable) {
                Icon(
                    imageVector = if (isVideo) AppIcons.Video else AppIcons.Photo,
                    contentDescription = null,
                    tint = colors.pewter,
                    modifier = Modifier.size(if (compact) 18.dp else 28.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = if (compact) 4.dp else 6.dp, vertical = if (compact) 4.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isVideo) {
                Icon(AppIcons.Play, contentDescription = stringResource(R.string.media_tile_video), tint = Color.White, modifier = Modifier.size(if (compact) 10.dp else 13.dp))
            }
            if (file.isPinned) {
                if (isVideo) Spacer(Modifier.width(if (compact) 2.dp else 4.dp))
                Icon(AppIcons.StarFill, contentDescription = stringResource(R.string.media_tile_favorite), tint = Color.White, modifier = Modifier.size(if (compact) 10.dp else 13.dp))
            }
            Spacer(Modifier.weight(1f))
            if (badge != null && !compact) {
                Text(text = badge, style = KobbloType.caption, color = Color.White, maxLines = 1)
            }
            if (isVideo && !compact && !file.duration.isNullOrEmpty()) {
                Text(
                    text = file.duration,
                    style = KobbloType.caption.copy(fontFeatureSettings = "tnum"),
                    color = Color.White,
                    maxLines = 1
                )
            }
        }

        if (selecting) {
            if (isSelected) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.28f)))
            }
            SelectionCircle(
                selected = isSelected,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(if (compact) 4.dp else 6.dp)
                    .size(if (compact) 16.dp else 24.dp)
            )
        }
    }
}

/** The kinds a square tile can't otherwise show: a GIF sits still here, and a RAW looks like any photo. */
internal fun tileBadge(file: FileItem): String? = when {
    file.type != "image" -> null
    isGifFile(file.name, null) -> "GIF"
    isRawFile(file.name, null) -> "RAW"
    else -> null
}

private fun thumbCacheKey(file: FileItem, px: Int): MemoryCache.Key =
    MemoryCache.Key("gallery:${file.id}:${file.lastModified}:${file.sizeBytes}:$px")

/** A soft darkening along the bottom edge, so the white badges read over any picture. */
private fun Modifier.bottomScrim(): Modifier = drawWithContent {
    drawContent()
    val height = size.height * 0.34f
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
            startY = size.height - height,
            endY = size.height
        ),
        topLeft = Offset(0f, size.height - height),
        size = Size(size.width, height)
    )
}

/**
 * A thin handle on the right edge, there only while scrolling. Dragging it travels the whole library,
 * with the date under the thumb, so a year ago is a second away.
 */
@Composable
private fun BoxScope.GalleryScrollHandle(
    feed: GalleryFeed,
    gridState: LazyGridState,
    bottomPadding: Dp,
    suppressed: Boolean
) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableIntStateOf(-1) }
    var shown by remember { mutableStateOf(false) }

    val scrolling = (gridState.isScrollInProgress || dragging) && !suppressed
    LaunchedEffect(scrolling) {
        if (scrolling) {
            shown = true
        } else {
            delay(HandleLingerMillis)
            shown = false
        }
    }

    // Each drag step only needs the latest position, so a slow frame never queues up jumps.
    LaunchedEffect(gridState) {
        snapshotFlow { target }.filter { it >= 0 }.collectLatest { gridState.scrollToItem(it) }
    }

    val fraction by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val range = info.totalItemsCount - info.visibleItemsInfo.size
            if (range <= 0) 0f else (gridState.firstVisibleItemIndex.toFloat() / range).coerceIn(0f, 1f)
        }
    }
    val sectionTitle by remember(feed, gridState) {
        derivedStateOf {
            feed.sections.getOrNull(feed.sectionIndexForGridIndex(gridState.firstVisibleItemIndex))?.title.orEmpty()
        }
    }
    LaunchedEffect(sectionTitle) {
        if (dragging) haptics.tap()
    }

    BoxWithConstraints(
        modifier = Modifier
            .matchParentSize()
            .padding(top = 8.dp, bottom = bottomPadding)
    ) {
        val handleHeight = 52.dp
        val trackPx = with(density) { (maxHeight - handleHeight).toPx().coerceAtLeast(1f) }

        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(KobbloMotion.fadeIn()),
            exit = fadeOut(KobbloMotion.fadeOut()),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (fraction * trackPx).roundToInt()) }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(handleHeight)
            ) {
                if (sectionTitle.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .kobbloGlass(null, CapsuleShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = sectionTitle,
                            style = KobbloType.callout.copy(fontWeight = FontWeight.Medium),
                            color = colors.ink,
                            maxLines = 1
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(handleHeight)
                        .pointerInput(feed) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragging = true
                                    dragFraction = fraction
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    dragFraction = (dragFraction + dragAmount / trackPx).coerceIn(0f, 1f)
                                    val info = gridState.layoutInfo
                                    val range = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)
                                    target = (dragFraction * range).roundToInt()
                                }
                            )
                        },
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .width(if (dragging) 8.dp else 5.dp)
                            .height(handleHeight)
                            .background(if (dragging) colors.action else colors.smoke.copy(alpha = 0.72f), CapsuleShape)
                    )
                }
            }
        }
    }
}

/** The line under a feed: how many of each it holds. */
@Composable
internal fun galleryCountLabel(photos: Int, videos: Int): String {
    val photoText = if (photos > 0) pluralStringResource(R.plurals.media_count_photos, photos, "%,d".format(photos)) else null
    val videoText = if (videos > 0) pluralStringResource(R.plurals.media_count_videos, videos, "%,d".format(videos)) else null
    return if (photoText != null && videoText != null) {
        stringResource(R.string.media_count_photos_and_videos, photoText, videoText)
    } else {
        photoText ?: videoText.orEmpty()
    }
}
