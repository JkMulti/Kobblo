package com.kobblo.gallery.file.explorer.music

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class PlaylistImportResult(val name: String, val matched: Int, val total: Int)

/** Moves playlists in and out of `.m3u` files. */
object PlaylistFiles {
    /**
     * Writes a playlist to Music/Playlists, where other players look for one. An existing file of that
     * name is replaced, since exporting the same playlist twice should update it, not pile up copies.
     */
    suspend fun export(context: Context, playlist: Playlist, library: MusicSnapshot): File? = withContext(Dispatchers.IO) {
        try {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Playlists")
            folder.mkdirs()
            val file = File(folder, M3u.fileNameFor(playlist.name))
            val entries = playlist.paths.map { path ->
                val track = library.byPath[path]
                M3uEntry(
                    path = path,
                    title = track?.title,
                    artist = track?.artist?.takeIf { it.isNotBlank() },
                    durationSec = track?.durationMs?.takeIf { it > 0L }?.let { (it / 1000L).toInt() }
                )
            }
            com.kobblo.gallery.file.explorer.fileops.SafeFiles.writeAtomically(file, M3u.write(entries, folder.path))
            MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
            file
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads a playlist file into a new playlist. A path can name a song by another route, as
     * `/sdcard` does, so it's resolved before it's looked up. A song that still isn't there is looked
     * for by file name, because a playlist made on another device rarely has the same folders.
     */
    suspend fun import(store: MusicStore, library: MusicSnapshot, path: String): PlaylistImportResult? = withContext(Dispatchers.IO) {
        try {
            val source = File(path)
            val entries = M3u.parse(decodeLyricBytes(source.readBytes()), source.parent)
            if (entries.isEmpty()) return@withContext null
            val byName = HashMap<String, String>(library.tracks.size)
            library.tracks.forEach { byName.putIfAbsent(it.fileName.lowercase(), it.path) }
            val found = entries.mapNotNull { entry ->
                val resolved = try {
                    File(entry.path).canonicalPath
                } catch (_: Exception) {
                    entry.path
                }
                when {
                    entry.path in library.byPath -> entry.path
                    resolved in library.byPath -> resolved
                    else -> byName[entry.path.substringAfterLast('/').lowercase()]
                }
            }.distinct()
            val name = source.nameWithoutExtension
            store.update {
                it.withNewPlaylist(UUID.randomUUID().toString(), name, found, System.currentTimeMillis())
            }
            PlaylistImportResult(name, found.size, entries.size)
        } catch (_: Exception) {
            null
        }
    }
}
