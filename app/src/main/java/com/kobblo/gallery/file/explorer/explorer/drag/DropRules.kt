package com.kobblo.gallery.file.explorer.explorer.drag

/** How long a finger has to stay on a folder before it opens with the drag still running. */
internal const val SpringLoadMillis = 700L

/** What a finger is over right now, before asking what letting go would mean. */
internal sealed interface DropHit {
    /** One of the selection bar's actions. */
    data class Action(val key: String) : DropHit

    /** A folder: a tile in the list, a path segment or Back. */
    data class Folder(val path: String) : DropHit

    /** The list of a folder the drag has opened, which is as good as that folder's icon. */
    data class Here(val path: String) : DropHit

    /** Nothing that takes a drop. */
    data object Nothing : DropHit
}

/** What letting go means, once everything that's being carried has been looked at. */
internal sealed interface DropOutcome {
    data class RunAction(val key: String) : DropOutcome

    /** Moves [paths], which leaves out anything already in the folder, into [folder]. */
    data class MoveTo(val folder: String, val paths: List<String>) : DropOutcome

    /** Everything goes back. [refused] says the person should be told why, because they asked for something that can't be. */
    data class GoBack(val refused: Boolean = false) : DropOutcome
}

internal object DropRules {
    private fun isSameOrInside(path: String, folder: String): Boolean = path == folder || path.startsWith("$folder/")

    /** Whether hovering over [folder] should light it up. */
    fun canReceive(folder: String, draggedFolders: Collection<String>): Boolean =
        draggedFolders.none { isSameOrInside(folder, it) }

    /**
     * What's under a finger. The floating bars come first, because they're drawn over the list and whatever's under
     * them can't be seen.
     *
     * @param chrome what's under the finger in the floating bars and the header, or null if nothing is.
     * @param blocked whether the finger is on one of those bars, but not on anything in it.
     * @param tileFolder the path of the folder under the finger in the list, if there is one and it isn't being dragged.
     * @param onDragged the finger is over one of the dragged items themselves, which is nowhere.
     * @param pageFolder the folder this screen shows, if it shows just one.
     * @param originFolder the folder the drag started in. Letting go there changes nothing.
     */
    fun resolve(
        chrome: DropHit?,
        blocked: Boolean,
        tileFolder: String?,
        onDragged: Boolean,
        pageFolder: String?,
        originFolder: String?
    ): DropHit = when {
        chrome != null -> chrome
        blocked -> DropHit.Nothing
        onDragged -> DropHit.Nothing
        tileFolder != null -> DropHit.Folder(tileFolder)
        pageFolder != null && pageFolder != originFolder -> DropHit.Here(pageFolder)
        else -> DropHit.Nothing
    }

    /**
     * @param paths loaded only if a folder is involved, since Select All on a large library is a lot to walk for no reason.
     */
    fun outcome(
        hit: DropHit,
        paths: () -> List<String>,
        draggedFolders: () -> Collection<String>
    ): DropOutcome = when (hit) {
        is DropHit.Action -> DropOutcome.RunAction(hit.key)
        is DropHit.Folder -> moving(paths(), draggedFolders(), hit.path)
        is DropHit.Here -> moving(paths(), draggedFolders(), hit.path)
        DropHit.Nothing -> DropOutcome.GoBack()
    }

    private fun moving(paths: List<String>, draggedFolders: Collection<String>, folder: String): DropOutcome {
        if (draggedFolders.any { isSameOrInside(folder, it) }) return DropOutcome.GoBack(refused = true)
        val moving = paths.filter { it.substringBeforeLast('/', "") != folder }
        return if (moving.isEmpty()) DropOutcome.GoBack() else DropOutcome.MoveTo(folder, moving)
    }

    /**
     * What it takes to put a move back: for each folder things left, where they are now. Null if that wouldn't restore
     * things exactly, which it can't if anything failed or took a new name to fit.
     */
    fun undoPlan(
        ok: Boolean,
        renamed: Int,
        movedFrom: List<String>,
        outputs: List<String>,
        folder: String
    ): Map<String, List<String>>? {
        if (!ok || renamed > 0 || movedFrom.isEmpty()) return null
        val arrived = outputs.toHashSet()
        val back = LinkedHashMap<String, MutableList<String>>()
        for (from in movedFrom) {
            val now = folder + "/" + from.substringAfterLast('/')
            if (now !in arrived) return null
            back.getOrPut(from.substringBeforeLast('/', "")) { ArrayList() }.add(now)
        }
        return back
    }

    /**
     * How fast a list scrolls by itself for a finger at [y], from -1 at the top edge to 1 at the bottom. Squared, so the
     * zone's inner end creeps and a person can still aim at something near the edge.
     *
     * @param top where the list starts on screen.
     * @param bottom where it stops being visible, which is the top of a floating bar if there is one.
     * @param floatingBelow the finger has gone past [bottom] onto the bar. It's choosing an action there, so nothing moves.
     */
    fun autoScrollRate(y: Float, top: Float, bottom: Float, zone: Float, floatingBelow: Boolean = false): Float {
        if (zone <= 0f || bottom <= top) return 0f
        val fromTop = (top + zone - y) / zone
        val fromBottom = (y - (bottom - zone)) / zone
        return when {
            y < top -> 0f
            fromTop > 0f -> -(fromTop * fromTop)
            y > bottom -> if (floatingBelow) 0f else 1f
            fromBottom > 0f -> fromBottom * fromBottom
            else -> 0f
        }
    }
}

/**
 * Says when a finger has stayed on one thing long enough, once for each time it arrives there. Leaving and coming back
 * starts again, and so does moving while it's there, so carrying things across a folder on the way somewhere else never
 * opens it, however slowly that's done.
 */
internal class HoverClock(private val waitMillis: Long = SpringLoadMillis) {
    private var current: Any? = null
    private var since = 0L
    private var fired = false
    private var resting = false
    private var restX = 0f
    private var restY = 0f
    private var stayX = 0f
    private var stayY = 0f

    /**
     * Something has just opened under the finger. Whatever's there now isn't what the person was aiming at, and things move
     * for real, so nothing else opens until the finger has gone somewhere. Otherwise a finger left on Back climbs to the top.
     */
    fun restAt(x: Float, y: Float) {
        resting = true
        restX = x
        restY = y
    }

    /**
     * @param paused the list is scrolling under the finger, so the finger isn't really staying on anything.
     * @param x where the finger is, along with [y], for [restAt]. It has to go further than [slop] to count as having left.
     * @param stillSlop how far a finger can wander and still count as staying put. Hands aren't perfectly steady.
     * @return true on the one call where the wait is over.
     */
    fun update(key: Any?, nowMillis: Long, paused: Boolean = false, x: Float = 0f, y: Float = 0f, slop: Float = 0f, stillSlop: Float = Float.POSITIVE_INFINITY): Boolean {
        if (resting) {
            val dx = x - restX
            val dy = y - restY
            if (dx * dx + dy * dy <= slop * slop) {
                current = null
                fired = false
                return false
            }
            resting = false
        }
        if (key != current) {
            current = key
            since = nowMillis
            fired = false
            stayX = x
            stayY = y
            return false
        }
        if (key == null || fired) return false
        val wanderX = x - stayX
        val wanderY = y - stayY
        if (paused || wanderX * wanderX + wanderY * wanderY > stillSlop * stillSlop) {
            since = nowMillis
            stayX = x
            stayY = y
            return false
        }
        if (nowMillis - since < waitMillis) return false
        fired = true
        return true
    }
}

/**
 * Which of what's on screen fly, and in what order: the one being held, then whatever's nearest it. [visible] is what the
 * list has on screen, so this is never more than a few dozen, whatever's selected. At most [limit].
 */
internal fun <T> flyingOrder(held: T, visible: List<T>, distance: (T) -> Float, limit: Int): List<T> {
    val others = visible.filter { it != held }.sortedBy(distance)
    return (listOf(held) + others).take(limit)
}

/**
 * The positions nearest [center] in a list of [size] that [wanted] accepts, nearest first, up to [limit]. It walks outward,
 * so with a dense selection it looks at about as many as it returns, and only a thin selection in a long list walks all of
 * it. Not the main thread's job at those sizes: see [DragSession].
 */
internal fun nearestOutward(size: Int, center: Int, limit: Int, wanted: (Int) -> Boolean): IntArray {
    val found = IntArray(limit.coerceIn(0, size))
    var count = 0
    var below = center - 1
    var above = center + 1
    while (count < found.size && (below >= 0 || above < size)) {
        val takeBelow = above >= size || (below >= 0 && center - below <= above - center)
        val at = if (takeBelow) below-- else above++
        if (wanted(at)) found[count++] = at
    }
    return found.copyOf(count)
}
