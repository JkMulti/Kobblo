package com.kobblo.gallery.file.explorer.viewer.pdf

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A whole page, at fit-width or less. */
internal class PdfPageBitmap(val page: Int, val width: Int, val height: Int, val image: ImageBitmap, val bytes: Int)

/** Part of a page at full sharpness for the zoom it was made at. */
internal class PdfDetailTile(val page: Int, val zoom: Float, val region: PdfRect, val image: ImageBitmap, val bytes: Int)

/** Holds rendered pages up to a budget in bytes, dropping the ones looked at longest ago, but never one in view. */
internal class PdfBitmapCache(private val budgetBytes: Int) {
    private val pages = LinkedHashMap<Int, PdfPageBitmap>(32, 0.75f, true)
    private var bytes = 0

    operator fun get(page: Int): PdfPageBitmap? = pages[page]

    fun contains(page: Int): Boolean = pages.containsKey(page)

    fun put(entry: PdfPageBitmap, keep: IntRange) {
        pages.remove(entry.page)?.let { bytes -= it.bytes }
        pages[entry.page] = entry
        bytes += entry.bytes
        trim(keep)
    }

    /** Whether something of this size would fit without pushing out a page in view. */
    fun fits(size: Int, keep: IntRange): Boolean {
        // Over the entries, as looking a page up here counts as using it and reorders what's being walked.
        var protected = 0
        for ((page, held) in pages) if (page in keep) protected += held.bytes
        return protected + size <= budgetBytes
    }

    private fun trim(keep: IntRange) {
        if (bytes <= budgetBytes) return
        val iterator = pages.entries.iterator()
        while (bytes > budgetBytes && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in keep) continue
            bytes -= entry.value.bytes
            iterator.remove()
        }
    }

    fun clear() {
        pages.clear()
        bytes = 0
    }
}

/**
 * Everything the reader's screen shows and everything the render loop decides from. It all runs on the main
 * thread, apart from the actual drawing of a page, which happens in another process and comes back here.
 *
 * Nothing ahead of need is rendered in bulk. One loop asks what's most wanted right now, renders that, and asks
 * again. That one rule is what keeps memory flat in any size of file and means a page scrolled past is simply
 * never drawn, as nothing queues up behind what's on screen.
 */
@Stable
internal class PdfReaderState(
    val doc: PdfDoc,
    context: Context,
    private val saved: PdfPlace?
) {
    private val sizes = PdfPageSizes(doc.pageCount, doc.firstWidth, doc.firstHeight)
    private val cache = PdfBitmapCache(cacheBudget(context))
    private val tiles = HashMap<Int, PdfDetailTile>()
    private val failed = HashSet<Int>()
    private val store = PdfPlaceStore(context)
    private val gapPx = context.resources.displayMetrics.density * 6f

    var view by mutableStateOf(PdfView(0f, 0f))
        private set
    var rotation by mutableIntStateOf(saved?.rotation?.mod(360) ?: 0)
        private set
    var layout by mutableStateOf<PdfLayout?>(null)
        private set
    var camera by mutableStateOf(PdfCamera())
        private set

    /** Bumped when something was rendered, for the page canvas to pick up. */
    var redraw by mutableIntStateOf(0)
        private set

    var fingersDown by mutableStateOf(false)

    /** Set while something is moving the view in big jumps, when whole pages would never finish in time. */
    private var lastMoveAt = 0L
    private var moveSpeedPx = 0f

    var bookmarks by mutableStateOf(saved?.bookmarks.orEmpty().toSet())
        private set

    /** Restored on the first layout. After that the anchor comes from wherever the camera is. */
    private var pendingAnchor: PdfAnchor? = saved?.let { PdfAnchor(it.page, it.fraction, it.xFraction) }
    private var pendingZoom: Float = saved?.zoom ?: 1f
    val wasRestored: Boolean = saved != null && (saved.page > 0 || saved.fraction > 0.02f)

    val currentPage: Int get() = layout?.currentPage(camera, view) ?: 0

    val pageCount: Int get() = doc.pageCount

    fun setViewport(width: Float, height: Float, paddingTop: Float, paddingBottom: Float) {
        val next = PdfView(width, height, paddingTop, paddingBottom)
        if (next == view || width <= 0f || height <= 0f) return
        val widthChanged = next.width != view.width
        val anchor = layout?.anchorOf(camera, view)
        view = next
        rebuild(anchor, clearBitmaps = widthChanged)
    }

    fun rotateClockwise() {
        val anchor = layout?.anchorOf(camera, view)
        rotation = (rotation + 90) % 360
        rebuild(anchor?.copy(xFraction = 0f), clearBitmaps = true)
    }

    private fun rebuild(anchor: PdfAnchor?, clearBitmaps: Boolean) {
        val fresh = PdfLayout(sizes, rotation, view.width, gapPx)
        val place = pendingAnchor ?: anchor
        val zoom = if (pendingAnchor != null) pendingZoom else camera.zoom
        pendingAnchor = null
        layout = fresh
        camera = if (place != null) fresh.cameraFor(place, zoom, view) else fresh.clamp(camera, view)
        if (clearBitmaps) {
            cache.clear()
            tiles.clear()
            failed.clear()
        }
        redraw++
    }

    // ── Moving ───────────────────────────────────────────────────────────────

    /** Returns false when it had nowhere to go, which ends a fling. */
    fun panBy(dx: Float, dy: Float): Boolean {
        val current = layout ?: return false
        val next = current.panned(camera, dx, dy, view)
        val moved = next != camera
        moveTo(next)
        return moved
    }

    fun transformBy(zoomChange: Float, focusX: Float, focusY: Float, panX: Float, panY: Float) {
        val current = layout ?: return
        val zoomed = current.zoomed(camera, camera.zoom * zoomChange, focusX, focusY, view)
        moveTo(current.panned(zoomed, -panX, -panY, view))
    }

    fun zoomFrom(start: PdfCamera, zoom: Float, focusX: Float, focusY: Float) {
        val current = layout ?: return
        moveTo(current.zoomed(start, zoom, focusX, focusY, view))
    }

    fun goToPage(page: Int) {
        val current = layout ?: return
        moveTo(current.cameraFor(PdfAnchor(page.coerceIn(0, doc.pageCount - 1), 0f, 0f), camera.zoom, view))
    }

    private val linksByPage = HashMap<Int, List<PdfLink>>()

    /**
     * The link at a spot on screen, if any. Asked for page by page, and only on a tap, as most pages have none. A tap
     * has to answer at once, so if a large file is slow to say, this tap goes without and the next has the answer.
     */
    suspend fun linkAt(spotX: Float, spotY: Float, slopPx: Float, scope: CoroutineScope): PdfLink? {
        val current = layout ?: return null
        val at = camera
        val page = current.pageAt((at.y + spotY - view.paddingTop) / at.zoom)
        val links = linksByPage[page] ?: run {
            val asking = scope.async { doc.pageLinks(page).also { linksByPage[page] = it } }
            withTimeoutOrNull(LinkWaitMs) { asking.await() } ?: return null
        }
        if (links.isEmpty()) return null
        val rect = current.screenRect(page, at, view)
        val (x, y) = PdfGeometry.pageToPoints((spotX - rect.left) / at.zoom, (spotY - rect.top) / at.zoom, current, page)
        val pointsPerPixel = current.displayWidth(page) / (current.pageWidth * at.zoom)
        return PdfLinkGeometry.hit(links, x, y, slopPx * pointsPerPixel)
    }

    fun show(hit: PdfHit) {
        val current = layout ?: return
        moveTo(current.cameraShowing(hit.page, hit.firstRect, camera, view))
    }

    /** Where the view sits in the whole document, from 0 to 1, in pixels so that a tall page gets its due. */
    val scrollFraction: Float
        get() {
            val current = layout ?: return 0f
            val range = current.totalHeight * camera.zoom + view.paddingTop + view.paddingBottom - view.height
            return if (range > 0.0) (camera.y / range).toFloat().coerceIn(0f, 1f) else 0f
        }

    fun scrollToFraction(fraction: Float) {
        val current = layout ?: return
        val range = current.totalHeight * camera.zoom + view.paddingTop + view.paddingBottom - view.height
        moveTo(current.clamp(camera.copy(y = range * fraction.coerceIn(0f, 1f)), view))
    }

    private fun moveTo(next: PdfCamera) {
        val now = SystemClock.uptimeMillis()
        val elapsed = (now - lastMoveAt).coerceAtLeast(1L)
        moveSpeedPx = (abs(next.y - camera.y).toFloat() / elapsed)
        lastMoveAt = now
        camera = next
    }

    // ── What to draw ─────────────────────────────────────────────────────────

    fun pageBitmap(page: Int): PdfPageBitmap? = cache[page]

    fun detailTile(page: Int): PdfDetailTile? = tiles[page]

    fun isPageFailed(page: Int): Boolean = page in failed

    // ── The render loop ──────────────────────────────────────────────────────

    private sealed interface Job {
        class Whole(val page: Int, val spec: PdfRenderSpec) : Job
        class Detail(val page: Int, val zoom: Float, val region: PdfRect, val spec: PdfRenderSpec) : Job
        class Later(val millis: Long) : Job
    }

    /** Runs for as long as the screen does. */
    suspend fun runRenderLoop() {
        while (true) {
            if (doc.isDead) return
            when (val job = nextJob()) {
                null -> awaitChange()
                is Job.Later -> delay(job.millis)
                is Job.Whole -> {
                    val bitmap = doc.render(job.page, job.spec)
                    if (doc.isDead) return
                    if (bitmap == null) {
                        failed.add(job.page)
                    } else {
                        addWhole(job, bitmap)
                    }
                    redraw++
                }
                is Job.Detail -> {
                    val bitmap = doc.render(job.page, job.spec)
                    if (doc.isDead) return
                    if (bitmap == null) {
                        // The whole page is still there, just softer. It isn't worth another try for this view.
                        failed.add(job.page)
                    } else {
                        tiles[job.page] = PdfDetailTile(job.page, job.zoom, job.region, bitmap.prepared().asImageBitmap(), bitmap.allocationByteCount)
                        dropFarTiles()
                    }
                    redraw++
                }
            }
        }
    }

    private fun addWhole(job: Job.Whole, bitmap: Bitmap) {
        val keep = wantedRange()
        cache.put(PdfPageBitmap(job.page, bitmap.width, bitmap.height, bitmap.prepared().asImageBitmap(), bitmap.allocationByteCount), keep)
    }

    /** Puts the pixels on their way to the GPU now, rather than in the middle of the frame that first needs them. */
    private fun Bitmap.prepared(): Bitmap = also { it.prepareToDraw() }

    private fun dropFarTiles() {
        val current = layout ?: return
        val visible = current.visiblePages(camera, view)
        tiles.keys.filter { it !in visible }.forEach { tiles.remove(it) }
    }

    /** A view of tiny pages can take in hundreds. Only so many get drawn for it at once. */
    private fun visibleRange(current: PdfLayout): IntRange {
        val visible = current.visiblePages(camera, view)
        if (visible.isEmpty()) return visible
        if (visible.last - visible.first < MaxPagesPerView) return visible
        val center = current.currentPage(camera, view)
        val first = max(visible.first, center - MaxPagesPerView / 2)
        return first..min(visible.last, first + MaxPagesPerView - 1)
    }

    private fun wantedRange(): IntRange {
        val current = layout ?: return IntRange.EMPTY
        return visibleRange(current)
    }

    private fun nextJob(): Job? {
        val current = layout ?: return null
        val at = camera
        val window = view
        if (window.width <= 0f) return null
        val visible = visibleRange(current)
        if (visible.isEmpty()) return null
        val center = current.currentPage(at, window)

        // What's on screen comes first, from the middle out.
        for (offset in 0..(visible.last - visible.first)) {
            for (page in if (offset == 0) listOf(center) else listOf(center + offset, center - offset)) {
                if (page !in visible || page in failed) continue
                val spec = PdfGeometry.baseSpec(current, page)
                val held = cache[page]
                if (held == null || held.width != spec.width || held.height != spec.height) return Job.Whole(page, spec)
            }
        }

        val idleFor = SystemClock.uptimeMillis() - lastMoveAt
        val settled = !fingersDown && idleFor >= SettleMs

        // Then sharpness, once nothing is moving: while a finger is on the glass a stretched page is fine.
        if (at.zoom >= 1f) {
            for (page in visible) {
                if (page in failed || !PdfGeometry.needsDetail(current, page, at.zoom)) continue
                val tile = tiles[page]
                val serves = tile != null && PdfGeometry.detailStillServes(current, page, tile.zoom, tile.region, at, window)
                if (serves) continue
                if (!settled) return Job.Later(max(SettleMs - idleFor, FingerPollMs))
                val region = PdfGeometry.detailRegion(current, page, at, window) ?: continue
                return Job.Detail(page, at.zoom, region, PdfGeometry.detailSpec(current, page, at.zoom, region))
            }
        }

        // Then what's about to come into view, but not during a fling, when that guess goes stale at once.
        if (moveSpeedPx * 16f > window.height * FastPerFrame && idleFor < SettleMs) return Job.Later(FingerPollMs)
        val ahead = arrayOf(visible.last + 1, visible.first - 1, visible.last + 2)
        for (page in ahead) {
            if (page !in 0 until doc.pageCount || page in failed) continue
            val spec = PdfGeometry.baseSpec(current, page)
            val held = cache[page]
            if (held != null && held.width == spec.width && held.height == spec.height) continue
            if (!cache.fits(spec.width * spec.height * BytesPerPixel, visible)) continue
            return Job.Whole(page, spec)
        }
        return null
    }

    private suspend fun awaitChange() {
        val startCamera = camera
        val startView = view
        val startRotation = rotation
        val startFingers = fingersDown
        snapshotFlow { camera != startCamera || view != startView || rotation != startRotation || fingersDown != startFingers }
            .first { it }
    }

    /** Learns every page's real size, a few at a time. Until then each page is taken to be like the first. */
    suspend fun scanPageSizes() {
        var from = 0
        while (from < doc.pageCount) {
            val pairs = doc.pageSizes(from, SizeBatch) ?: return
            if (pairs.isEmpty()) return
            if (sizes.update(from, pairs)) {
                val anchor = layout?.anchorOf(camera, view)
                if (view.width > 0f) rebuild(anchor, clearBitmaps = false)
            }
            from += pairs.size / 2
            // The sizes are only for the layout, so they wait for anything in view.
            delay(1)
        }
    }

    // ── Keeping the place ────────────────────────────────────────────────────

    fun toggleBookmark(page: Int) {
        bookmarks = if (page in bookmarks) bookmarks - page else bookmarks + page
        savePlace()
    }

    fun savePlace() {
        val current = layout ?: return
        if (doc.fingerprint.isEmpty()) return
        val anchor = current.anchorOf(camera, view)
        store.put(
            doc.fingerprint,
            PdfPlace(anchor.page, anchor.fraction, camera.zoom, anchor.xFraction, rotation, bookmarks.sorted())
        )
    }

    private companion object {
        const val BytesPerPixel = 4
        const val MaxPagesPerView = 12
        const val SettleMs = 140L
        const val FingerPollMs = 40L
        const val SizeBatch = 24
        const val LinkWaitMs = 150L

        /** A move of more than this share of the window per frame is a fling rather than reading. */
        const val FastPerFrame = 0.12f

        /**
         * Bitmaps here live in shared memory, outside the Java heap, but they're real memory all the same. A few
         * screens' worth covers what's in view and what's next. Going back further than that costs a moment's render.
         */
        fun cacheBudget(context: Context): Int {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val byClass = manager.memoryClass * 1024 * 1024 / 4
            val metrics = context.resources.displayMetrics
            val shortSide = min(min(metrics.widthPixels, metrics.heightPixels), PdfGeometry.MaxBaseWidth)
            val byScreen = PagesToKeep * shortSide * shortSide * 3 / 2 * BytesPerPixel
            return min(byClass, byScreen).coerceAtLeast(MinCacheBytes)
        }

        const val PagesToKeep = 8
        const val MinCacheBytes = 24 * 1024 * 1024
    }
}
