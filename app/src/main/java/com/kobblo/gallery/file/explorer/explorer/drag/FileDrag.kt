package com.kobblo.gallery.file.explorer.explorer.drag

import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.haptics.HapticsController
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion

/** How a list draws an item, which says where the picture sits inside it. */
internal enum class TileStyle { Square, Card, Row }

/** One item on screen, in the list's own pixels. */
internal fun interface TileVisitor {
    fun visit(file: FileItem, left: Int, top: Int, width: Int, height: Int)
}

/**
 * A list a drag can start from or hover over. Everything here is worked out from the list's own layout when asked,
 * so a list that isn't being dragged pays nothing per row.
 */
internal abstract class DragList(val style: TileStyle) {
    var coordinates: LayoutCoordinates? = null

    /** Every item the layout has on screen, whether or not it's a file. Things that aren't get left out here. */
    abstract fun forEachTile(visit: TileVisitor)

    /** How much it really moved, which is nothing at either end. */
    abstract fun scrollBy(pixels: Float): Float

    /** Where the picture is inside an item. */
    fun thumbOf(left: Float, top: Float, width: Float, height: Float, density: Density): Rect = when (style) {
        TileStyle.Square -> Rect(left, top, left + width, top + height)
        TileStyle.Card -> Rect(left, top, left + width, top + width)
        TileStyle.Row -> with(density) {
            val size = RowThumbnail.toPx()
            val middle = top + height / 2f
            val start = left + RowThumbnailInset.toPx()
            Rect(start, middle - size / 2f, start + size, middle + size / 2f)
        }
    }

    private companion object {
        val RowThumbnail = com.kobblo.gallery.file.explorer.ui.design.KobbloSize.thumbnail
        // The inset group's margin and then the row's own padding: see KobbloFileRow.
        val RowThumbnailInset = com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.content
    }
}

/** What the person has chosen, as it stood when they picked it up. */
internal class DragSelection(val ids: Set<Int>, val files: List<FileItem>) {
    val count: Int get() = ids.size
    val items: List<FileItem> by lazy { files.filter { it.id in ids } }
    val paths: List<String> by lazy { items.map { it.path } }
    val folders: List<String> by lazy { items.filter { it.type == "folder" }.map { it.path } }
}

/** A screen that lets its selection be picked up. */
internal abstract class DragSource {
    /** Whatever's being drawn right now, if it's a list. */
    abstract val list: DragList?

    /** The folder this screen shows, if it's showing one folder and not a search or a feed. */
    abstract val folder: String?

    /** Whether a hover can open a folder from here, which leaves this screen. */
    open val canOpenFolders: Boolean get() = false

    abstract fun selection(): DragSelection

    /** The picture for a folder being hovered over, the way this screen draws it. */
    @Composable
    abstract fun Thumbnail(file: FileItem, size: Dp)

    /**
     * What to ask the image loader for to put this on a card, at [px], or null if this kind of file has no picture. It
     * has to look the picture up just as the list's own request does, so that what's already on screen is found at once,
     * but it never adds to the cache: a large selection would push out what the list is about to need.
     */
    abstract fun pictureRequest(context: Context, file: FileItem, px: Int): ImageRequest.Builder?

    var pageCoordinates: LayoutCoordinates? = null
}

/**
 * Something in the floating bars or the header that takes part in a drag: an action, a folder, or just a bar in the
 * way of the list behind it.
 */
internal class ChromeTarget(
    val key: String,
    val isBlocker: Boolean = false,
    /** How far past its edges it still counts, for what's too thin to land on with a stack in the way. */
    val slop: Float = 0f
) {
    var coordinates: LayoutCoordinates? = null
    var folderPath: String? = null
    var folderName: String? = null
    var action: (() -> Unit)? = null

    fun boundsIn(host: LayoutCoordinates): Rect? {
        val own = coordinates?.takeIf { it.isAttached } ?: return null
        // Clipped, so an action scrolled out of the bar, or a path bar folded away with the title, isn't there.
        val bounds = host.localBoundingBoxOf(own, clipBounds = true)
        return bounds.takeIf { it.width > 0f && it.height > 0f }
    }
}

/** What a drag needs from the app around it, kept up to date from composition. */
internal class DragHost {
    var coordinates: LayoutCoordinates? = null
    var onAction: (AppAction) -> Unit = {}
    var haptics: HapticsController? = null
    var location by mutableStateOf("")
    var folderName: String? = null
    var reduced: Boolean = false
    var density: Density = Density(1f)
    lateinit var context: Context

    /** Where the floating bars start, or the bottom of everything if there are none. */
    fun floatingTop(within: LayoutCoordinates): Float {
        var top = Float.MAX_VALUE
        FileDrag.chrome.forEach { target ->
            if (target.isBlocker) target.boundsIn(within)?.let { top = minOf(top, it.top) }
        }
        return top
    }
}

/**
 * Picking up a selection and carrying it across the app. A list that's left the screen can't track a finger, and hovering
 * over a folder takes the list away, so the finger is watched from above all of them and each screen only says what's on it.
 */
internal object FileDrag {
    var session by mutableStateOf<DragSession?>(null)
        private set

    internal val host = DragHost()
    private val sources = ArrayList<DragSource>()
    internal val chrome = ArrayList<ChromeTarget>()

    private var fingerId: PointerId? = null
    private var fingerAt: Offset? = null

    /**
     * Ids only mean something in the list they came from, so going to another folder normally clears them. While a
     * drag is on its way through folders, they're what it's going to come back to.
     */
    val carriesSelection: Boolean get() = session?.holdsSelection == true

    fun register(source: DragSource) {
        sources.remove(source)
        sources.add(source)
    }

    fun unregister(source: DragSource) {
        sources.remove(source)
    }

    /** The newest screen under this point: during a folder's slide, the one arriving. */
    fun sourceAt(point: Offset): DragSource? {
        val within = host.coordinates ?: return null
        for (i in sources.indices.reversed()) {
            val source = sources[i]
            val own = (source.pageCoordinates ?: source.list?.coordinates)?.takeIf { it.isAttached } ?: continue
            if (within.localBoundingBoxOf(own).contains(point)) return source
        }
        return null
    }

    /** The newest screen, whichever part of it the finger's on. */
    fun newestSource(): DragSource? = sources.lastOrNull()

    /**
     * A hold on something selected. False means it isn't for a drag to handle, as with an accessibility service's long
     * click, or on a screen with no drag, and the item should do what a hold has always done.
     */
    fun lift(file: FileItem): Boolean {
        if (session != null) return true
        val pointer = fingerId ?: return false
        val point = fingerAt ?: return false
        val source = sourceAt(point) ?: return false
        val started = DragSession.start(host, source, file, pointer, point) ?: return false
        session = started
        host.haptics?.tap()
        return true
    }

    internal fun fingerDown(id: PointerId, position: Offset) {
        fingerId = id
        fingerAt = position
    }

    internal fun fingerMoved(position: Offset) {
        fingerAt = position
    }

    /** Past one finger, it's not a hold any more. */
    internal fun fingerGone() {
        fingerId = null
        fingerAt = null
    }

    internal fun finished(ended: DragSession) {
        if (session === ended) session = null
    }

    /** How lit an action or a path segment is right now. Read from layers and drawing, so nothing recomposes for it. */
    fun litAmount(key: String): Float {
        val current = session ?: return 0f
        return if (current.hover?.key == key) current.hoverAmount else 0f
    }

    /** Whatever was running goes back as it was, without waiting for any more frames. */
    fun abandon() {
        session?.abandon()
        session = null
    }
}

/** Keeps a screen's drag registered for as long as it can start one. */
@Composable
internal fun RegisterDragSource(source: DragSource, enabled: Boolean) {
    DisposableEffect(source, enabled) {
        if (enabled) FileDrag.register(source)
        onDispose { FileDrag.unregister(source) }
    }
}

/**
 * Goes on what holds the whole app, above every list. Costs nothing until a selection exists: the finger's only watched
 * while there's something to pick up, and nothing is taken from anyone until a drag has begun.
 */
@Composable
internal fun Modifier.fileDragHost(
    selecting: Boolean,
    location: String,
    folderName: String?,
    onAction: (AppAction) -> Unit
): Modifier {
    val host = FileDrag.host
    val haptics = LocalHaptics.current
    val reduced = ExpressiveMotion.isReducedMotion()
    val density = LocalDensity.current
    val context = LocalContext.current
    SideEffect {
        host.onAction = onAction
        host.haptics = haptics
        host.location = location
        host.folderName = folderName
        host.reduced = reduced
        host.density = density
        host.context = context
    }
    DisposableEffect(Unit) {
        onDispose {
            FileDrag.abandon()
            // This outlives the activity, so it shouldn't be what keeps one alive.
            host.onAction = {}
            host.haptics = null
            host.coordinates = null
            host.context = context.applicationContext
        }
    }
    return this
        .onPlaced { host.coordinates = it }
        .pointerInput(selecting) {
            // If the selection goes while something's being carried, whatever took it away, this stopping cancels the drag.
            if (selecting) trackFinger()
        }
}

private suspend fun PointerInputScope.trackFinger() {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        FileDrag.fingerDown(down.id, down.position)
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val finger = event.changes.firstOrNull { it.id == down.id }
                val pressed = event.changes.count { it.pressed }
                val session = FileDrag.session?.takeIf { it.pointer == down.id }
                if (session == null) {
                    if (finger == null || !finger.pressed) break
                    if (pressed > 1) FileDrag.fingerGone() else FileDrag.fingerMoved(finger.position)
                    continue
                }
                // A release that's already consumed up here is the system taking the touch away, for a call or a
                // shade, and doesn't mean let go. Nobody's seen the event before this, so nobody else could have.
                val takenAway = finger == null || (!finger.pressed && finger.isConsumed)
                event.changes.forEach { it.consume() }
                when {
                    takenAway -> session.cancel()
                    !finger!!.pressed -> session.letGo(finger.position)
                    pressed > 1 -> session.cancel()
                    else -> session.moveTo(finger.position)
                }
                if (finger == null || event.changes.none { it.pressed }) break
            }
        } finally {
            FileDrag.fingerGone()
            FileDrag.session?.takeIf { it.pointer == down.id }?.cancel()
        }
    }
}

/** What a target looks like to the overlay. */
@Stable
internal class DropView(
    val key: String,
    val hit: DropHit,
    /** Followed every frame without recomposing, since the list can scroll under a hover. */
    var bounds: Rect,
    var thumb: Rect?,
    val file: FileItem?,
    val chrome: ChromeTarget?,
    val source: DragSource?,
    /** Whether anything should light up for it. */
    val valid: Boolean,
    /** Whether staying on it opens it. */
    val canOpen: Boolean
)

/**
 * A move a drag made that came out exactly as asked, which is what can be put back exactly.
 *
 * @param back the folders things came from, with where each of them is now.
 */
class DragMoved internal constructor(val message: String, val back: Map<String, List<String>>) {
    companion object {
        /**
         * Null when it can't simply be reversed: anything that failed, or anything that took a new name to fit, since
         * moving it back wouldn't give it its old name again.
         */
        internal fun from(result: com.kobblo.gallery.file.explorer.fileops.JobResult): DragMoved? {
            val report = result.payload as? com.kobblo.gallery.file.explorer.fileops.TransferReport ?: return null
            val folder = result.folder ?: return null
            val message = result.message?.takeIf { it.isNotBlank() } ?: return null
            val back = DropRules.undoPlan(report.ok, report.renamed, report.movedFrom, report.outputs, folder) ?: return null
            return DragMoved(message, back)
        }
    }
}
