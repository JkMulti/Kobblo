package com.kobblo.gallery.file.explorer.gallery

import com.kobblo.gallery.file.explorer.FileItem
import java.util.Locale
import java.util.TimeZone

enum class AlbumSort { NAME, DATE, COUNT }

/** A folder of photos and videos, shown as an album. */
data class FolderAlbum(
    val path: String,
    val name: String,
    val count: Int,
    /**
     * The newest files that may be shown, best first. Locked files never become a cover, and more than
     * one is kept because the newest may turn out not to decode, which only showing it can tell.
     */
    val covers: List<FileItem>,
    val latestMillis: Long,
    val isPinned: Boolean,
    val isHiddenFromHome: Boolean = false
) {
    val cover: FileItem? get() = covers.firstOrNull()
}

data class SmartAlbum(val kind: SmartKind, val count: Int)

/** How far down an album to look for a picture before settling for a glyph. */
internal const val MaxCoverCandidates = 8

/** Keeps [candidates] as the newest few, newest first. */
private fun addCoverCandidate(candidates: ArrayList<FileItem>, file: FileItem) {
    if (file.isLocked) return
    if (candidates.size == MaxCoverCandidates && file.lastModified <= candidates.last().lastModified) return
    var at = candidates.size
    while (at > 0 && candidates[at - 1].lastModified < file.lastModified) at--
    candidates.add(at, file)
    if (candidates.size > MaxCoverCandidates) candidates.removeAt(candidates.lastIndex)
}

/**
 * Every folder holding media, plus the albums made here that are still empty. Pinned albums come
 * first, then the chosen order. One pass over the feed, so a 100,000 item library stays quick.
 */
fun buildFolderAlbums(
    files: List<FileItem>,
    pinned: Set<String>,
    created: Set<String>,
    sort: AlbumSort,
    rootName: String = "Internal Storage",
    hidden: Set<String> = emptySet(),
    chosenCovers: Map<String, String> = emptyMap()
): List<FolderAlbum> {
    class Tally(var count: Int = 0, val covers: ArrayList<FileItem> = ArrayList(2), var latest: Long = 0L, var chosen: FileItem? = null)

    val tallies = HashMap<String, Tally>()
    for (file in files) {
        val folder = file.path.substringBeforeLast('/', "")
        if (folder.isEmpty()) continue
        val tally = tallies.getOrPut(folder) { Tally() }
        tally.count++
        if (file.lastModified > tally.latest) tally.latest = file.lastModified
        addCoverCandidate(tally.covers, file)
        if (chosenCovers.isNotEmpty() && !file.isLocked && chosenCovers[folder] == file.path) tally.chosen = file
    }
    for (path in created) tallies.getOrPut(path) { Tally() }
    val hiddenPaths = com.kobblo.gallery.file.explorer.fileops.LockedPaths(hidden)

    val albums = tallies.map { (path, tally) ->
        FolderAlbum(
            path = path,
            name = albumNameFor(path, rootName),
            count = tally.count,
            // What someone chose comes first. The others stay behind it, in case it stops decoding.
            covers = tally.chosen?.let { chosen -> listOf(chosen) + tally.covers.filterNot { it.path == chosen.path } } ?: tally.covers,
            latestMillis = tally.latest,
            isPinned = path in pinned,
            isHiddenFromHome = hiddenPaths.covers(path)
        )
    }
    return sortAlbums(albums, sort)
}

fun sortAlbums(albums: List<FolderAlbum>, sort: AlbumSort): List<FolderAlbum> {
    val byName = compareBy<FolderAlbum> { it.name.lowercase(Locale.getDefault()) }.thenBy { it.path }
    val order = when (sort) {
        AlbumSort.NAME -> byName
        // An album with nothing in it yet was only just made, so it belongs with the newest.
        AlbumSort.DATE -> compareByDescending<FolderAlbum> { if (it.count == 0) Long.MAX_VALUE else it.latestMillis }.then(byName)
        AlbumSort.COUNT -> compareByDescending<FolderAlbum> { it.count }.then(byName)
    }
    return albums.sortedWith(compareByDescending<FolderAlbum> { it.isPinned }.then(order))
}

/** Storage roots end in a bare number on Android, which is no name for an album. */
fun albumNameFor(path: String, rootName: String): String {
    val name = path.substringAfterLast('/')
    return if (name.isEmpty() || name == "0") rootName else name
}

/** What's in one smart album, in feed order. */
fun smartAlbumFiles(
    kind: SmartKind,
    files: List<FileItem>,
    facts: Map<Int, MediaFacts>,
    flags: Map<Int, Int>,
    nowMillis: Long,
    zone: TimeZone = TimeZone.getDefault(),
    hidden: Set<String> = emptySet()
): List<FileItem> {
    val clock = SmartClock(nowMillis, zone)
    val hiddenPaths = com.kobblo.gallery.file.explorer.fileops.LockedPaths(hidden)
    return files.filter {
        matchesSmartAlbum(kind, it, facts[it.id], flags[it.id] ?: 0, clock) && !(kind.followsHome && hiddenPaths.covers(it.path))
    }
}

/** The smart albums that have something in them, in their fixed order. */
fun buildSmartAlbums(
    files: List<FileItem>,
    facts: Map<Int, MediaFacts>,
    flags: Map<Int, Int>,
    nowMillis: Long,
    zone: TimeZone = TimeZone.getDefault(),
    hidden: Set<String> = emptySet()
): List<SmartAlbum> {
    val kinds = SmartKind.entries
    val counts = IntArray(kinds.size)
    val clock = SmartClock(nowMillis, zone)
    val hiddenPaths = com.kobblo.gallery.file.explorer.fileops.LockedPaths(hidden)
    for (file in files) {
        val fileFacts = facts[file.id]
        val fileFlags = flags[file.id] ?: 0
        val isHidden = hiddenPaths.covers(file.path)
        for (i in kinds.indices) {
            if (isHidden && kinds[i].followsHome) continue
            if (matchesSmartAlbum(kinds[i], file, fileFacts, fileFlags, clock)) counts[i]++
        }
    }
    return kinds.indices.filter { counts[it] > 0 }.map { SmartAlbum(kinds[it], counts[it]) }
}

/** The name a person typed, made safe to use as a folder, or null if nothing usable is left. */
fun sanitizeAlbumName(input: String): String? {
    val cleaned = input.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "")
        .trimStart('.')
        .trim()
    return cleaned.takeIf { it.isNotEmpty() }?.take(120)
}

/** A name that doesn't collide with what's in the folder, so nothing is ever overwritten. */
fun freeFileName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""
    var counter = 1
    while (true) {
        val candidate = "$base ($counter)$extension"
        if (!taken(candidate)) return candidate
        counter++
    }
}
