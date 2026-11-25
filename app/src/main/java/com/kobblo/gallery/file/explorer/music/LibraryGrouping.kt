package com.kobblo.gallery.file.explorer.music

import java.text.Normalizer

data class MusicAlbum(
    val key: String,
    val title: String,
    /** The album artist, or the only artist on it. Empty when untagged or [isVarious]. */
    val artist: String,
    val isVarious: Boolean,
    val year: Int,
    /** In playing order: disc, then track, then title. */
    val tracks: List<MusicTrack>
) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
}

data class MusicArtist(
    val key: String,
    /** Empty for the group of tracks with no artist tag. */
    val name: String,
    val tracks: List<MusicTrack>,
    val albums: List<MusicAlbum>
)

data class MusicFolder(val path: String, val name: String, val tracks: List<MusicTrack>)

/** How the Songs view can be ordered. */
enum class SongSort { Title, Artist, Album, RecentlyAdded }

private val LeadingArticle = Regex("^(the|a|an)\\s+")
private val Diacritics = Regex("\\p{Mn}+")
private val LeadingPunctuation = Regex("^[^\\p{L}\\p{N}]+")

/**
 * The key a name sorts and groups under: no case, accents or leading "The", so "Éa", "ea" and "The
 * Ea" stay together. Everything here runs off the main thread, over the whole library.
 */
fun sortKey(text: String): String {
    val folded = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
        .replace(Diacritics, "")
        .lowercase()
    val noPunctuation = folded.replace(LeadingPunctuation, "")
    return noPunctuation.replace(LeadingArticle, "").ifEmpty { folded }
}

/** Orders names the way people count, so "Track 2" comes before "Track 10". */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val startA = i
            val startB = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val numA = a.substring(startA, i).trimStart('0')
            val numB = b.substring(startB, j).trimStart('0')
            if (numA.length != numB.length) return numA.length - numB.length
            val digits = numA.compareTo(numB)
            if (digits != 0) return digits
        } else {
            val diff = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (diff != 0) return diff
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

private val TitleOrder: Comparator<MusicTrack> =
    compareBy<MusicTrack> { sortKey(it.title) }.thenBy { it.path }

/** Album order: disc, then track. Tracks with no number follow, by name. */
val AlbumTrackOrder: Comparator<MusicTrack> = Comparator { a, b ->
    val disc = a.discNumber.compareTo(b.discNumber)
    if (disc != 0) return@Comparator disc
    val aNumbered = a.trackNumber > 0
    val bNumbered = b.trackNumber > 0
    when {
        aNumbered && bNumbered && a.trackNumber != b.trackNumber -> a.trackNumber - b.trackNumber
        aNumbered != bNumbered -> if (aNumbered) -1 else 1
        else -> naturalCompare(a.fileName, b.fileName)
    }
}

/** A folder plays in the order its files are named, as it reads in Browse. */
val FolderTrackOrder: Comparator<MusicTrack> = Comparator { a, b -> naturalCompare(a.fileName, b.fileName) }

fun sortSongs(tracks: List<MusicTrack>, sort: SongSort): List<MusicTrack> = when (sort) {
    SongSort.Title -> tracks.sortedWith(TitleOrder)
    SongSort.Artist -> tracks.sortedWith(
        compareBy<MusicTrack> { it.artist.isBlank() }
            .thenBy { sortKey(it.artist) }
            .thenBy { sortKey(it.albumOrFolder) }
            .then(AlbumTrackOrder)
    )
    SongSort.Album -> tracks.sortedWith(
        compareBy<MusicTrack> { sortKey(it.albumOrFolder) }.then(AlbumTrackOrder)
    )
    SongSort.RecentlyAdded -> tracks.sortedWith(
        compareByDescending<MusicTrack> { it.dateAddedSec }.then(TitleOrder)
    )
}

/**
 * The same album name is the same album only under one album artist, or, with no such tag, in one
 * folder. That keeps every "Greatest Hits" apart and a split-up album together.
 */
private fun albumKey(track: MusicTrack): String {
    val owner = sortKey(track.albumArtist).ifEmpty { track.folderPath }
    return sortKey(track.albumOrFolder) + "\u0000" + owner
}

fun groupAlbums(tracks: List<MusicTrack>): List<MusicAlbum> =
    tracks.groupBy(::albumKey)
        .map { (key, members) ->
            val ordered = members.sortedWith(AlbumTrackOrder)
            val albumArtist = ordered.firstNotNullOfOrNull { t -> t.albumArtist.takeIf { it.isNotBlank() } }
            val trackArtists = ordered.map { it.artist }.filter { it.isNotBlank() }.distinctBy(::sortKey)
            MusicAlbum(
                key = key,
                title = ordered.first().albumOrFolder,
                artist = albumArtist ?: trackArtists.singleOrNull().orEmpty(),
                isVarious = albumArtist == null && trackArtists.size > 1,
                year = ordered.maxOf { it.year },
                tracks = ordered
            )
        }
        .sortedBy { sortKey(it.title) }

/** Artists by name, with the group of untagged tracks last rather than scattered through the list. */
fun groupArtists(tracks: List<MusicTrack>, albums: List<MusicAlbum> = groupAlbums(tracks)): List<MusicArtist> {
    val albumOfTrack = HashMap<String, MusicAlbum>(tracks.size)
    albums.forEach { album -> album.tracks.forEach { albumOfTrack[it.path] = album } }
    return tracks.groupBy { sortKey(it.artist) }
        .map { (key, members) ->
            val owned = members.mapNotNull { albumOfTrack[it.path] }.distinctBy { it.key }
            MusicArtist(
                key = key,
                name = members.first().artist.trim(),
                tracks = members.sortedWith(compareBy<MusicTrack> { sortKey(it.albumOrFolder) }.then(AlbumTrackOrder)),
                albums = owned.sortedWith(compareByDescending<MusicAlbum> { it.year }.thenBy { sortKey(it.title) })
            )
        }
        .sortedWith(compareBy<MusicArtist> { it.name.isBlank() }.thenBy { it.key })
}

fun groupFolders(tracks: List<MusicTrack>): List<MusicFolder> =
    tracks.groupBy { it.folderPath }
        .map { (path, members) ->
            MusicFolder(path = path, name = path.substringAfterLast('/'), tracks = members.sortedWith(FolderTrackOrder))
        }
        .sortedBy { sortKey(it.name) }

/** Every word of the query has to appear somewhere in the song's title, artist, album or file name. */
fun MusicTrack.matchesQuery(words: List<String>): Boolean {
    if (words.isEmpty()) return true
    val haystack = sortFold("$title $artist $album $albumArtist $fileName")
    return words.all { it in haystack }
}

fun queryWords(query: String): List<String> =
    sortFold(query).split(' ', '\t').filter { it.isNotEmpty() }

private fun sortFold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Diacritics, "").lowercase()

/**
 * Where each letter starts in a list sorted by title, for jumping down a long library. Anything that
 * isn't a Latin letter, digits and other scripts alike, files under `#`, at the front.
 */
fun letterIndex(tracks: List<MusicTrack>): List<Pair<Char, Int>> {
    val found = LinkedHashMap<Char, Int>()
    tracks.forEachIndexed { index, track ->
        val first = sortKey(track.title).firstOrNull()?.uppercaseChar() ?: '#'
        val letter = if (first in 'A'..'Z') first else '#'
        found.putIfAbsent(letter, index)
    }
    return found.entries.map { it.key to it.value }.sortedBy { it.first }
}

/** A total running time in the largest units that still say something, which [formatTotalDuration] puts in words. */
sealed interface RunningTime {
    data class Hours(val hours: Long, val minutes: Long) : RunningTime
    data class Minutes(val minutes: Long) : RunningTime
    data class Seconds(val seconds: Long) : RunningTime
}

fun runningTimeOf(durationMs: Long): RunningTime {
    val minutes = durationMs / 60_000L
    return when {
        minutes >= 60L -> RunningTime.Hours(minutes / 60L, minutes % 60L)
        minutes >= 1L -> RunningTime.Minutes(minutes)
        else -> RunningTime.Seconds(durationMs / 1000L)
    }
}

/** A track's time, as m:ss or h:mm:ss. */
fun formatTrackTime(millis: Long): String {
    val total = (millis / 1000L).coerceAtLeast(0L)
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
