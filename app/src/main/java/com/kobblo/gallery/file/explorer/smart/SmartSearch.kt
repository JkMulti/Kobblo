package com.kobblo.gallery.file.explorer.smart

import android.content.Context
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.gallery.GalleryFeed
import com.kobblo.gallery.file.explorer.gallery.GallerySection
import com.subhashh.lumen.ai.LumenAi
import kotlin.coroutines.cancellation.CancellationException

/**
 * The photos and videos that match [query], best first, from whichever features are ready: what's in
 * the picture, the words in it, and the people named. Empty when none is ready. Call off the main thread.
 */
internal suspend fun smartSearchIds(context: Context, query: String): List<Long> {
    if (query.isBlank() || !SmartFeatures.anyReady(context)) return emptyList()
    return try {
        LumenAi.search(context, query).items
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e("SmartSearch", "Smart Search failed", e)
        emptyList()
    }
}

/**
 * [feed] with what Smart Search found after it, under [title], in order of how well each matches.
 * What the feed already shows stays where it is, and locked files stay out.
 */
internal fun withSmartMatches(feed: GalleryFeed, ids: List<Long>, library: List<FileItem>, title: String): GalleryFeed {
    if (ids.isEmpty()) return feed
    val byId = library.associateBy { it.id.toLong() }
    val shown = feed.files.mapTo(HashSet()) { it.path }
    val extra = ids.mapNotNull { byId[it] }.filter { !it.isLocked && shown.add(it.path) }
    if (extra.isEmpty()) return feed
    return GalleryFeed(feed.sections + GallerySection(title, extra, feed.files.size), feed.files + extra)
}
