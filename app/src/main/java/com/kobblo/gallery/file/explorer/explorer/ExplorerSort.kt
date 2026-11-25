package com.kobblo.gallery.file.explorer.explorer

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.SortMode

/**
 * Orders names the way a person reads them, so `file2` comes before `file10`. A folder of 10,000 names
 * is sorted on every open, so this allocates nothing, and it falls back to the raw text only when two
 * names read the same, which keeps a sort's order the same from one run to the next.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca in '0'..'9' && cb in '0'..'9') {
                var startA = i
                while (startA < a.length && a[startA] == '0') startA++
                var startB = j
                while (startB < b.length && b[startB] == '0') startB++
                var endA = startA
                while (endA < a.length && a[endA] in '0'..'9') endA++
                var endB = startB
                while (endB < b.length && b[endB] in '0'..'9') endB++
                // With the zeros gone, more digits means a bigger number, however long the run is.
                val lengthA = endA - startA
                val lengthB = endB - startB
                if (lengthA != lengthB) return lengthA - lengthB
                for (k in 0 until lengthA) {
                    val difference = a[startA + k] - b[startB + k]
                    if (difference != 0) return difference
                }
                i = endA
                j = endB
                continue
            }
            if (ca != cb) {
                val foldedA = fold(ca)
                val foldedB = fold(cb)
                if (foldedA != foldedB) return foldedA - foldedB
            }
            i++
            j++
        }
        val left = (a.length - i) - (b.length - j)
        return if (left != 0) left else a.compareTo(b)
    }

    private fun fold(c: Char): Char =
        if (c.code < 128) {
            if (c in 'A'..'Z') c + 32 else c
        } else {
            Character.toLowerCase(Character.toUpperCase(c))
        }
}

/** How a folder's files line up: folders first, then whatever the folder is sorted by. */
object ExplorerSort {
    private val byName = Comparator<FileItem> { a, b -> NaturalOrder.compare(a.name, b.name) }

    private val byKind = Comparator<FileItem> { a, b ->
        val kinds = extension(a.name).compareTo(extension(b.name), ignoreCase = true)
        if (kinds != 0) kinds else NaturalOrder.compare(a.name, b.name)
    }

    /**
     * Each sort reads first in the direction people want most: A to Z, newest, largest. [reversed] flips
     * it, and folders stay on top either way, because they're how you get anywhere.
     */
    fun comparator(mode: SortMode, reversed: Boolean): Comparator<FileItem> {
        val order: Comparator<FileItem> = when (mode) {
            SortMode.ALPHABETICAL -> byName
            SortMode.DATE -> Comparator { a, b -> b.lastModified.compareTo(a.lastModified) }
            SortMode.SIZE -> Comparator { a, b -> b.sizeBytes.compareTo(a.sizeBytes) }
            SortMode.KIND -> byKind
        }
        val directed = if (reversed) order.reversed() else order
        // Ties read A to Z in both directions, or a reversed list would shuffle its equal dates.
        val settled = if (mode == SortMode.ALPHABETICAL || mode == SortMode.KIND) directed else directed.then(byName)
        return Comparator { a, b ->
            val aFolder = a.type == "folder"
            val bFolder = b.type == "folder"
            if (aFolder != bFolder) (if (aFolder) -1 else 1) else settled.compare(a, b)
        }
    }

    private fun extension(name: String): String = name.substringAfterLast('.', "")

    /** Whether a file belongs under a folder's filter, in the names the filter sheet uses for them. */
    fun matchesFilter(file: FileItem, filter: String): Boolean = when (filter) {
        "all" -> true
        "image" -> file.type == "image"
        "video" -> file.type == "video"
        "audio", "music" -> file.type == "audio"
        "media" -> file.type == "image" || file.type == "video" || file.type == "audio"
        "doc" -> file.type == "doc" || file.kind == "doc"
        "apk" -> file.type == "apk"
        "archive" -> file.type == "archive"
        else -> false
    }
}
