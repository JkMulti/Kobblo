package com.kobblo.gallery.file.explorer.explorer.search

import com.kobblo.gallery.file.explorer.StorageCategory
import com.kobblo.gallery.file.explorer.fileops.escapeForLike
import java.util.Calendar
import java.util.Locale

/** What a search can be narrowed to. */
enum class SearchKind { Photos, Videos, Audio, Documents, Archives, Apps, Folders }

enum class SearchSize(val minBytes: Long, val maxBytes: Long) {
    Under1Mb(0L, 1L shl 20),
    To100Mb(1L shl 20, 100L shl 20),
    To1Gb(100L shl 20, 1L shl 30),
    Over1Gb(1L shl 30, Long.MAX_VALUE)
}

enum class SearchDate { Today, Week, Month, Year }

data class SearchFilters(
    val everywhere: Boolean = false,
    val kind: SearchKind? = null,
    val size: SearchSize? = null,
    val date: SearchDate? = null
) {
    /** Whether these say anything about the results, as opposed to where to look. */
    val narrows: Boolean get() = kind != null || size != null || date != null
}

/**
 * What was typed. Every word has to appear in the name, in any order, so "2024 invoice" finds
 * `Invoice-March-2024.pdf`. A `*` is only a separator, for hands used to `*.pdf`. Written without
 * Android types so it can be tested on the JVM.
 */
class SearchText(raw: String) {
    val tokens: List<String> = raw.lowercase(Locale.ROOT)
        .split(' ', '\t', '*')
        .filter { it.isNotEmpty() }
        .distinct()

    val isBlank: Boolean get() = tokens.isEmpty()

    fun matches(name: String): Boolean {
        for (token in tokens) if (!containsFolded(name, token)) return false
        return true
    }

    /**
     * Is [token], already lower case, in [name] in any case? This runs on every name in every folder that
     * gets looked through, so it allocates nothing, and takes the quick path for the ASCII nearly all
     * names are made of. It was 2 s of a pass over 100,000 names when it went through `contains`.
     */
    private fun containsFolded(name: String, token: String): Boolean = containsIgnoringCase(name, token)

    internal companion object {
        /** [token], already lower case, in [text] from position [from] on. */
        fun containsIgnoringCase(text: String, token: String, from: Int = 0): Boolean {
            val last = text.length - token.length
            if (last < from) return false
            val first = token[0]
            for (start in from..last) {
                if (!sameFolded(text[start], first)) continue
                var at = 1
                while (at < token.length && sameFolded(text[start + at], token[at])) at++
                if (at == token.length) return true
            }
            return false
        }

        private fun sameFolded(c: Char, lower: Char): Boolean = when {
            c == lower -> true
            c.code < 128 -> c in 'A'..'Z' && c.code + 32 == lower.code
            else -> c.lowercaseChar() == lower || c.uppercaseChar().lowercaseChar() == lower
        }
    }

    /**
     * How well [name] fits, lower being better: the whole name, then the start of it, then the start of
     * a word, then anywhere. What you were looking for is usually the file that begins with it.
     */
    fun rank(name: String): Int {
        val first = tokens.firstOrNull() ?: return 3
        val stem = name.substringBeforeLast('.', name)
        if (tokens.size == 1 && (stem.equals(first, ignoreCase = true) || name.equals(first, ignoreCase = true))) return 0
        if (name.startsWith(first, ignoreCase = true)) return 1
        val at = name.indexOf(first, ignoreCase = true)
        if (at > 0 && !name[at - 1].isLetterOrDigit()) return 2
        return 3
    }

    /** Where each word sits in [name], so a row can show why it matched. Overlaps are the caller's to merge. */
    fun ranges(name: String): List<IntRange> {
        val found = ArrayList<IntRange>(tokens.size)
        for (token in tokens) {
            var from = 0
            while (true) {
                val at = name.indexOf(token, from, ignoreCase = true)
                if (at < 0) break
                found.add(at until at + token.length)
                from = at + token.length
            }
        }
        return found.sortedBy { it.first }
    }
}

/** A selection for the system's file index, with its arguments in order. */
data class IndexSelection(val selection: String, val args: List<String>)

/**
 * The query that answers a search from the system's file index. The index holds every file written
 * since Android 11, and asking it takes milliseconds where walking the disk takes seconds at 100,000
 * files. The Kotlin side checks every row again, because a row the scanner hasn't reached has no size
 * or date yet, and because the index only ignores case for ASCII.
 */
object IndexSelectionBuilder {
    private const val FOLDER_FORMAT = 12289
    private const val NOT_MEDIA = "(media_type IS NULL OR media_type NOT IN (1, 2, 3))"

    /** Recently Deleted's own folder. Even with hidden files showing, what's in it isn't found by searching. */
    internal const val BIN_FOLDER = ".kobblo_trash"

    fun build(
        text: SearchText,
        filters: SearchFilters,
        scope: String?,
        showHidden: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        zone: java.util.TimeZone = java.util.TimeZone.getDefault()
    ): IndexSelection {
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()

        // The name comes first. SQLite stops at the first false clause, and this one is what rules most
        // rows out, so a long list of extensions further on only runs on the few rows left.
        for (token in text.tokens) {
            for (run in asciiRuns(token)) {
                clauses.add("_display_name LIKE ? ESCAPE '\\'")
                args.add("%" + escapeForLike(run) + "%")
            }
        }
        if (scope != null) {
            clauses.add("_data LIKE ? ESCAPE '\\'")
            args.add(escapeForLike(scope.trimEnd('/')) + "/%")
        }
        if (!showHidden) clauses.add("_data NOT LIKE '%/.%'") else clauses.add("_data NOT LIKE '%/$BIN_FOLDER/%'")
        clauses.add("_data NOT LIKE '%/Android/data%'")
        clauses.add("_data NOT LIKE '%/Android/obb%'")

        when (val kind = filters.kind) {
            null -> if (filters.size != null) clauses.add("format IS NOT $FOLDER_FORMAT")
            SearchKind.Folders -> clauses.add("format = $FOLDER_FORMAT")
            else -> {
                clauses.add("format IS NOT $FOLDER_FORMAT")
                clauses.add(kindClause(kind))
            }
        }
        filters.size?.let { size ->
            // A row the scanner hasn't reached has no size yet, so the disk decides those afterwards.
            if (size.maxBytes == Long.MAX_VALUE) {
                clauses.add("(_size IS NULL OR _size >= ${size.minBytes})")
            } else {
                clauses.add("(_size IS NULL OR (_size >= ${size.minBytes} AND _size < ${size.maxBytes}))")
            }
        }
        filters.date?.let { date ->
            clauses.add("(date_modified IS NULL OR date_modified >= ${sinceMillis(date, nowMillis, zone) / 1000})")
        }
        return IndexSelection(clauses.joinToString(" AND "), args)
    }

    /** Every folder the index knows of in a scope, for finding what it doesn't know inside them. */
    fun foldersIn(scope: String?, showHidden: Boolean): IndexSelection {
        val clauses = arrayListOf("format = $FOLDER_FORMAT")
        val args = ArrayList<String>()
        if (scope != null) {
            clauses.add("_data LIKE ? ESCAPE '\\'")
            args.add(escapeForLike(scope.trimEnd('/')) + "/%")
        }
        if (!showHidden) clauses.add("_data NOT LIKE '%/.%'")
        clauses.add("_data NOT LIKE '%/Android/data%'")
        clauses.add("_data NOT LIKE '%/Android/obb%'")
        return IndexSelection(clauses.joinToString(" AND "), args)
    }

    /**
     * The parts of a word that LIKE can compare without caring about case. SQLite folds only ASCII, so
     * "ÉTÉ" would miss `été`, but both still contain "T", and the Kotlin side does the rest.
     */
    internal fun asciiRuns(token: String): List<String> {
        val runs = ArrayList<String>()
        val current = StringBuilder()
        for (c in token) {
            if (c.code < 128) {
                current.append(c)
            } else if (current.isNotEmpty()) {
                runs.add(current.toString())
                current.clear()
            }
        }
        if (current.isNotEmpty()) runs.add(current.toString())
        return runs
    }

    private fun kindClause(kind: SearchKind): String {
        val (mediaType, category) = when (kind) {
            SearchKind.Photos -> 1 to StorageCategory.Images
            SearchKind.Audio -> 2 to StorageCategory.Audio
            SearchKind.Videos -> 3 to StorageCategory.Videos
            SearchKind.Documents -> null to StorageCategory.Documents
            SearchKind.Archives -> null to StorageCategory.Archives
            SearchKind.Apps -> null to StorageCategory.Apps
            SearchKind.Folders -> error("Folders are told apart by their format")
        }
        // The extensions are our own, never from outside, so they can sit in the text of the query.
        val byName = "$NOT_MEDIA AND (" + category.extensions.joinToString(" OR ") { "_data LIKE '%.$it'" } + ")"
        return if (mediaType != null) "(media_type = $mediaType OR ($byName))" else "($byName)"
    }

    fun sinceMillis(date: SearchDate, nowMillis: Long, zone: java.util.TimeZone): Long {
        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = nowMillis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        when (date) {
            SearchDate.Today -> Unit
            SearchDate.Week -> calendar.add(Calendar.DAY_OF_YEAR, -6)
            SearchDate.Month -> calendar.add(Calendar.DAY_OF_YEAR, -29)
            SearchDate.Year -> calendar.add(Calendar.YEAR, -1)
        }
        return calendar.timeInMillis
    }
}

/** Decides a row for good, once its real size and date are known. */
internal class SearchMatcher(
    val text: SearchText,
    val filters: SearchFilters,
    val scope: String?,
    val showHidden: Boolean,
    val lockedFolders: Set<String>,
    nowMillis: Long = System.currentTimeMillis(),
    zone: java.util.TimeZone = java.util.TimeZone.getDefault()
) {
    private val since = filters.date?.let { IndexSelectionBuilder.sinceMillis(it, nowMillis, zone) }
    private val scopePrefix = scope?.trimEnd('/')?.plus("/")

    fun matches(path: String, name: String, isFolder: Boolean, mediaType: Int, size: Long, modified: Long): Boolean {
        if (!text.matches(name)) return false
        if (scopePrefix != null && !path.startsWith(scopePrefix)) return false
        if (!showHidden && isHiddenPath(path)) return false
        if (path.contains("/${IndexSelectionBuilder.BIN_FOLDER}")) return false
        if (isInsideLocked(path)) return false
        when (val kind = filters.kind) {
            null -> if (isFolder && filters.size != null) return false
            SearchKind.Folders -> if (!isFolder) return false
            else -> {
                if (isFolder) return false
                if (categoryOf(kind) != StorageCategory.ofRow(mediaType, path)) return false
            }
        }
        filters.size?.let { if (size < it.minBytes || size >= it.maxBytes) return false }
        if (since != null && modified < since) return false
        return true
    }

    /** Whether [path] is under one of the hidden folders, not merely hidden itself. */
    private fun isHiddenPath(path: String): Boolean = path.contains("/.")

    /**
     * A locked folder asks for the password before it opens, so its contents can't be readable from a
     * search either. The locked item itself is listed, and asks when tapped.
     */
    private fun isInsideLocked(path: String): Boolean {
        if (lockedFolders.isEmpty()) return false
        return lockedFolders.any { path.startsWith("$it/") }
    }

    private fun categoryOf(kind: SearchKind): StorageCategory = when (kind) {
        SearchKind.Photos -> StorageCategory.Images
        SearchKind.Videos -> StorageCategory.Videos
        SearchKind.Audio -> StorageCategory.Audio
        SearchKind.Documents -> StorageCategory.Documents
        SearchKind.Archives -> StorageCategory.Archives
        SearchKind.Apps -> StorageCategory.Apps
        SearchKind.Folders -> error("Folders are told apart by their format")
    }
}
