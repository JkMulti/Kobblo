package com.kobblo.gallery.file.explorer.gallery.similar

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.gallery.isCameraFile
import java.util.Locale
import kotlin.math.abs

/** What a photo looks like at a glance: its hash, plus enough to rule out lookalikes that aren't. */
data class PhotoPrint(
    val hash: Long,
    val width: Int,
    val height: Int,
    /** Average color, each channel 0 to 255. */
    val red: Int,
    val green: Int,
    val blue: Int
)

/** A difference hash. Comparing neighbouring pixels survives resizing, recompression and brightness changes. */
object DifferenceHash {
    const val SampleWidth = 9
    const val SampleHeight = 8

    /** [luma] is [SampleWidth] by [SampleHeight] brightness values, row by row. */
    fun of(luma: IntArray): Long {
        require(luma.size == SampleWidth * SampleHeight)
        var hash = 0L
        var bit = 0
        for (y in 0 until SampleHeight) {
            val row = y * SampleWidth
            for (x in 0 until SampleWidth - 1) {
                if (luma[row + x] > luma[row + x + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Blank walls and solid screenshots hash to almost nothing, so they'd match each other for no reason. */
    fun isTooPlain(hash: Long): Boolean {
        val bits = java.lang.Long.bitCount(hash)
        return bits <= 4 || bits >= 60
    }
}

/** How close two hashes must be. Strict, because the result of a match is a suggestion to delete. */
const val SimilarDistance = 5
private const val MaxAspectDifference = 0.035f
private const val MaxColorDifference = 36

/** Whether two prints look like the same picture. */
fun looksSame(a: PhotoPrint, b: PhotoPrint): Boolean {
    if (DifferenceHash.distance(a.hash, b.hash) > SimilarDistance) return false
    if (a.height > 0 && b.height > 0 && a.width > 0 && b.width > 0) {
        val ratioA = a.width.toFloat() / a.height
        val ratioB = b.width.toFloat() / b.height
        if (abs(ratioA - ratioB) > MaxAspectDifference) return false
    }
    return abs(a.red - b.red) <= MaxColorDifference &&
        abs(a.green - b.green) <= MaxColorDifference &&
        abs(a.blue - b.blue) <= MaxColorDifference
}

/** Groups connected through "these are the same", smallest index first. */
private class Unions(size: Int) {
    private val parent = IntArray(size) { it }

    fun find(item: Int): Int {
        var i = item
        while (parent[i] != i) {
            parent[i] = parent[parent[i]]
            i = parent[i]
        }
        return i
    }

    fun union(a: Int, b: Int) {
        val rootA = find(a)
        val rootB = find(b)
        if (rootA != rootB) parent[maxOf(rootA, rootB)] = minOf(rootA, rootB)
    }
}

/**
 * Finds every pair within [SimilarDistance] without comparing every pair. Six bands cover the 64 bits,
 * and two hashes differing in at most five bits can't differ in all six, so any true match shares a
 * band and lands in the same bucket.
 */
private val BandWidths = intArrayOf(11, 11, 11, 11, 10, 10)

private inline fun forEachBandKey(hash: Long, action: (Long) -> Unit) {
    var start = 0
    for (band in BandWidths.indices) {
        val width = BandWidths[band]
        val value = (hash ushr start) and ((1L shl width) - 1)
        action((band.toLong() shl 32) or value)
        start += width
    }
}

/**
 * Sorts photos into groups of duplicates. [contents] holds a checksum for files that share a size with
 * another file. Files with the same checksum are the same file and need no hash at all to match.
 *
 * Returns index lists into [prints], each with two or more members.
 */
fun groupPhotos(prints: List<PhotoPrint?>, contents: List<String?>): List<List<Int>> {
    val unions = Unions(prints.size)

    val sameContent = HashMap<String, Int>()
    contents.forEachIndexed { index, checksum ->
        if (checksum != null) {
            val first = sameContent.getOrPut(checksum) { index }
            if (first != index) unions.union(first, index)
        }
    }

    val buckets = HashMap<Long, ArrayList<Int>>()
    prints.forEachIndexed { index, print ->
        if (print == null || DifferenceHash.isTooPlain(print.hash)) return@forEachIndexed
        forEachBandKey(print.hash) { key ->
            val bucket = buckets.getOrPut(key) { ArrayList(2) }
            for (other in bucket) {
                if (unions.find(other) != unions.find(index) && looksSame(print, prints[other]!!)) {
                    unions.union(other, index)
                }
            }
            bucket.add(index)
        }
    }

    val groups = LinkedHashMap<Int, ArrayList<Int>>()
    for (index in prints.indices) groups.getOrPut(unions.find(index)) { ArrayList(1) }.add(index)
    return groups.values.filter { it.size > 1 }
}

private val CopySuffix = Regex("(?i)(\\s*\\(\\d+\\)|[ _-]copy([ _-]?\\d+)?|[ _-]\\d+)$")

/** Whether a name reads as a copy of something else, like `photo (1).jpg` or `photo copy.jpg`. */
fun looksLikeCopyName(name: String): Boolean {
    val base = name.substringBeforeLast('.', name)
    return CopySuffix.containsMatchIn(base) || base.lowercase(Locale.ROOT).contains("copy")
}

/**
 * The order to prefer duplicates in, best first: favorites are never second-guessed, then the most
 * pixels, then the largest file, and among equals the one in the camera's folder with an original's
 * name and the earliest date.
 */
fun bestFirst(files: List<FileItem>, sizes: (FileItem) -> Pair<Int, Int>): List<FileItem> =
    files.sortedWith(
        compareByDescending<FileItem> { it.isPinned }
            .thenByDescending { sizes(it).let { (w, h) -> w.toLong() * h } }
            .thenByDescending { it.sizeBytes }
            .thenBy { looksLikeCopyName(it.name) }
            .thenByDescending { isCameraFile(it.path, it.name) }
            .thenBy { it.lastModified }
            .thenBy { it.path }
    )

/** One set of duplicates under review. [remove] holds the ids marked to go to Recently Deleted. */
data class DuplicateGroup(
    val members: List<FileItem>,
    /** Every member is the same file, byte for byte. */
    val isExact: Boolean,
    val remove: Set<Int>
) {
    val keeper: FileItem? get() = members.firstOrNull { it.id !in remove }
    val freeableBytes: Long get() = members.filter { it.id in remove }.sumOf { it.sizeBytes }

    /** Marks or unmarks one photo. At least one always stays, since keeping none is never what this is for. */
    fun toggled(id: Int): DuplicateGroup {
        val next = if (id in remove) remove - id else remove + id
        return if (next.size >= members.size) this else copy(remove = next)
    }
}

/**
 * Turns index groups into groups to review, with the best of each first. A file is only ever marked when it's
 * been proven the same, byte for byte, as a file that stays. Looking alike isn't proof: a burst of photos can
 * look alike with only one of them in focus, and the person has to look before any of those goes.
 */
fun buildReview(
    files: List<FileItem>,
    groups: List<List<Int>>,
    contents: List<String?>,
    sizes: (FileItem) -> Pair<Int, Int>
): List<DuplicateGroup> =
    groups.map { indexes ->
        val checksumOf = indexes.associate { files[it].id to contents[it] }
        val members = bestFirst(indexes.map { files[it] }, sizes)
        val checksums = indexes.map { contents[it] }
        val exact = checksums.all { it != null } && checksums.toSet().size == 1
        val keptChecksums = HashSet<String>()
        val marked = HashSet<Int>()
        for (member in members) {
            val checksum = checksumOf[member.id] ?: continue
            if (!keptChecksums.add(checksum)) marked.add(member.id)
        }
        DuplicateGroup(members, exact, marked)
    }.sortedWith(compareByDescending<DuplicateGroup> { it.freeableBytes }.thenByDescending { group -> group.members.sumOf { it.sizeBytes } })

/** Two sizes the same is the only cheap hint that files might be identical, and the only ones worth reading whole. */
fun filesSharingSize(files: List<FileItem>): Set<Int> {
    val seen = HashMap<Long, Int>()
    for (file in files) if (file.sizeBytes > 0L) seen[file.sizeBytes] = (seen[file.sizeBytes] ?: 0) + 1
    return files.filter { (seen[it.sizeBytes] ?: 0) > 1 }.map { it.id }.toSet()
}
