package com.kobblo.gallery.file.explorer.gallery.places

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.gallery.MaxCoverCandidates
import com.kobblo.gallery.file.explorer.gallery.PhotoDetails

/** One town's photos, or one tag's. */
internal class MediaGroup(val key: String, val name: String, val place: Int, val count: Int, val covers: List<FileItem>)

/** A country and the towns in it, most photos first. */
internal class CountryGroup(val code: String, val name: String, val count: Int, val places: List<MediaGroup>)

private class Tally(val covers: ArrayList<FileItem> = ArrayList(MaxCoverCandidates)) {
    var count = 0

    fun add(file: FileItem) {
        count++
        // The feed runs newest first, so the first few here are the newest. Nothing locked becomes a cover.
        if (covers.size < MaxCoverCandidates) covers.add(file)
    }
}

/** One pass over the feed. Locked files don't count, and don't show. */
internal fun buildPlaceGroups(
    files: List<FileItem>,
    details: Map<Int, PhotoDetails>,
    placeName: (Int) -> String,
    countryCode: (Int) -> String,
    countryLabel: (String) -> String = ::countryName
): List<CountryGroup> {
    val tallies = HashMap<Int, Tally>()
    for (file in files) {
        if (file.isLocked) continue
        val place = details[file.id]?.place ?: continue
        if (place >= 0) tallies.getOrPut(place) { Tally() }.add(file)
    }
    return tallies.entries
        .groupBy { countryCode(it.key) }
        .map { (code, entries) ->
            val places = entries
                .map { (place, tally) -> MediaGroup("place_$place", placeName(place), place, tally.count, tally.covers) }
                .sortedWith(compareByDescending<MediaGroup> { it.count }.thenBy { it.name })
            CountryGroup(code, countryLabel(code), places.sumOf { it.count }, places)
        }
        .sortedWith(compareByDescending<CountryGroup> { it.count }.thenBy { it.name })
}

internal fun buildTagGroups(files: List<FileItem>, details: Map<Int, PhotoDetails>): List<MediaGroup> {
    val tallies = HashMap<String, Tally>()
    for (file in files) {
        if (file.isLocked) continue
        val tags = details[file.id]?.tags ?: continue
        for (tag in tags) tallies.getOrPut(tag) { Tally() }.add(file)
    }
    return tallies
        .map { (tag, tally) -> MediaGroup("tag_$tag", tag, -1, tally.count, tally.covers) }
        .sortedWith(compareByDescending<MediaGroup> { it.count }.thenBy { it.name })
}
