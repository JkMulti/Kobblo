package com.kobblo.gallery.file.explorer.explorer.drag

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.size.Precision
import coil.size.Scale
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveFileThumbnail
import com.kobblo.gallery.file.explorer.ui.expressive.hasThumbnail
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import java.io.File
import kotlin.math.min

/**
 * Rows of a [androidx.compose.foundation.lazy.LazyColumn], which all run the width of the list up to the readable limit.
 * Layout says where each one is, which is why nothing has to be measured row by row.
 */
internal class RowDragList(
    private val state: LazyListState,
    private val readablePx: Int,
    private val fileOf: (LazyListItemInfo) -> FileItem?
) : DragList(TileStyle.Row) {
    override fun forEachTile(visit: TileVisitor) {
        val info = state.layoutInfo
        val width = min(info.viewportSize.width, readablePx)
        val left = (info.viewportSize.width - width) / 2
        // Offsets start after the padding, and the padding is still part of the list on screen.
        val start = info.viewportStartOffset
        info.visibleItemsInfo.forEach { item ->
            val file = fileOf(item) ?: return@forEach
            visit.visit(file, left, item.offset - start, width, item.size)
        }
    }

    override fun scrollBy(pixels: Float): Float = state.dispatchRawDelta(pixels)
}

/** Cells of a lazy grid. */
internal class GridDragList(
    private val state: LazyGridState,
    style: TileStyle,
    private val startPaddingPx: Int,
    private val fileOf: (LazyGridItemInfo) -> FileItem?
) : DragList(style) {
    override fun forEachTile(visit: TileVisitor) {
        val info = state.layoutInfo
        val start = info.viewportStartOffset
        info.visibleItemsInfo.forEach { item ->
            val file = fileOf(item) ?: return@forEach
            visit.visit(file, item.offset.x + startPaddingPx, item.offset.y - start, item.size.width, item.size.height)
        }
    }

    override fun scrollBy(pixels: Float): Float = state.dispatchRawDelta(pixels)
}

/** Rows of a [androidx.compose.foundation.lazy.LazyColumn] that each hold a line of cards, as a category's icons do. */
internal class CardRowDragList(
    private val state: LazyListState,
    private val columns: () -> Int,
    private val sidePaddingPx: Int,
    private val gapPx: Int,
    private val rowBottomPx: Int,
    private val cardsOf: (LazyListItemInfo) -> List<FileItem>?
) : DragList(TileStyle.Card) {
    override fun forEachTile(visit: TileVisitor) {
        val info = state.layoutInfo
        val count = columns().coerceAtLeast(1)
        val cell = (info.viewportSize.width - sidePaddingPx * 2 - gapPx * (count - 1)) / count
        val start = info.viewportStartOffset
        info.visibleItemsInfo.forEach { item ->
            val cards = cardsOf(item) ?: return@forEach
            cards.forEachIndexed { index, file ->
                visit.visit(file, sidePaddingPx + index * (cell + gapPx), item.offset - start, cell, item.size - rowBottomPx)
            }
        }
    }

    override fun scrollBy(pixels: Float): Float = state.dispatchRawDelta(pixels)
}

/**
 * Goes on the list itself. Finds out where the list is, once, and draws the ghosts over its own rows, which is how they go
 * faint without any row being told. The same modifier from one composition to the next, so a list recomposing costs nothing.
 */
@Composable
internal fun Modifier.fileDragList(
    list: DragList,
    place: ((androidx.compose.ui.layout.LayoutCoordinates) -> Unit)? = null
): Modifier {
    val colors = KobbloDesign.colors
    val ghost = if (list.style == TileStyle.Row) colors.snow else colors.canvas
    val density = LocalDensity.current
    return this.then(
        remember(list, ghost, density, place) {
            Modifier
                .onPlaced(place ?: { list.coordinates = it })
                .drawWithContent {
                    drawContent()
                    val session = FileDrag.session ?: return@drawWithContent
                    val veil = session.veil
                    if (veil > 0f) {
                        val faded = GhostFade * veil
                        drawGhosts(list, session.selection.ids, ghost.copy(alpha = faded), density)
                        // What's held is as faint as the others from the first frame. The deck doesn't cover it, and its
                        // press springing back while it fades in plain view reads as a flicker. This is what it still lacks.
                        if (session.isCarrying && faded < GhostFade) session.cards.firstOrNull()?.file?.id?.let { held ->
                            val rest = 1f - (1f - GhostFade) / (1f - faded)
                            drawGhosts(list, setOf(held), ghost.copy(alpha = rest), density)
                        }
                    }
                }
        }
    )
}

private fun DrawScope.drawGhosts(list: DragList, ids: Set<Int>, color: Color, density: Density) = clipRect {
    when (list.style) {
        TileStyle.Square -> list.forEachTile { file, left, top, width, height ->
            if (file.id in ids) {
                drawRect(color, Offset(left.toFloat(), top.toFloat()), Size(width.toFloat(), height.toFloat()))
            }
        }

        TileStyle.Card -> {
            val radius = CornerRadius(with(density) { 12.dp.toPx() })
            list.forEachTile { file, left, top, width, height ->
                if (file.id in ids) {
                    drawRoundRect(color, Offset(left.toFloat(), top.toFloat()), Size(width.toFloat(), height.toFloat()), radius)
                }
            }
        }

        TileStyle.Row -> {
            // Just inside the group, with the same gap on every side and a corner that follows the group's.
            val gap = KobbloSpace.xxs
            val side = with(density) { (KobbloSpace.screen + gap).toPx() }
            val edge = with(density) { gap.toPx() }
            val radius = CornerRadius(with(density) { com.kobblo.gallery.file.explorer.ui.design.concentricRadius(com.kobblo.gallery.file.explorer.ui.design.KobbloShapes.groupRadius, gap).toPx() })
            list.forEachTile { file, left, top, width, height ->
                if (file.id in ids) {
                    drawRoundRect(
                        color,
                        Offset(left + side, top + edge),
                        Size(width - side * 2f, height - edge * 2f),
                        radius
                    )
                }
            }
        }
    }
}

private const val GhostFade = 0.66f

/** An action in the selection bar, which lights up under a stack and does its tap when one is let go on it. */
@Composable
internal fun Modifier.dragActionTarget(key: String, tint: Color, shape: Shape, onDrop: () -> Unit): Modifier {
    val target = remember(key) { ChromeTarget(key) }
    val currentDrop by rememberUpdatedState(onDrop)
    SideEffect { target.action = { currentDrop() } }
    KeepChrome(target)
    return this
        .onPlaced { target.coordinates = it }
        .graphicsLayer {
            val lit = FileDrag.litAmount(key)
            val grow = 1f + 0.1f * lit
            scaleX = grow
            scaleY = grow
        }
        .drawBehind {
            val lit = FileDrag.litAmount(key)
            if (lit > 0f) {
                drawOutline(shape.createOutline(size, layoutDirection, this), tint.copy(alpha = 0.18f * lit))
            }
        }
}

/** A path segment or Back, which lights up under a stack, opens if it's stayed on and takes a drop. */
@Composable
internal fun Modifier.dragFolderTarget(path: String, name: String, tint: Color, shape: Shape): Modifier {
    val slop = with(LocalDensity.current) { 6.dp.toPx() }
    val target = remember(path) { ChromeTarget("folder:$path", slop = slop) }
    SideEffect {
        target.folderPath = path
        target.folderName = name
    }
    KeepChrome(target)
    return this
        .onPlaced { target.coordinates = it }
        .drawBehind {
            val lit = FileDrag.litAmount(target.key)
            if (lit > 0f) {
                drawOutline(shape.createOutline(size, layoutDirection, this), tint.copy(alpha = 0.18f * lit))
            }
        }
}

/** A floating bar: what's behind it can't be seen, so it can't be dropped on either. */
@Composable
internal fun Modifier.dragBlocker(): Modifier {
    val target = remember { ChromeTarget("blocker", isBlocker = true) }
    KeepChrome(target)
    return this.onPlaced { target.coordinates = it }
}

@Composable
private fun KeepChrome(target: ChromeTarget) {
    DisposableEffect(target) {
        FileDrag.chrome.add(target)
        onDispose { FileDrag.chrome.remove(target) }
    }
}

/** A screen listing [AppState.files], drawn the way every file list is. */
internal abstract class FileListSource : DragSource() {
    lateinit var state: AppState

    override fun selection() = DragSelection(state.selectedFiles, state.files)

    @Composable
    override fun Thumbnail(file: FileItem, size: Dp) {
        // Exactly the size the list draws it, because anything larger misses the cache and shows a blank card while
        // it decodes. An icon grid draws a document a little smaller than its cell: see BrowseGridCard.
        val drawn = if (list?.style == TileStyle.Card && file.type != "folder") size * 0.92f else size
        Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
            ExpressiveFileThumbnail(file = file, isSelected = false, size = drawn, shape = KobbloShapes.sm, cornerRadius = 12.dp)
        }
    }

    override fun pictureRequest(context: Context, file: FileItem, px: Int): ImageRequest.Builder? {
        if (!hasThumbnail(file)) return null
        // See Thumbnail: a cached picture smaller than what's asked for doesn't count.
        val asked = if (list?.style == TileStyle.Card) (px * 0.92f).toInt() else px
        val model: Any = file.thumbnailLink ?: File(file.path)
        return ImageRequest.Builder(context)
            .data(model)
            .size(asked)
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .memoryCachePolicy(CachePolicy.READ_ONLY)
            .apply { if (file.type == "video") videoFrameMillis(1000L) }
    }
}

/** A folder's page in Browse, in any of the three ways it can be listing things. */
internal class FolderDragSource(
    rowsState: LazyListState,
    gridState: LazyGridState,
    resultsState: LazyListState,
    readablePx: Int,
    gridPaddingPx: Int
) : FileListSource() {
    val rows = RowDragList(rowsState, readablePx) { state.files.getOrNull(it.index) }
    val grid = GridDragList(gridState, TileStyle.Card, gridPaddingPx) { state.files.getOrNull(it.index) }
    val results = RowDragList(resultsState, readablePx) { state.files.getOrNull(it.index) }

    var searching = false

    override val list: DragList
        get() = when {
            searching -> results
            state.isListMode -> rows
            else -> grid
        }

    override val folder: String?
        get() = state.location.takeIf { !searching && it.startsWith("/") }

    override val canOpenFolders: Boolean get() = !searching
}

@Composable
internal fun rememberFolderDrag(
    state: AppState,
    enabled: Boolean,
    searching: Boolean,
    rowsState: LazyListState,
    gridState: LazyGridState,
    resultsState: LazyListState
): FolderDragSource {
    val density = LocalDensity.current
    val source = remember(rowsState, gridState, resultsState) {
        with(density) {
            FolderDragSource(
                rowsState,
                gridState,
                resultsState,
                KobbloSpace.readableWidth.roundToPx(),
                KobbloSpace.screen.roundToPx()
            )
        }
    }
    source.state = state
    SideEffect { source.searching = searching }
    RegisterDragSource(source, enabled)
    return source
}

/** Recents, in whichever view it's in. Headers sit between its rows, so its items go by key rather than by position. */
internal class RecentsDragSource(
    rowsState: LazyListState,
    gridState: LazyGridState,
    resultsState: LazyListState,
    readablePx: Int,
    gridPaddingPx: Int
) : FileListSource() {
    private var indexedFrom: List<FileItem>? = null
    private var byId: Map<Int, FileItem> = emptyMap()

    /** Only built once a drag asks, since that's the only thing that needs it. */
    private fun fileWith(key: Any): FileItem? {
        val id = (key as? String)?.removePrefix("recent_")?.toIntOrNull() ?: return null
        if (indexedFrom !== state.files) {
            indexedFrom = state.files
            byId = state.files.associateBy { it.id }
        }
        return byId[id]
    }

    val rows = RowDragList(rowsState, readablePx) { fileWith(it.key) }
    val grid = GridDragList(gridState, TileStyle.Card, gridPaddingPx) { fileWith(it.key) }
    val results = RowDragList(resultsState, readablePx) { state.files.getOrNull(it.index) }
    var searching = false

    override val list: DragList
        get() = when {
            searching -> results
            state.isListMode -> rows
            else -> grid
        }

    override val folder: String? get() = null
}

@Composable
internal fun rememberRecentsDrag(
    state: AppState,
    searching: Boolean,
    rowsState: LazyListState,
    gridState: LazyGridState,
    resultsState: LazyListState
): RecentsDragSource {
    val density = LocalDensity.current
    val source = remember(rowsState, gridState, resultsState) {
        with(density) {
            RecentsDragSource(
                rowsState,
                gridState,
                resultsState,
                KobbloSpace.readableWidth.roundToPx(),
                KobbloSpace.screen.roundToPx()
            )
        }
    }
    source.state = state
    SideEffect { source.searching = searching }
    RegisterDragSource(source, true)
    return source
}

/** A category, whose list has date headers and whose icons are lines of cards. */
internal class CategoryDragSource(
    listState: LazyListState,
    readablePx: Int,
    sidePaddingPx: Int,
    gapPx: Int,
    rowBottomPx: Int
) : FileListSource() {
    var columns = 3
    var cardRows: () -> Map<Any, List<FileItem>> = { emptyMap() }

    private var indexedFrom: List<FileItem>? = null
    private var byId: Map<Int, FileItem> = emptyMap()

    private fun fileWith(key: Any): FileItem? {
        val id = key as? Int ?: return null
        if (indexedFrom !== state.files) {
            indexedFrom = state.files
            byId = state.files.associateBy { it.id }
        }
        return byId[id]
    }

    private val rows = RowDragList(listState, readablePx) { fileWith(it.key) }
    private val cards = CardRowDragList(listState, { columns }, sidePaddingPx, gapPx, rowBottomPx) { cardRows()[it.key] }

    /** Whichever view is up. Both draw into the same list. */
    override val list: DragList
        get() = if (state.isListMode) rows else cards

    override val folder: String? get() = null

    /** The same coordinates for both, since it's the same list. */
    fun place(coordinates: androidx.compose.ui.layout.LayoutCoordinates) {
        rows.coordinates = coordinates
        cards.coordinates = coordinates
    }
}

@Composable
internal fun rememberCategoryDrag(state: AppState, listState: LazyListState): CategoryDragSource {
    val density = LocalDensity.current
    val source = remember(listState) {
        with(density) {
            CategoryDragSource(
                listState,
                KobbloSpace.readableWidth.roundToPx(),
                KobbloSpace.screen.roundToPx(),
                12.dp.roundToPx(),
                18.dp.roundToPx()
            )
        }
    }
    source.state = state
    RegisterDragSource(source, true)
    return source
}

/**
 * The modifiers, for screens that write every name out in full. Being inside an object keeps them from having to import
 * this whole package to use them.
 */
internal object FileDragModifiers {
    @Composable
    fun Modifier.host(selecting: Boolean, location: String, folderName: String?, onAction: (com.kobblo.gallery.file.explorer.AppAction) -> Unit): Modifier =
        fileDragHost(selecting, location, folderName, onAction)

    @Composable
    fun Modifier.list(list: DragList): Modifier = fileDragList(list)

    @Composable
    fun Modifier.list(list: DragList, place: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit): Modifier = fileDragList(list, place)

    @Composable
    fun Modifier.folderTarget(path: String, name: String, tint: Color, shape: Shape): Modifier = dragFolderTarget(path, name, tint, shape)
}
