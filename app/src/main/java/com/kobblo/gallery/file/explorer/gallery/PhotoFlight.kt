package com.kobblo.gallery.file.explorer.gallery

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.toSize
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.viewer.decoder.ViewerImages
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * The photo that travels between a grid tile and the viewer.
 *
 * Opening, a copy of the picture grows from the tapped tile until it fills the screen, and the viewer is
 * built underneath it; closing, the copy starts wherever the viewer left the photo and shrinks back into
 * its tile. The tile and the viewer stay hidden meanwhile, so it reads as one photo leaving its slot and
 * coming home to it.
 *
 * A tap calls [requestFor] before it's handled. Only if the tap really opens the viewer does anything
 * move: a locked photo, a selection, or another app chosen to open photos leaves it a plain tap.
 */
object PhotoFlight {

    /** A tile the flight can start from or land in. */
    private class Tile(val coordinates: LayoutCoordinates, val roundness: Float)

    /** One flight: what moves, between which two rects, and over what background. */
    private class Trip(
        val file: FileItem,
        val id: Int,
        var home: Rect,
        var homeCorner: Float,
        var homeless: Boolean,
        val full: Rect,
        val backdrop: Color,
        val backdropFrom: Float,
        var throwVelocity: Velocity = Velocity.Zero
    )

    /** The photo's center and how far it has grown, from 0 (tile size) to 1 (full size). */
    private class FlightPose {
        var x by mutableFloatStateOf(0f)
        var y by mutableFloatStateOf(0f)
        var growth by mutableFloatStateOf(0f)

        fun place(center: Offset, growth: Float) {
            x = center.x
            y = center.y
            this.growth = growth
        }

        suspend fun moveTo(center: Offset, growth: Float, path: AnimationSpec<Float>, sizing: AnimationSpec<Float>, velocity: Velocity) =
            coroutineScope {
                launch { animate(x, center.x, velocity.x, path) { v, _ -> x = v } }
                launch { animate(y, center.y, velocity.y, path) { v, _ -> y = v } }
                launch { animate(this@FlightPose.growth, growth, 0f, sizing) { v, _ -> this@FlightPose.growth = v } }
            }
    }

    private val tiles = HashMap<String, Tile>()
    private val pathAt = IdentityHashMap<LayoutCoordinates, String>()
    private val knownAspects = HashMap<String, Float>()

    private var trip by mutableStateOf<Trip?>(null)
    private val pose = FlightPose()
    private var trips = 0
    private var growJob: Job? = null

    /** The photo in flight, or null while nothing moves. */
    val file: FileItem? get() = trip?.file

    /** A tile that was just tapped, whose photo the viewer may be about to open. */
    var requested by mutableStateOf<String?>(null)
        private set

    /** True while the photo grows. The viewer waits for it, so building the viewer never stutters the flight. */
    var growing by mutableStateOf(false)
        private set

    /** Counts closing flights waiting to land; the overlay lands each one. */
    var landings by mutableIntStateOf(0)
        private set

    /** The size of the area the photo flies across, set by the overlay. */
    internal var screen = IntSize.Zero

    // --- Tiles --------------------------------------------------------------------------------------

    /** Records where the tile for [path] is. [roundness] is its corner radius as a share of its shorter side. */
    fun register(path: String, coordinates: LayoutCoordinates, roundness: Float = 0f) {
        // Lazy grids reuse a tile's node for another photo; forget the old photo if it pointed here.
        val earlier = pathAt.put(coordinates, path)
        if (earlier != null && earlier != path && tiles[earlier]?.coordinates === coordinates) tiles.remove(earlier)
        tiles[path] = Tile(coordinates, roundness)
        if (tiles.size > ForgetDetachedAbove) {
            // A grid that's gone would otherwise keep its whole tree, pictures included, alive through here.
            tiles.values.removeAll { !it.coordinates.isAttached }
            pathAt.keys.removeAll { !it.isAttached }
        }
    }

    /** Where [path]'s tile is on screen, while it's showing. */
    fun cellRect(path: String): Rect? {
        val spot = tiles[path]?.coordinates ?: return null
        if (!spot.isAttached) return null
        return Rect(spot.positionInRoot(), spot.size.toSize())
    }

    private fun cornerFor(path: String, rect: Rect): Float = (tiles[path]?.roundness ?: 0f) * rect.minDimension

    // --- Shapes -------------------------------------------------------------------------------------

    /** Notes a photo's shape as a tile or the viewer saw it. */
    fun rememberAspect(path: String, width: Float, height: Float) {
        if (width > 0f && height > 0f) knownAspects[path] = width / height
    }

    /** A photo's width over height: from the media index when it has measured it, else as seen on screen. */
    fun aspectOf(context: Context, file: FileItem): Float? {
        val measured = MediaIndex.get(context).facts.value[file.id]
        if (measured != null && measured.width > 0 && measured.height > 0) return measured.width.toFloat() / measured.height
        return knownAspects[file.path]
    }

    /** A local photo's shape from its file header, turned the way its EXIF orientation says. */
    private suspend fun aspectFromHeader(file: FileItem): Float? = withContext(Dispatchers.IO) {
        if (file.type != "image" || !file.path.startsWith("/")) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.path, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return@withContext null
        val sideways = runCatching { ExifInterface(file.path).rotationDegrees % 180 != 0 }.getOrDefault(false)
        if (sideways) h.toFloat() / w else w.toFloat() / h
    }

    /** The rect a photo of [aspect] fills when it's fitted inside [width] × [height] and centered. */
    fun fittedRect(aspect: Float?, width: Float, height: Float): Rect {
        val area = Rect(0f, 0f, width, height)
        if (aspect == null || aspect <= 0f || width <= 0f || height <= 0f) return area
        val scale = minOf(width / aspect, height)
        val size = androidx.compose.ui.geometry.Size(scale * aspect, scale)
        return Rect(area.center - Offset(size.width / 2f, size.height / 2f), size)
    }

    // --- Opening ------------------------------------------------------------------------------------

    /**
     * Marks [path] as just tapped, and lets the mark lapse a few frames later if the tap didn't open the
     * viewer: another app was chosen, a lock asked first, or it was a download.
     */
    fun requestFor(scope: CoroutineScope, path: String) {
        if (trip == null) requested = path
        scope.launch {
            repeat(3) { withFrameNanos { } }
            if (requested == path) requested = null
        }
    }

    /** Whether the viewer for [path] should wait, because its photo is still travelling to it. */
    fun holds(path: String?): Boolean =
        path != null && (requested == path || (growing && trip?.file?.path == path))

    /** Grows [file] out of its tile, returning once it fills the screen, or at once when there's no tile. */
    suspend fun open(context: Context, file: FileItem, background: Color) {
        val tile = cellRect(file.path)
        val area = screen
        if (tile == null || area == IntSize.Zero) {
            requested = null
            return
        }
        // Growing to the wrong shape jumps when the viewer takes over, and outside the gallery nothing may
        // have measured the photo yet, so its header is read: it takes a millisecond or two.
        val aspect = aspectOf(context, file) ?: aspectFromHeader(file)?.also { knownAspects[file.path] = it }
        val full = fittedRect(aspect, area.width.toFloat(), area.height.toFloat())
        val current = begin(file, home = tile, homeless = false, full = full, backdrop = background, backdropFrom = 1f)
        current.homeCorner = cornerFor(file.path, tile)
        pose.place(tile.center, growth = 0f)
        requested = null
        growing = true
        growJob = currentCoroutineContext()[Job]
        try {
            pose.moveTo(full.center, 1f, OpenPath, OpenSize, Velocity.Zero)
        } finally {
            growing = false
            growJob = null
        }
    }

    /** The viewer is now built under the photo. Two frames later it has drawn, and the copy can go. */
    suspend fun handOver() {
        val id = trip?.id ?: return
        repeat(2) { withFrameNanos { } }
        if (trip?.id == id && !growing) trip = null
    }

    /** Back pressed while the photo is still growing: it turns round and lands in its tile instead. */
    fun turnBack() {
        if (!growing) return
        growJob?.cancel()
        trip?.throwVelocity = Velocity.Zero
        landings++
    }

    // --- Closing ------------------------------------------------------------------------------------

    /**
     * Starts the trip home from [from], where the viewer is leaving the photo. Call it in the frame the
     * viewer goes, so the photo continues from exactly there, moving at [velocity].
     */
    fun beginClose(file: FileItem, from: Rect, background: Color, backgroundAlpha: Float, velocity: Velocity) {
        val current = begin(file, home = from.scaledAboutCenter(0.6f), homeless = true, full = from, backdrop = background, backdropFrom = backgroundAlpha)
        current.throwVelocity = velocity
        pose.place(from.center, growth = 1f)
        requested = null
        landings++
    }

    /** Finds the tile (it may have moved) and flies into it; with no tile, the photo shrinks and fades. */
    internal suspend fun land() {
        val current = trip ?: return
        cellRect(current.file.path)?.let { tile ->
            current.home = tile
            current.homeCorner = cornerFor(current.file.path, tile)
            current.homeless = false
        }
        val v = current.throwVelocity
        val capped = Velocity(v.x.coerceIn(-MaxThrow, MaxThrow), v.y.coerceIn(-MaxThrow, MaxThrow))
        pose.moveTo(current.home.center, 0f, LandPath, LandSize, capped)
        if (trip?.id == current.id) trip = null
    }

    /** Drops any flight, as a new activity starts with none under way. */
    fun reset() {
        growJob?.cancel()
        growJob = null
        trip = null
        requested = null
        growing = false
        trips++
    }

    private fun begin(file: FileItem, home: Rect, homeless: Boolean, full: Rect, backdrop: Color, backdropFrom: Float): Trip {
        val next = Trip(
            file = file,
            id = ++trips,
            home = home,
            homeCorner = 0f,
            homeless = homeless,
            full = full,
            backdrop = backdrop,
            backdropFrom = backdropFrom
        )
        trip = next
        return next
    }

    // --- What the overlay draws ---------------------------------------------------------------------

    private val progress: Float get() = pose.growth.coerceIn(0f, 1f)

    internal fun currentRect(): Rect {
        val current = trip ?: return Rect.Zero
        val t = progress
        val width = lerp(current.home.width, current.full.width, t)
        val height = lerp(current.home.height, current.full.height, t)
        return Rect(Offset(pose.x - width / 2f, pose.y - height / 2f), androidx.compose.ui.geometry.Size(width, height))
    }

    /** Rounded like the tile near it, squaring off as the photo grows. */
    internal fun currentCorner(): Float = (trip?.homeCorner ?: 0f) * (1f - progress)

    internal fun backgroundAlpha(): Float = progress * (trip?.backdropFrom ?: 0f)
    internal fun photoAlpha(): Float = if (trip?.homeless == true) progress else 1f
    internal fun backgroundColor(): Color = trip?.backdrop ?: Color.Transparent

    private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

    private fun Rect.scaledAboutCenter(factor: Float): Rect {
        val size = androidx.compose.ui.geometry.Size(width * factor, height * factor)
        return Rect(center - Offset(size.width / 2f, size.height / 2f), size)
    }

    // Springs end at their visibility threshold. The default is a hundredth of a pixel, an invisible tail
    // that kept the viewer waiting after the photo had clearly arrived, so these end sooner.
    private const val PIXEL_DONE = 0.5f
    private const val FRACTION_DONE = 0.002f

    /** Quick and smooth, with a hint of settle: about a third of a second. */
    private val OpenPath = spring(dampingRatio = 0.9f, stiffness = 320f, visibilityThreshold = PIXEL_DONE)
    private val OpenSize = spring(dampingRatio = 0.9f, stiffness = 320f, visibilityThreshold = FRACTION_DONE)

    /** Loose enough that a throw carries on visibly before curving home. */
    private val LandPath = spring(dampingRatio = 0.82f, stiffness = 230f, visibilityThreshold = PIXEL_DONE)

    /** The size never overshoots: shrinking past the tile and back would read as a second animation. */
    private val LandSize = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 260f, visibilityThreshold = FRACTION_DONE)

    /** Pixels a second. A harder throw would leave the screen before turning back. */
    private const val MaxThrow = 5000f

    /** A few screens of tiles; above this, tiles no longer attached are forgotten. */
    private const val ForgetDetachedAbove = 400
}

/**
 * Lets the flight find this tile, and hides the tile while its photo is away from it, in flight or in the
 * viewer. [cornerShare] is the tile's corner radius as a share of its shorter side.
 */
fun Modifier.flightCell(path: String, cornerShare: Float = 0f): Modifier = this
    .onPlaced { PhotoFlight.register(path, it, cornerShare) }
    .graphicsLayer {
        val away = PhotoFlight.file?.path == path || GalleryScope.viewerAt == path
        alpha = if (away) 0f else 1f
    }

/** The flying photo, over everything, with the viewer's background fading behind it. Empty when nothing moves. */
@Composable
fun PhotoFlightOverlay(modifier: Modifier = Modifier) {
    // A new or recreated activity starts clean; a flight cut short by the old one would otherwise stick.
    DisposableEffect(Unit) {
        PhotoFlight.reset()
        onDispose { PhotoFlight.reset() }
    }
    val landings = PhotoFlight.landings
    LaunchedEffect(landings) {
        if (landings == 0) return@LaunchedEffect
        // Give the grid under the closing viewer one frame to lay out again before looking for the tile.
        withFrameNanos { }
        PhotoFlight.land()
    }
    Box(modifier.fillMaxSize().onSizeChanged { PhotoFlight.screen = it }) {
        val file = PhotoFlight.file ?: return@Box
        val context = LocalContext.current
        Box(Modifier.fillMaxSize().drawBehind { drawRect(PhotoFlight.backgroundColor(), alpha = PhotoFlight.backgroundAlpha()) })
        val request = remember(file.path, file.lastModified) {
            // The same request the viewer makes, so the decode started by the tap is the one it shows.
            val longSide = maxOf(PhotoFlight.screen.width, PhotoFlight.screen.height).coerceAtLeast(1)
            if (file.type == "video") ViewerImages.posterRequest(context, file, longSide)
            else ViewerImages.fullRequest(context, file, 0)
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            // Cropped into a rect that changes from the tile's shape to the photo's, so the tile's crop opens
            // out into the whole photo as it grows.
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .layout { measurable, constraints ->
                    val rect = PhotoFlight.currentRect()
                    val placeable = measurable.measure(
                        Constraints.fixed(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1))
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
                    }
                }
                .graphicsLayer {
                    alpha = PhotoFlight.photoAlpha()
                    val radius = PhotoFlight.currentCorner()
                    if (radius > 0.5f) {
                        shape = RoundedRectangle(radius.toDp())
                        clip = true
                    }
                }
        )
    }
}

/** [this] scaled by [scale] about [pivot] and then moved by [translation], as a graphics layer would draw it. */
internal fun Rect.transformed(pivot: Offset, scale: Float, translation: Offset): Rect {
    fun map(point: Offset) = pivot + (point - pivot) * scale + translation
    return Rect(map(topLeft), map(bottomRight))
}
