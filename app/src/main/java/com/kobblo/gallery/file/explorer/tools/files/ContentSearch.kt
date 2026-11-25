package com.kobblo.gallery.file.explorer.tools.files

import com.kobblo.gallery.file.explorer.tools.editor.TextCodec
import java.io.File

/** One line that matched. [column] is where the match starts, so a screen can show it in context. */
data class ContentMatch(val path: String, val line: Int, val column: Int, val text: String)

/** Searches inside text files. Written without Android types, so it's tested on the JVM. */
object ContentSearcher {
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val SNIFF_BYTES = 4096
    private const val MAX_MATCHES_PER_FILE = 50

    /**
     * Walks [root] depth first and reports matches as it finds them. Hidden files and folders are
     * skipped, as are files too big or that aren't text.
     *
     * @param onFile receives how many files have been read so far.
     * @param checkCancelled is called for each file, and expected to throw to stop.
     * @param onMatch returns false to stop the search, once enough has been found.
     */
    fun search(
        root: File,
        query: String,
        onFile: (Int) -> Unit = {},
        checkCancelled: () -> Unit = {},
        skip: (File) -> Boolean = { false },
        onMatch: (ContentMatch) -> Boolean
    ) {
        if (query.isEmpty()) return
        var filesRead = 0
        val pending = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val dir = pending.removeLast()
            val children = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: continue
            for (child in children) {
                if (child.name.startsWith(".") || skip(child)) continue
                checkCancelled()
                if (child.isDirectory) {
                    pending.addLast(child)
                    continue
                }
                if (!isSearchable(child)) continue
                filesRead++
                onFile(filesRead)
                if (!searchFile(child, query, onMatch)) return
            }
        }
    }

    private fun isSearchable(file: File): Boolean {
        val length = file.length()
        if (length == 0L || length > MAX_FILE_BYTES) return false
        return try {
            val sample = ByteArray(minOf(length, SNIFF_BYTES.toLong()).toInt())
            file.inputStream().use { input -> input.read(sample) }
            TextCodec.looksLikeText(sample)
        } catch (_: Exception) {
            false
        }
    }

    private fun searchFile(file: File, query: String, onMatch: (ContentMatch) -> Boolean): Boolean {
        val text = try {
            TextCodec.decode(file.readBytes()).text
        } catch (_: Exception) {
            return true
        }
        var found = 0
        var lineNumber = 1
        var start = 0
        while (start <= text.length && found < MAX_MATCHES_PER_FILE) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val column = indexOfIgnoreCase(text, query, start, end)
            if (column >= 0) {
                found++
                val match = ContentMatch(file.path, lineNumber, column - start, snippet(text, start, end, column))
                if (!onMatch(match)) return false
            }
            lineNumber++
            start = end + 1
        }
        return true
    }

    private fun indexOfIgnoreCase(text: String, query: String, from: Int, to: Int): Int {
        var i = from
        val last = to - query.length
        while (i <= last) {
            if (text.regionMatches(i, query, 0, query.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }

    /** A long line is cut around the match, since the match is the part someone wants to see. */
    private fun snippet(text: String, start: Int, end: Int, at: Int): String {
        val from = maxOf(start, at - 40)
        val to = minOf(end, at + 120)
        return buildString {
            if (from > start) append('…')
            append(text, from, to)
            if (to < end) append('…')
        }.trim()
    }
}

/** A line that differs between two texts. */
data class DiffLine(val kind: Kind, val text: String, val oldLine: Int?, val newLine: Int?) {
    enum class Kind { SAME, REMOVED, ADDED }
}

object LineDiff {
    /** More lines than this makes the table below too big to hold in memory on a phone. */
    private const val MAX_LINES = 4000

    fun isTooLarge(oldLines: Int, newLines: Int): Boolean = oldLines > MAX_LINES || newLines > MAX_LINES

    /** The classic longest common subsequence. Unchanged lines at both ends are trimmed off first. */
    fun diff(old: List<String>, new: List<String>): List<DiffLine> {
        var head = 0
        while (head < old.size && head < new.size && old[head] == new[head]) head++
        var oldEnd = old.size
        var newEnd = new.size
        while (oldEnd > head && newEnd > head && old[oldEnd - 1] == new[newEnd - 1]) {
            oldEnd--
            newEnd--
        }
        val rows = oldEnd - head
        val columns = newEnd - head
        val table = Array(rows + 1) { IntArray(columns + 1) }
        for (r in rows - 1 downTo 0) {
            for (c in columns - 1 downTo 0) {
                table[r][c] = if (old[head + r] == new[head + c]) {
                    table[r + 1][c + 1] + 1
                } else {
                    maxOf(table[r + 1][c], table[r][c + 1])
                }
            }
        }
        val out = ArrayList<DiffLine>()
        for (i in 0 until head) out += DiffLine(DiffLine.Kind.SAME, old[i], i + 1, i + 1)
        var r = 0
        var c = 0
        while (r < rows || c < columns) {
            when {
                r < rows && c < columns && old[head + r] == new[head + c] -> {
                    out += DiffLine(DiffLine.Kind.SAME, old[head + r], head + r + 1, head + c + 1)
                    r++
                    c++
                }
                // Removed before added, so a changed line reads as the old one, then the new one.
                r < rows && (c == columns || table[r + 1][c] >= table[r][c + 1]) -> {
                    out += DiffLine(DiffLine.Kind.REMOVED, old[head + r], head + r + 1, null)
                    r++
                }
                else -> {
                    out += DiffLine(DiffLine.Kind.ADDED, new[head + c], null, head + c + 1)
                    c++
                }
            }
        }
        for (i in oldEnd until old.size) {
            out += DiffLine(DiffLine.Kind.SAME, old[i], i + 1, newEnd + (i - oldEnd) + 1)
        }
        return out
    }

    /** Only what changed, with [context] unchanged lines kept around each change. */
    fun changesOnly(lines: List<DiffLine>, context: Int = 2): List<DiffLine> {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { index, line ->
            if (line.kind != DiffLine.Kind.SAME) {
                for (i in maxOf(0, index - context)..minOf(lines.lastIndex, index + context)) keep[i] = true
            }
        }
        return lines.filterIndexed { index, _ -> keep[index] }
    }
}
