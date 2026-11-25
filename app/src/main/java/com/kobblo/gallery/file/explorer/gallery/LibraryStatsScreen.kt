package com.kobblo.gallery.file.explorer.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.hub.HubScreen
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.StorageMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** A count and a size for one slice of the library. */
data class StatLine(val label: String, val count: Int, val bytes: Long)

data class LibraryStats(
    val photos: StatLine,
    val videos: StatLine,
    val types: List<StatLine>,
    val folders: List<StatLine>
) {
    val totalBytes: Long get() = photos.bytes + videos.bytes
    val totalCount: Int get() = photos.count + videos.count
}

/** The library by kind, by file type, and by the albums taking the most room. */
fun buildLibraryStats(
    files: List<FileItem>,
    photosLabel: String = "Photos",
    videosLabel: String = "Videos",
    rootName: String = "Internal Storage",
    maxTypes: Int = 8,
    maxFolders: Int = 10
): LibraryStats {
    class Tally(var count: Int = 0, var bytes: Long = 0L)

    val photos = Tally()
    val videos = Tally()
    val types = HashMap<String, Tally>()
    val folders = HashMap<String, Tally>()
    for (file in files) {
        val kind = if (file.type == "video") videos else photos
        kind.count++
        kind.bytes += file.sizeBytes
        val type = types.getOrPut(extensionOf(file.name).uppercase(Locale.ROOT).ifEmpty { "?" }) { Tally() }
        type.count++
        type.bytes += file.sizeBytes
        val folder = folders.getOrPut(file.path.substringBeforeLast('/', "")) { Tally() }
        folder.count++
        folder.bytes += file.sizeBytes
    }
    fun top(from: Map<String, Tally>, limit: Int, label: (String) -> String) = from.entries
        .sortedWith(compareByDescending<Map.Entry<String, Tally>> { it.value.bytes }.thenBy { it.key })
        .take(limit)
        .map { StatLine(label(it.key), it.value.count, it.value.bytes) }
    return LibraryStats(
        photos = StatLine(photosLabel, photos.count, photos.bytes),
        videos = StatLine(videosLabel, videos.count, videos.bytes),
        types = top(types, maxTypes) { it },
        folders = top(folders, maxFolders) { albumNameFor(it, rootName) }
    )
}

@Composable
internal fun LibraryStatsScreen(state: AppState) {
    val context = LocalContext.current
    val photosLabel = stringResource(R.string.gallery_stats_photos)
    val videosLabel = stringResource(R.string.gallery_stats_videos)
    val rootName = stringResource(R.string.gallery_album_root)
    val stats by produceState<LibraryStats?>(null, state.galleryFiles) {
        val files = state.galleryFiles
        value = withContext(Dispatchers.Default) { buildLibraryStats(files, photosLabel, videosLabel, rootName) }
    }

    HubScreen(
        title = stringResource(R.string.gallery_library_stats),
        backLabel = stringResource(R.string.gallery_albums)
    ) {
        val current = stats ?: return@HubScreen
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item(key = "overview") {
                KobbloGroupHeader(title = stringResource(R.string.gallery_stats_overview), modifier = Modifier.padding(top = 0.dp))
                KobbloGroup {
                    StatRow(current.photos, showDivider = true)
                    StatRow(current.videos, showDivider = true)
                    StatRow(StatLine(stringResource(R.string.gallery_stats_total), current.totalCount, current.totalBytes))
                }
            }
            item(key = "types") {
                KobbloGroupHeader(title = stringResource(R.string.gallery_stats_types))
                KobbloGroup {
                    current.types.forEachIndexed { index, line ->
                        StatRow(line, showDivider = index < current.types.lastIndex, total = current.totalBytes)
                    }
                }
            }
            item(key = "folders") {
                KobbloGroupHeader(title = stringResource(R.string.gallery_stats_folders))
                KobbloGroup {
                    current.folders.forEachIndexed { index, line ->
                        StatRow(line, showDivider = index < current.folders.lastIndex, total = current.totalBytes)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(line: StatLine, showDivider: Boolean = false, total: Long = 0L) {
    val context = LocalContext.current
    KobbloRow(
        title = line.label,
        subtitle = pluralStringResource(R.plurals.gallery_item_count, line.count, line.count),
        trailing = {
            Text(
                text = com.kobblo.gallery.file.explorer.data.core.FileSizes.label(line.bytes),
                style = KobbloType.callout,
                color = KobbloDesign.colors.smoke
            )
        },
        below = if (total > 0L) {
            { StorageMeter(fraction = line.bytes.toFloat() / total, height = 3.dp, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }
        } else null,
        showDivider = showDivider
    )
}
