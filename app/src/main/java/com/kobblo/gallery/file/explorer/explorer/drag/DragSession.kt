package com.kobblo.gallery.file.explorer.explorer.drag

import android.util.SparseArray
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AuthReason
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.fileops.LockGate
import com.kobblo.gallery.file.explorer.fileops.Locks
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal enum class DragPhase { Carrying, Returning, Absorbing, Done }

/**
 * One picked-up selection, from the hold until the last card has gone. Runs from a frame loop and writes only to a counter
 * that layers read, so a drag at any size recomposes nothing but the overlay's hover, and only when the hover changes.
 */
internal class DragSession private constructor(
    private val host: DragHost,
    val origin: DragSource,
    val selection: DragSelection,
    /** Everything being carried, top card first. The ones off screen join a frame or two after the lift. */
    val cards: ArrayList<CardModel>,
    val pointer: PointerId,
    private val stack: DragStack,
    private val offScreen: OffScreenCards?,
    start: Offset
) {
    var phase by mutableStateOf(DragPhase.Carrying)
        private set

    /** Layers read this to know there's a new frame. */
    var tick by mutableIntStateOf(0)
        private set

    /** How faint the originals are, from nothing to fully ghosted. */
    var veil by mutableFloatStateOf(0f)
        private set

    var hover by mutableStateOf<DropView?>(null)
        private set

    var hoverAmount by mutableFloatStateOf(0f)
        private set

    /** What just took the stack, while it's pulsing. */
    var pulseTarget by mutableStateOf<DropView?>(null)
        private set

    var pulse by mutableFloatStateOf(0f)
        private set

    var badge by mutableFloatStateOf(0f)
        private set

    val total: Int get() = selection.count
    val top: DragCard get() = cards[0].body
    val pictures = CardPictures(host.context, origin)

    /** Whether the cards' pictures have had time to arrive. Until then the originals are what's showing. */
    val hasAppeared: Boolean get() = tick >= FirstFrames
    val holdsSelection: Boolean get() = phase == DragPhase.Carrying || phase == DragPhase.Returning || waitingAction != null
    val isCarrying: Boolean get() = phase == DragPhase.Carrying
    val isAway: Boolean get() = host.location != originLocation

    private val originLocation = host.location
    private val originName = host.folderName
    private var fingerX = start.x
    private var fingerY = start.y
    private val liftX = start.x
    private val liftY = start.y
    private var moved = false
    private val clock = HoverClock()
    private var lastFrameNanos = 0L

    // The bar's actions look through the selection, and ids only mean something in the list they came from. So one let
    // go on after a hover has opened another folder waits until that list is back, and gives up if it never is.
    private var waitingAction: (() -> Unit)? = null
    private var waitedSeconds = 0f
    private var inZoneSeconds = 0f

    @Volatile
    private var foundOffScreen: List<CardModel>? = null
    private var cardById: SparseArray<CardModel>? = null

    fun moveTo(position: Offset) {
        if (!isCarrying) return
        fingerX = position.x
        fingerY = position.y
        if (!moved) {
            val dx = fingerX - liftX
            val dy = fingerY - liftY
            val slop = with(host.density) { MoveSlop.toPx() }
            if (dx * dx + dy * dy > slop * slop) moved = true
        }
    }

    /** The finger's come up on purpose. */
    fun letGo(position: Offset) {
        if (!isCarrying) return
        moveTo(position)
        if (!moved) {
            cancel()
            return
        }
        val target = findTarget(Offset(fingerX, fingerY))
        val outcome = if (target == null) {
            DropOutcome.GoBack()
        } else {
            DropRules.outcome(target.hit, { selection.paths }, { selection.folders })
        }
        when (outcome) {
            is DropOutcome.RunAction -> {
                val run = target?.chrome?.action
                if (isAway && isClosedBehind()) {
                    cancel()
                    return
                }
                if (isAway) {
                    waitingAction = run
                    host.onAction(AppAction.SetLocation(originLocation, originName))
                    absorbInto(target)
                } else {
                    absorbInto(target)
                    // Exactly what tapping it does, which may be a sheet asking first.
                    run?.invoke()
                }
            }

            is DropOutcome.MoveTo -> {
                // A locked folder takes its password first, and that takes a screen that can come back to its selection.
                if (target?.file?.isLocked == true && isAway) {
                    cancel()
                    return
                }
                absorbInto(target)
                move(outcome, target)
            }

            is DropOutcome.GoBack -> cancel(refused = outcome.refused)
        }
    }

    private fun move(outcome: DropOutcome.MoveTo, target: DropView?) {
        val action = AppAction.MoveDragged(outcome.paths, outcome.folder)
        val locked = target?.file?.isLocked == true || selection.items.any { it.isLocked }
        if (locked) {
            LockGate.ask { host.onAction(action) }
            host.onAction(
                AppAction.RequestAuth(
                    AuthReason.CONFIRM_ACTION,
                    outcome.folder,
                    fileId = target?.file?.id,
                    folderName = target?.file?.name ?: outcome.folder.substringAfterLast('/')
                )
            )
        } else {
            host.onAction(action)
        }
    }

    private fun absorbInto(target: DropView?) {
        val point = target?.let { (it.thumb ?: it.bounds).center } ?: Offset(fingerX, fingerY)
        val here = target?.hit is DropHit.Here
        stack.startAbsorb(if (here) fingerX else point.x, if (here) fingerY else point.y)
        pulseTarget = target
        hover = null
        phase = DragPhase.Absorbing
    }

    /** Everything goes back. [refused] says why, for something that was asked for and can't be. */
    fun cancel(refused: Boolean = false) {
        if (!isCarrying) return
        phase = DragPhase.Returning
        hover = null
        stack.startReturn()
        if (refused) host.onAction(AppAction.ShowToast(host.context.getString(R.string.explorer_drag_into_itself)))
        if (isAway) goBackToOrigin()
    }

    /** A locked folder the drag has left has closed behind it, and going back in would ask for its password. */
    private fun isClosedBehind(): Boolean =
        originLocation.startsWith("/") && Locks.inTheWay(host.context, originLocation) != null

    private fun goBackToOrigin() {
        // Asking for a password over a stack would be odd, so the selection goes, which is all it would have cost to back out.
        if (isClosedBehind()) {
            host.onAction(AppAction.ClearSelection)
        } else {
            host.onAction(AppAction.SetLocation(originLocation, originName))
        }
    }

    /** The app's going away, so no frames are coming. Nothing to animate, and nothing's been changed but the place. */
    fun abandon() {
        if (isAway && isCarrying) goBackToOrigin()
        phase = DragPhase.Done
    }

    /** Runs for as long as there's anything to show. */
    suspend fun run() = coroutineScope {
        try {
            // What's selected can be a hundred thousand things. The lift can't wait for that, and doesn't need to, as
            // what's on screen goes first and the rest come in from the edges anyway.
            val finding = offScreen?.let { cards -> launch(Dispatchers.Default) { foundOffScreen = cards.find() } }
            lastFrameNanos = withFrameNanos { it }
            while (phase != DragPhase.Done) {
                withFrameNanos { now ->
                    val seconds = (now - lastFrameNanos) / 1_000_000_000f
                    lastFrameNanos = now
                    joinOffScreen()
                    pictures.pump(cards, carrying = phase == DragPhase.Carrying)
                    step(seconds, now / 1_000_000L)
                    tick++
                }
            }
            finding?.cancel()
            FileDrag.finished(this@DragSession)
        } finally {
            pictures.release(cards)
        }
    }

    private fun joinOffScreen() {
        val found = foundOffScreen ?: return
        foundOffScreen = null
        // Too late to be seen going anywhere.
        if (phase != DragPhase.Carrying) return
        for (i in found.indices) {
            cards.add(found[i])
            stack.add(found[i].body)
        }
        cardById = null
    }

    private fun step(seconds: Float, nowMillis: Long) {
        // The first frames show the cards where the originals are, and give their pictures time to arrive from the cache.
        if (!hasAppeared) return
        pictures.cardsAppeared()
        when (phase) {
            DragPhase.Carrying -> {
                stack.carry(seconds, fingerX, fingerY, moved)
                veil = min(1f, veil + seconds / VeilInSeconds)
                badge = min(1f, badge + seconds / BadgeInSeconds)
                val point = Offset(fingerX, fingerY)
                val scrolled = autoScroll(point, seconds)
                val target = findTarget(point)
                val view = target?.takeIf { it.valid }
                val current = hover
                if (view?.key != current?.key) {
                    hover = view
                } else if (view != null && current != null) {
                    current.bounds = view.bounds
                    current.thumb = view.thumb
                }
                val slop = with(host.density) { OpenAgainDistance.toPx() }
                val stillSlop = with(host.density) { StayStillDistance.toPx() }
                if (clock.update(target?.takeIf { it.canOpen }?.key, nowMillis, scrolled, fingerX, fingerY, slop, stillSlop)) {
                    clock.restAt(fingerX, fingerY)
                    open(target!!)
                }
                hoverAmount = approach(hoverAmount, if (view != null) 1f else 0f, seconds / HoverSeconds)
            }

            DragPhase.Returning -> {
                refreshHomes()
                veil = max(0f, veil - seconds / VeilReturnSeconds)
                badge = max(0f, badge - seconds / BadgeOutSeconds)
                hoverAmount = max(0f, hoverAmount - seconds / HoverSeconds)
                if (stack.flyHome(seconds) && veil <= 0f) phase = DragPhase.Done
            }

            DragPhase.Absorbing -> {
                veil = max(0f, veil - seconds / VeilOutSeconds)
                badge = max(0f, badge - seconds / BadgeOutSeconds)
                pulse = min(1f, pulse + seconds / PulseSeconds)
                val gone = stack.absorb(seconds)
                runWaitingAction(seconds)
                if (gone && veil <= 0f && pulse >= 1f && waitingAction == null) phase = DragPhase.Done
            }

            DragPhase.Done -> Unit
        }
    }

    private fun runWaitingAction(seconds: Float) {
        val run = waitingAction ?: return
        waitedSeconds += seconds
        val back = !isAway && FileDrag.newestSource()?.selection()?.let { now ->
            now.files.count { it.id in selection.ids } == selection.count
        } == true
        if (back) {
            waitingAction = null
            run()
        } else if (waitedSeconds > ActionWaitSeconds) {
            // Whatever's happened, nothing's been done to anything, and what's selected is still selected.
            waitingAction = null
        }
    }

    private fun approach(value: Float, target: Float, amount: Float): Float =
        if (value < target) min(target, value + amount) else max(target, value - amount)

    private fun open(target: DropView) {
        val folder = (target.hit as? DropHit.Folder)?.path ?: return
        val name = target.chrome?.folderName ?: target.file?.name ?: folder.substringAfterLast('/')
        host.onAction(AppAction.SetLocation(folder, name))
    }

    /** Keeps the list moving while the finger's at its edge, and says whether it did. */
    private fun autoScroll(point: Offset, seconds: Float): Boolean {
        if (!moved) return false
        val within = host.coordinates ?: return false
        val list = FileDrag.sourceAt(point)?.list ?: return false
        val own = list.coordinates?.takeIf { it.isAttached } ?: return false
        val bounds = within.localBoundingBoxOf(own)
        val floatingTop = floatingTop(within)
        val bottom = minOf(bounds.bottom, floatingTop)
        val zone = with(host.density) { ScrollZone.toPx() }
        val rate = DropRules.autoScrollRate(point.y, bounds.top, bottom, zone, floatingBelow = floatingTop < bounds.bottom)
        if (rate == 0f) {
            inZoneSeconds = 0f
            return false
        }
        // The way to the selection bar goes through the bottom zone, and that shouldn't jog the list on the way past.
        inZoneSeconds += seconds
        if (inZoneSeconds < ScrollDwellSeconds) return false
        val speed = with(host.density) { ScrollSpeedPerSecond.toPx() }
        return list.scrollBy(rate * speed * seconds) != 0f
    }

    /** Where the floating bars start. Past this, nothing in a list can be seen. */
    private fun floatingTop(within: LayoutCoordinates): Float = host.floatingTop(within)

    private fun findTarget(point: Offset): DropView? {
        val within = host.coordinates ?: return null
        val away = isAway

        var chromeHit: ChromeTarget? = null
        var chromeBounds: Rect? = null
        var blocked = false
        // Newest first: while a folder's sliding in, the page on its way out still has a Back button here too.
        for (target in FileDrag.chrome.asReversed()) {
            val bounds = target.boundsIn(within) ?: continue
            if (!bounds.inflate(target.slop).contains(point)) continue
            if (target.isBlocker) {
                blocked = true
            } else {
                chromeHit = target
                chromeBounds = bounds
                break
            }
        }
        if (chromeHit != null) return viewOfChrome(chromeHit, chromeBounds!!)

        val source = FileDrag.sourceAt(point)
        var tile: FileItem? = null
        var tileBounds: Rect? = null
        var tileThumb: Rect? = null
        if (source != null && !blocked) {
            val list = source.list
            val own = list?.coordinates?.takeIf { it.isAttached }
            if (list != null && own != null) {
                val shift = within.localPositionOf(own, Offset.Zero)
                if (within.localBoundingBoxOf(own).contains(point)) {
                    list.forEachTile { file, left, top, width, height ->
                        val x = left + shift.x
                        val y = top + shift.y
                        if (point.x >= x && point.x < x + width && point.y >= y && point.y < y + height) {
                            tile = file
                            // A row's margin is part of its item but not part of what's drawn.
                            val margin = if (list.style == TileStyle.Row) with(host.density) { KobbloSpace.screen.toPx() } else 0f
                            tileBounds = Rect(x + margin, y, x + width - margin, y + height)
                            tileThumb = list.thumbOf(x, y, width.toFloat(), height.toFloat(), host.density)
                        }
                    }
                }
            }
        }
        val under = tile
        val isFolder = under != null && under.type == "folder" && under.path.startsWith("/")
        val hit = DropRules.resolve(
            chrome = null,
            blocked = blocked,
            tileFolder = if (isFolder) under!!.path else null,
            onDragged = under != null && !away && under.id in selection.ids,
            pageFolder = source?.folder,
            originFolder = originLocation
        )
        return when (hit) {
            is DropHit.Folder -> {
                val canReceive = DropRules.canReceive(hit.path, selection.folders)
                // A locked folder's password is asked once it's let go on, but it can't be opened by hovering.
                val locked = under?.isLocked == true
                DropView(
                    key = hit.path,
                    hit = hit,
                    bounds = tileBounds!!,
                    thumb = tileThumb,
                    file = under,
                    chrome = null,
                    source = source,
                    valid = canReceive && (!locked || !away),
                    canOpen = canReceive && !locked && source?.canOpenFolders == true
                )
            }

            is DropHit.Here -> {
                val own = (source?.list?.coordinates ?: source?.pageCoordinates)?.takeIf { it.isAttached }
                    ?: source?.pageCoordinates?.takeIf { it.isAttached }
                val whole = own?.let { within.localBoundingBoxOf(it) } ?: return null
                DropView(
                    key = "here:${hit.path}",
                    hit = hit,
                    bounds = whole.copy(bottom = minOf(whole.bottom, floatingTop(within))),
                    thumb = null,
                    file = null,
                    chrome = null,
                    source = source,
                    valid = DropRules.canReceive(hit.path, selection.folders),
                    canOpen = false
                )
            }

            else -> null
        }
    }

    private fun viewOfChrome(target: ChromeTarget, bounds: Rect): DropView {
        val path = target.folderPath
        return if (path != null) {
            val canReceive = DropRules.canReceive(path, selection.folders)
            DropView(
                key = target.key,
                hit = DropHit.Folder(path),
                bounds = bounds,
                thumb = null,
                file = null,
                chrome = target,
                source = null,
                valid = canReceive,
                canOpen = canReceive
            )
        } else {
            DropView(
                key = target.key,
                hit = DropHit.Action(target.key),
                bounds = bounds,
                thumb = null,
                file = null,
                chrome = target,
                source = null,
                valid = true,
                canOpen = false
            )
        }
    }

    /** The originals may have moved, since the list can have scrolled, and they may have gone off the screen altogether. */
    private fun refreshHomes() {
        for (i in cards.indices) cards[i].body.hasHome = false
        if (isAway) return
        val source = FileDrag.newestSource() ?: return
        val list = source.list ?: return
        val own = list.coordinates?.takeIf { it.isAttached } ?: return
        val within = host.coordinates ?: return
        val shift = within.localPositionOf(own, Offset.Zero)
        val visible = within.localBoundingBoxOf(own)
        val bottom = minOf(visible.bottom, floatingTop(within))
        val byId = cardById ?: SparseArray<CardModel>(cards.size).also { lookup ->
            for (i in cards.indices) lookup.put(cards[i].file.id, cards[i])
            cardById = lookup
        }
        list.forEachTile { file, left, top, width, height ->
            val card = byId.get(file.id) ?: return@forEachTile
            val thumb = list.thumbOf(left + shift.x, top + shift.y, width.toFloat(), height.toFloat(), host.density)
            // Under the toolbar or the bars is as good as gone.
            if (thumb.center.y in visible.top..bottom) {
                card.body.setHome(thumb.center.x, thumb.center.y, thumb.width)
            }
        }
    }

    companion object {
        private val MoveSlop = 10.dp
        private val OpenAgainDistance = 24.dp
        private val StayStillDistance = 16.dp
        private val StackSize = 104.dp
        private val MaxLiftSize = 168.dp
        // A deck reaches back this share of the screen's short side, at the very most.
        private const val MaxClusterShare = 0.36f
        private val LiftOffset = 30.dp
        private val ScrollZone = 72.dp
        private val ScrollSpeedPerSecond = 850.dp

        private const val VeilInSeconds = 0.14f
        private const val VeilOutSeconds = 0.22f
        private const val VeilReturnSeconds = 0.36f
        private const val FirstFrames = 2
        private const val ScrollDwellSeconds = 0.25f
        private const val ActionWaitSeconds = 4f
        private const val BadgeInSeconds = 0.16f
        private const val BadgeOutSeconds = 0.1f
        private const val HoverSeconds = 0.12f
        private const val PulseSeconds = 0.34f

        /** Null if there's nothing to carry here after all, in which case a hold should do what it always did. */
        fun start(host: DragHost, source: DragSource, held: FileItem, pointer: PointerId, finger: Offset): DragSession? {
            val within: LayoutCoordinates = host.coordinates ?: return null
            val list = source.list ?: return null
            val own = list.coordinates?.takeIf { it.isAttached } ?: return null
            val selection = source.selection()
            if (held.id !in selection.ids) return null

            val density = host.density
            val shift = within.localPositionOf(own, Offset.Zero)
            class Seen(val file: FileItem, val thumb: Rect)
            val visible = ArrayList<Seen>()
            val columns = FloatArray(MaxColumns)
            var columnCount = 0
            val listBounds = within.localBoundingBoxOf(own)
            val bottom = minOf(listBounds.bottom, host.floatingTop(within))
            list.forEachTile { file, left, top, width, height ->
                val thumb = list.thumbOf(left + shift.x, top + shift.y, width.toFloat(), height.toFloat(), density)
                if (columnCount < columns.size && (0 until columnCount).none { kotlin.math.abs(columns[it] - thumb.center.x) < 1f }) {
                    columns[columnCount++] = thumb.center.x
                }
                if (file.id in selection.ids) {
                    // One that's under the toolbar or a floating bar has nowhere to fly out from. The held one is what the
                    // finger's on, so it counts whatever else is true of it.
                    if (file.id == held.id || thumb.center.y in listBounds.top..bottom) visible += Seen(file, thumb)
                }
            }
            val heldSeen = visible.firstOrNull { it.file.id == held.id } ?: return null
            val total = min(selection.count, StackShape.MaxCards)
            val order = flyingOrder(
                heldSeen,
                visible,
                { val d = it.thumb.center - finger; d.x * d.x + d.y * d.y },
                total
            )

            val stackSize = with(density) { StackSize.toPx() }
            val thumbPx = heldSeen.thumb.width
            val liftSize = max(thumbPx * 1.06f, stackSize).coerceAtMost(with(density) { MaxLiftSize.toPx() })
            val shortSide = min(within.size.width, within.size.height) / density.density
            val metrics = StackMetrics(
                stackSize,
                liftSize,
                with(density) { LiftOffset.toPx() },
                density.density,
                min(StackShape.MaxClusterRadius, shortSide * MaxClusterShare),
                within.size.width.toFloat()
            )

            val models = ArrayList<CardModel>(total)
            order.forEachIndexed { index, seen ->
                val body = DragCard(index, seen.thumb.center.x, seen.thumb.center.y, seen.thumb.width)
                body.setHome(seen.thumb.center.x, seen.thumb.center.y, seen.thumb.width)
                models += CardModel(seen.file, body, seen.thumb.width.toInt(), onScreen = true)
            }
            val stack = DragStack(models.map { it.body }, metrics, host.reduced, finger.x, finger.y, total)
            val offScreen = if (models.size < total) {
                columns.sort(0, columnCount)
                val heldColumn = (0 until columnCount).firstOrNull { kotlin.math.abs(columns[it] - heldSeen.thumb.center.x) < 1f } ?: 0
                OffScreenCards(
                    selection = selection,
                    heldId = held.id,
                    onScreenIds = order.mapTo(HashSet(order.size * 2)) { it.file.id },
                    firstIndex = models.size,
                    total = total,
                    columns = columns.copyOf(columnCount),
                    heldColumn = heldColumn,
                    screenHeight = within.size.height.toFloat(),
                    stackSize = stackSize,
                    thumbPx = thumbPx
                )
            } else {
                null
            }
            return DragSession(host, source, selection, models, pointer, stack, offScreen, finger)
        }

        private const val MaxColumns = 16
    }
}

/**
 * Finds what's selected but isn't on screen, nearest first, as far as the ceiling allows. Runs off the main thread, because
 * it has the whole list to look through. Nothing here touches anything that changes: both the list and the selection are
 * what they were when the finger picked them up.
 */
private class OffScreenCards(
    private val selection: DragSelection,
    private val heldId: Int,
    private val onScreenIds: Set<Int>,
    private val firstIndex: Int,
    private val total: Int,
    private val columns: FloatArray,
    private val heldColumn: Int,
    private val screenHeight: Float,
    private val stackSize: Float,
    private val thumbPx: Float
) {
    fun find(): List<CardModel> {
        val files = selection.files
        val ids = selection.ids
        val heldIndex = files.indexOfFirst { it.id == heldId }
        if (heldIndex < 0) return emptyList()
        val found = nearestOutward(files.size, heldIndex, total - firstIndex) { at ->
            val id = files[at].id
            id in ids && id !in onScreenIds
        }
        val cards = ArrayList<CardModel>(found.size)
        for (rank in found.indices) {
            val at = found[rank]
            val index = firstIndex + rank
            val size = stackSize * StackShape.sizeFactor(index, total)
            val x = if (columns.isEmpty()) 0f else columns[CardEntry.column(at, heldIndex, heldColumn, columns.size)]
            val y = CardEntry.edgeY(at, heldIndex, -size, screenHeight + size)
            val body = DragCard(index, x, y, size)
            body.setExit(x, y)
            // Drawn small, and only for as long as this is carried, so it's not worth decoding any bigger than that.
            cards += CardModel(files[at], body, min(thumbPx, ceil(size)).toInt(), onScreen = false)
        }
        return cards
    }
}
