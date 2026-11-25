package com.kobblo.gallery.file.explorer.music

import java.net.URLDecoder

data class M3uEntry(
    /** An absolute path when it could be worked out, otherwise the path as the file wrote it. */
    val path: String,
    val title: String? = null,
    val artist: String? = null,
    val durationSec: Int? = null
)

/**
 * Reads and writes `.m3u` and `.m3u8`. Playlists come from everywhere, so this takes relative paths,
 * `file://` URLs and Windows separators in stride. Anything on a network is skipped: this is a local
 * player.
 */
object M3u {
    private val Scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]+://")

    fun parse(text: String, baseDir: String? = null): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        var title: String? = null
        var artist: String? = null
        var duration: Int? = null
        text.removePrefix("﻿").lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val body = line.substring("#EXTINF:".length)
                    val comma = body.indexOf(',')
                    val time = if (comma >= 0) body.substring(0, comma) else body
                    duration = time.trim().substringBefore(' ').toIntOrNull()?.takeIf { it >= 0 }
                    val name = if (comma >= 0) body.substring(comma + 1).trim() else ""
                    val dash = name.indexOf(" - ")
                    artist = if (dash > 0) name.substring(0, dash).trim() else null
                    title = (if (dash > 0) name.substring(dash + 3) else name).trim().ifEmpty { null }
                }
                line.startsWith("#") -> Unit
                else -> {
                    resolve(line, baseDir)?.let { entries += M3uEntry(it, title, artist, duration) }
                    title = null
                    artist = null
                    duration = null
                }
            }
        }
        return entries
    }

    /** Writes paths relative to [baseDir] where possible, so a folder of music and its list can move together. */
    fun write(entries: List<M3uEntry>, baseDir: String? = null): String = buildString {
        append("#EXTM3U\n")
        entries.forEach { entry ->
            val name = listOfNotNull(entry.artist?.takeIf { it.isNotBlank() }, entry.title?.takeIf { it.isNotBlank() })
                .joinToString(" - ")
            if (name.isNotEmpty() || entry.durationSec != null) {
                append("#EXTINF:").append(entry.durationSec ?: -1).append(',').append(name).append('\n')
            }
            append(relativeTo(entry.path, baseDir)).append('\n')
        }
    }

    private fun resolve(line: String, baseDir: String?): String? {
        if (line.startsWith("file:", ignoreCase = true)) {
            val path = line.substring("file:".length).replace(Regex("^/{2}(?=/)"), "")
            return normalize(decodePercent(path).replace('\\', '/'))
        }
        if (Scheme.containsMatchIn(line)) return null
        val path = line.replace('\\', '/')
        // A Windows drive letter can't exist here, so all that's left to match on is the name.
        if (Regex("^[a-zA-Z]:/").containsMatchIn(path)) return path
        if (path.startsWith("/")) return normalize(path)
        return if (baseDir != null) normalize("$baseDir/$path") else path
    }

    private fun decodePercent(path: String): String = try {
        URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        path
    }

    /** Collapses `.` and `..`, which playlists written next to the music use a lot. */
    fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return (if (path.startsWith("/")) "/" else "") + parts.joinToString("/")
    }

    private fun relativeTo(path: String, baseDir: String?): String {
        val base = baseDir?.trimEnd('/') ?: return path
        return if (path.startsWith("$base/")) path.removePrefix("$base/") else path
    }

    /** A playlist name turned into something every filesystem accepts. */
    fun fileNameFor(playlistName: String): String {
        val safe = playlistName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        return safe.ifEmpty { "Playlist" } + ".m3u"
    }
}
