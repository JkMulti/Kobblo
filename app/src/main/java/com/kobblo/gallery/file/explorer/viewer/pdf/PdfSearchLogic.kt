package com.kobblo.gallery.file.explorer.viewer.pdf

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One match: the rectangles it covers on its page, in points, as a match can wrap across lines. */
internal class PdfHit(val page: Int, val rects: FloatArray) {
    val firstRect: PdfRect
        get() = PdfRect(rects[0], rects[1], rects[2], rects[3])
}

/** A page's text and each character's rectangle. A space between words has no rectangle of its own. */
internal class PdfPageChars(val text: String, val rects: FloatArray)

/** Finding words in text extracted from a page, for the devices whose PDF renderer can't search. */
internal object PdfTextMatch {
    /** Extraction reduces every run of whitespace to one space, so what's typed has to read the same way. */
    fun normalizeQuery(query: String): String =
        query.trim().replace(WhitespaceRun, " ")

    private val WhitespaceRun = Regex("\\s+")

    /**
     * Where the query is in the text, ignoring case and spaces. Extraction guesses where words end from the gaps between
     * letters, and tight or turned text can come out as "co rn er", which a reader sees as one word and would search for
     * as one. The range covers the first character to the last, without overlapping itself.
     */
    fun find(text: String, query: String): List<IntRange> {
        val wanted = query.filterNot(Char::isWhitespace)
        if (wanted.isEmpty()) return emptyList()
        val places = IntArray(text.length)
        val compact = StringBuilder(text.length)
        for (at in text.indices) {
            if (!text[at].isWhitespace()) {
                places[compact.length] = at
                compact.append(text[at])
            }
        }
        val found = ArrayList<IntRange>()
        var from = 0
        while (from <= compact.length - wanted.length) {
            val at = compact.indexOf(wanted, from, ignoreCase = true)
            if (at < 0) break
            found.add(places[at]..places[at + wanted.length - 1])
            from = at + wanted.length
        }
        return found
    }

    /**
     * The rectangles for the characters from [start] up to [end], joined up along each line. Four floats
     * each: left, top, right, bottom.
     */
    fun rectsFor(chars: FloatArray, start: Int, end: Int): FloatArray {
        val out = ArrayList<Float>()
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        var open = false
        for (index in start until end) {
            val at = index * 4
            if (at + 3 >= chars.size) break
            val l = chars[at]
            val t = chars[at + 1]
            val r = chars[at + 2]
            val b = chars[at + 3]
            if (r <= l || b <= t) continue
            val sameLine = open && abs((t + b) - (top + bottom)) / 2f < (bottom - top) / 2f
            if (sameLine) {
                left = min(left, l)
                top = min(top, t)
                right = max(right, r)
                bottom = max(bottom, b)
            } else {
                if (open) out.addRect(left, top, right, bottom)
                left = l
                top = t
                right = r
                bottom = b
                open = true
            }
        }
        if (open) out.addRect(left, top, right, bottom)
        return out.toFloatArray()
    }

    private fun MutableList<Float>.addRect(left: Float, top: Float, right: Float, bottom: Float) {
        add(left)
        add(top)
        add(right)
        add(bottom)
    }
}

/**
 * Every match found so far, in document order, however the scan's order wrapped round, plus which one is
 * being looked at. Results land in here as they arrive, so moving between them works during the scan.
 */
internal class PdfSearchResults {
    private val hits = ArrayList<PdfHit>()
    private val byPage = HashMap<Int, MutableList<PdfHit>>()

    val count: Int get() = hits.size
    var currentIndex: Int = -1
        private set

    val current: PdfHit? get() = hits.getOrNull(currentIndex)

    fun onPage(page: Int): List<PdfHit> = byPage[page].orEmpty()

    /** Adds a page's matches, in place, keeping what's current the same match. */
    fun add(page: Int, matches: List<PdfHit>) {
        if (matches.isEmpty()) return
        val at = insertionPoint(page)
        hits.addAll(at, matches)
        byPage.getOrPut(page) { ArrayList() }.addAll(matches)
        if (currentIndex >= at) currentIndex += matches.size
    }

    private fun insertionPoint(page: Int): Int {
        var low = 0
        var high = hits.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (hits[mid].page <= page) low = mid + 1 else high = mid
        }
        return low
    }

    /** The first match at or after this page, wrapping round. */
    fun selectFrom(page: Int): PdfHit? {
        if (hits.isEmpty()) return null
        var low = 0
        var high = hits.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (hits[mid].page < page) low = mid + 1 else high = mid
        }
        currentIndex = if (low < hits.size) low else 0
        return current
    }

    fun next(fromPage: Int): PdfHit? {
        if (hits.isEmpty()) return null
        if (currentIndex < 0) return selectFrom(fromPage)
        currentIndex = (currentIndex + 1) % hits.size
        return current
    }

    fun previous(fromPage: Int): PdfHit? {
        if (hits.isEmpty()) return null
        if (currentIndex < 0) selectFrom(fromPage)
        currentIndex = (currentIndex - 1 + hits.size) % hits.size
        return current
    }

    fun clear() {
        hits.clear()
        byPage.clear()
        currentIndex = -1
    }
}

/** A place on a page that goes somewhere when tapped. */
internal class PdfLink(val rect: PdfRect, val page: Int, val uri: String?)

internal object PdfLinkGeometry {
    /**
     * A rectangle in the file's own space, origin at the lower left and from before the page's own rotation, to how
     * it's shown: the same top-left points the renderer and search use. [width] and [height] are the page before turning.
     */
    fun toDisplay(llx: Float, lly: Float, urx: Float, ury: Float, width: Float, height: Float, rotation: Int): PdfRect =
        when (rotation.mod(360)) {
            90 -> PdfRect(lly, llx, ury, urx)
            180 -> PdfRect(width - urx, lly, width - llx, ury)
            270 -> PdfRect(height - ury, width - urx, height - lly, width - llx)
            else -> PdfRect(llx, height - ury, urx, height - lly)
        }

    /** The link under a tap, with a little forgiveness for a fingertip on a line of small print. */
    fun hit(links: List<PdfLink>, x: Float, y: Float, slop: Float): PdfLink? = links.firstOrNull { link ->
        x >= link.rect.left - slop && x <= link.rect.right + slop && y >= link.rect.top - slop && y <= link.rect.bottom + slop
    }

    /** What's safe to hand to another app from a file that could be from anywhere. */
    fun isOpenableUri(uri: String): Boolean {
        val scheme = uri.substringBefore(':', "").lowercase()
        return scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel"
    }
}

/** Where a character is on screen, given which way its text runs there. */
internal object PdfCharBox {
    /** Clockwise on screen: the page's own turn is clockwise, and a text's direction in the file counter-clockwise. */
    fun displayAngle(pageRotation: Int, textDirection: Int): Int = (pageRotation - textDirection).mod(360)

    /**
     * The box around a stretch of a line, measured from where its baseline starts, [from] and [to] along the text, and
     * up to [above] and down to [below] across it.
     */
    fun rect(angle: Int, x: Float, y: Float, from: Float, to: Float, above: Float, below: Float): PdfRect = when (angle) {
        90 -> PdfRect(x - below, y + from, x + above, y + to)
        180 -> PdfRect(x - to, y - below, x - from, y + above)
        270 -> PdfRect(x - above, y - to, x + below, y - from)
        else -> PdfRect(x + from, y - above, x + to, y + below)
    }
}
