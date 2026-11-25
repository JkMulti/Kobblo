package com.kobblo.gallery.file.explorer.gallery

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.explorer.NaturalOrder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One date heading and the files under it. */
class GallerySection(
    val title: String,
    val files: List<FileItem>,
    /** Where this section starts in [GalleryFeed.files]. */
    val firstFileIndex: Int
)

/**
 * A feed laid out for the grid: date sections over one flat list. The grid's items run header, tiles,
 * header, tiles, and one footer, so everything that maps between a grid index and a file works from
 * the section starts here rather than scanning.
 */
class GalleryFeed(val sections: List<GallerySection>, val files: List<FileItem>) {
    val photoCount: Int = files.count { it.type == "image" }
    val videoCount: Int = files.count { it.type == "video" }

    /** The grid index of each section's header, ascending. */
    private val headerIndexes = IntArray(sections.size) { sections[it].firstFileIndex + it }

    /** Headers, tiles, and the footer. */
    val gridItemCount: Int = files.size + sections.size + 1

    /** The section a grid index falls in. The footer counts as the last section. */
    fun sectionIndexForGridIndex(gridIndex: Int): Int {
        if (sections.isEmpty()) return -1
        val found = headerIndexes.binarySearch(gridIndex)
        return if (found >= 0) found else (-found - 2).coerceIn(0, sections.lastIndex)
    }

    /** The file at a grid index, or -1 for a header or the footer. */
    fun fileIndexForGridIndex(gridIndex: Int): Int {
        if (gridIndex < 0 || sections.isEmpty()) return -1
        val section = sectionIndexForGridIndex(gridIndex)
        if (gridIndex == headerIndexes[section]) return -1
        val fileIndex = gridIndex - section - 1
        return if (fileIndex in files.indices) fileIndex else -1
    }

    fun gridIndexForFileIndex(fileIndex: Int): Int {
        var low = 0
        var high = sections.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (sections[mid].firstFileIndex <= fileIndex) low = mid else high = mid - 1
        }
        return fileIndex + low + 1
    }

    companion object {
        val Empty = GalleryFeed(emptyList(), emptyList())
    }
}

/** The words a feed's headers use that aren't derived from a date. */
data class SectionLabels(val today: String, val yesterday: String)

/**
 * Recent media is grouped by day and everything older by month. A library of 100,000 is usually
 * thousands of files in a row from one month, so each file first tries the date range it landed in
 * last time, and the formatters only run when the feed crosses into a new heading.
 */
fun buildGalleryFeed(
    files: List<FileItem>,
    labels: SectionLabels,
    nowMillis: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault(),
    zone: TimeZone = TimeZone.getDefault()
): GalleryFeed {
    if (files.isEmpty()) return GalleryFeed.Empty
    val calendar = Calendar.getInstance(zone, locale)
    calendar.timeInMillis = nowMillis
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    val startOfToday = calendar.timeInMillis
    val thisYear = calendar.get(Calendar.YEAR)
    calendar.add(Calendar.DAY_OF_MONTH, -1)
    val startOfYesterday = calendar.timeInMillis
    calendar.add(Calendar.DAY_OF_MONTH, -5)
    val startOfWeek = calendar.timeInMillis

    val weekday = SimpleDateFormat("EEEE", locale).apply { timeZone = zone }
    val monthThisYear = SimpleDateFormat("LLLL", locale).apply { timeZone = zone }
    val monthAndYear = SimpleDateFormat("LLLL yyyy", locale).apply { timeZone = zone }

    val groups = LinkedHashMap<String, ArrayList<FileItem>>()
    var rangeStart = Long.MAX_VALUE
    var rangeEnd = Long.MIN_VALUE
    var group: ArrayList<FileItem>? = null
    // The grid keys tiles by path and crashes on a repeat, so this is the one place every feed passes
    // through that guarantees there can't be one, whatever the list was built from.
    val seenPaths = HashSet<String>(files.size * 2)

    for (file in files) {
        if (!seenPaths.add(file.path)) continue
        val moment = file.lastModified
        if (moment < rangeStart || moment >= rangeEnd) {
            val title: String
            when {
                moment >= startOfToday -> {
                    title = labels.today
                    rangeStart = startOfToday
                    rangeEnd = Long.MAX_VALUE
                }
                moment >= startOfYesterday -> {
                    title = labels.yesterday
                    rangeStart = startOfYesterday
                    rangeEnd = startOfToday
                }
                moment >= startOfWeek -> {
                    calendar.timeInMillis = moment
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    rangeStart = calendar.timeInMillis
                    calendar.add(Calendar.DAY_OF_MONTH, 1)
                    rangeEnd = calendar.timeInMillis
                    title = weekday.format(Date(moment))
                }
                else -> {
                    calendar.timeInMillis = moment
                    val year = calendar.get(Calendar.YEAR)
                    calendar.set(Calendar.DAY_OF_MONTH, 1)
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    rangeStart = calendar.timeInMillis
                    calendar.add(Calendar.MONTH, 1)
                    // The last days of this month may belong to the headings above instead.
                    rangeEnd = minOf(calendar.timeInMillis, startOfWeek)
                    title = (if (year == thisYear) monthThisYear else monthAndYear).format(Date(moment))
                }
            }
            group = groups.getOrPut(title) { ArrayList() }
        }
        group!!.add(file)
    }

    val sections = ArrayList<GallerySection>(groups.size)
    val ordered = ArrayList<FileItem>(files.size)
    for ((title, members) in groups) {
        sections.add(GallerySection(title, members, ordered.size))
        ordered.addAll(members)
    }
    return GalleryFeed(sections, ordered)
}

/** How one album's photos line up. Newest first is what every album starts as. */
enum class PhotoSort { NEWEST, OLDEST, NAME, SIZE }

/**
 * An album's members in the order it is sorted by. [files] arrive newest first, so [PhotoSort.NEWEST] costs
 * nothing, and the sort is stable and nearly linear on a list already almost in order, so oldest first stays
 * cheap. An album can hold 100,000 files, so this runs off the main thread.
 */
fun orderedForAlbum(files: List<FileItem>, sort: PhotoSort): List<FileItem> = when (sort) {
    PhotoSort.NEWEST -> files
    PhotoSort.OLDEST -> files.asReversed().sortedBy { it.lastModified }
    PhotoSort.NAME -> files.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    PhotoSort.SIZE -> files.sortedWith { a, b ->
        val size = b.sizeBytes.compareTo(a.sizeBytes)
        if (size != 0) size else NaturalOrder.compare(a.name, b.name)
    }
}

/** A feed for an album, with date headings only where the order is by date. */
fun buildAlbumFeed(files: List<FileItem>, sort: PhotoSort, labels: SectionLabels): GalleryFeed {
    val ordered = orderedForAlbum(files, sort)
    return if (sort == PhotoSort.NEWEST || sort == PhotoSort.OLDEST) buildGalleryFeed(ordered, labels) else buildFlatFeed(ordered)
}

/**
 * The same feed with one untitled section: sorted by name or size, neighbours can be years apart, and date
 * headings would break the grid into a heading per photo. The grid draws no heading for an empty title.
 */
fun buildFlatFeed(files: List<FileItem>): GalleryFeed {
    if (files.isEmpty()) return GalleryFeed.Empty
    val seenPaths = HashSet<String>(files.size * 2)
    val unique = files.filter { seenPaths.add(it.path) }
    return GalleryFeed(listOf(GallerySection("", unique, 0)), unique)
}

/**
 * The selection after dragging across a run of files. [base] is what was selected before the drag
 * began, so moving back over a file restores it. Locked files stay out, since selecting one normally
 * asks for the password first.
 */
fun rangeSelection(
    base: Set<Int>,
    files: List<FileItem>,
    anchorIndex: Int,
    currentIndex: Int,
    selecting: Boolean
): Set<Int> {
    val from = minOf(anchorIndex, currentIndex).coerceAtLeast(0)
    val to = maxOf(anchorIndex, currentIndex).coerceAtMost(files.lastIndex)
    val result = HashSet<Int>(base)
    for (index in from..to) {
        val file = files[index]
        if (file.isLocked) continue
        if (selecting) result.add(file.id) else result.remove(file.id)
    }
    return result
}

/** A date header's mark: selects its whole section, or clears it if it was all selected already. */
fun toggleSection(base: Set<Int>, section: GallerySection): Set<Int> {
    val selectable = section.files.filterNot { it.isLocked }
    val result = HashSet<Int>(base)
    if (selectable.isNotEmpty() && selectable.all { it.id in base }) {
        selectable.forEach { result.remove(it.id) }
    } else {
        selectable.forEach { result.add(it.id) }
    }
    return result
}

/** Whether a header's mark shows as checked. */
fun isSectionSelected(selected: Set<Int>, section: GallerySection): Boolean {
    var any = false
    for (file in section.files) {
        if (file.isLocked) continue
        if (file.id !in selected) return false
        any = true
    }
    return any
}

/**
 * The thumbnail size for a column count. Sizes come in three steps rather than one per count, so a
 * pinch through neighbouring counts reuses bitmaps already in memory instead of decoding them again.
 */
fun thumbnailPx(gridWidthPx: Int, columns: Int): Int {
    val divisor = when {
        columns <= 2 -> 2
        columns <= 4 -> 3
        else -> 5
    }
    return gridWidthPx / divisor
}

/**
 * When a burst of change notifications should cause one reload: after a pause, or at most
 * [maxWaitMillis] in, but never sooner than [gapMillis] after the last one. The gap is what stops a long
 * import from keeping a large library reloading back to back.
 */
fun burstFireTime(
    firstEvent: Long,
    lastEvent: Long,
    quietMillis: Long,
    maxWaitMillis: Long,
    lastFire: Long = 0L,
    gapMillis: Long = 0L
): Long = maxOf(minOf(lastEvent + quietMillis, firstEvent + maxWaitMillis), lastFire + gapMillis)

/** How long to leave between reloads: reading a library takes longer the larger it is. */
fun reloadGapMillis(librarySize: Int): Long = (librarySize / 5_000) * 1_000L

/**
 * Files written straight to storage have a row with no dates until something scans them, and the system
 * may leave that for a long time. Asking for each once is enough, and a folder full of them is one
 * request rather than thousands, since a scan of thousands of paths keeps the media index busy and the
 * feed reloading for as long as it runs.
 */
object ScanRequests {
    private const val FolderThreshold = 50
    private const val MaxPaths = 200
    private val asked = HashSet<String>()

    fun nextToScan(unscanned: List<String>): List<String> = synchronized(asked) { next(unscanned, asked) }

    internal fun next(unscanned: List<String>, asked: MutableSet<String>): List<String> {
        val result = ArrayList<String>()
        for ((folder, files) in unscanned.groupBy { it.substringBeforeLast('/', "") }) {
            if (files.size >= FolderThreshold) {
                if (folder.isNotEmpty() && asked.add(folder)) result.add(folder)
            } else {
                for (file in files) {
                    if (result.size >= MaxPaths) break
                    if (asked.add(file)) result.add(file)
                }
            }
        }
        return result
    }
}
