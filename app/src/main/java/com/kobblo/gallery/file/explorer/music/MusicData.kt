package com.kobblo.gallery.file.explorer.music

import kotlin.math.pow

// Every field has a default so Gson builds these through their no-argument constructors, and a field
// added in a later version simply takes its default when an older file is read.

data class Playlist(
    val id: String = "",
    val name: String = "",
    val paths: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class TrackStats(val plays: Int = 0, val listenedMs: Long = 0L, val lastPlayedAt: Long = 0L)

/** The sound of playback. All of it stays on across restarts. */
data class SoundSettings(
    val speed: Float = 1f,
    /** Semitones up or down, independent of speed. */
    val pitchSemitones: Int = 0,
    val skipSilence: Boolean = false,
    /** How long one song overlaps the next, or 0 for today's gapless playback. */
    val crossfadeSeconds: Int = 0,
    val eqEnabled: Boolean = false,
    /** A platform preset, or -1 once the bands have been moved by hand. */
    val eqPreset: Int = -1,
    /** In millibels, one per band. Empty until the equalizer has first been used. */
    val eqBands: List<Int> = emptyList(),
    /** 0 to 1000, the platform's own scale. */
    val bassBoost: Int = 0,
    val lastSleepMinutes: Int = 30
) {
    val pitchFactor: Float get() = 2f.pow(pitchSemitones / 12f)
}

data class MusicData(
    /** Newest first. */
    val favorites: List<String> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val stats: Map<String, TrackStats> = emptyMap(),
    /** Newest first, each path once. */
    val recent: List<String> = emptyList(),
    /** The user's own lyric timing, in milliseconds, per song. */
    val lyricOffsets: Map<String, Long> = emptyMap(),
    val sound: SoundSettings = SoundSettings(),
    val songSort: SongSort = SongSort.Title
) {
    fun isFavorite(path: String): Boolean = path in favorites

    fun withFavorite(path: String, favorite: Boolean): MusicData = copy(
        favorites = if (favorite) listOf(path) + (favorites - path) else favorites - path
    )

    // ── Playlists ─────────────────────────────────────────────────────────────

    fun playlist(id: String): Playlist? = playlists.firstOrNull { it.id == id }

    fun withNewPlaylist(id: String, name: String, paths: List<String>, now: Long): MusicData = copy(
        playlists = playlists + Playlist(id = id, name = name.trim(), paths = paths, createdAt = now, updatedAt = now)
    )

    fun withRenamedPlaylist(id: String, name: String, now: Long): MusicData =
        updating(id, now) { it.copy(name = name.trim()) }

    fun withoutPlaylist(id: String): MusicData = copy(playlists = playlists.filterNot { it.id == id })

    /** Adds songs to the end of a playlist, leaving out any it already holds. */
    fun withAddedToPlaylist(id: String, paths: List<String>, now: Long): MusicData =
        updating(id, now) { playlist ->
            playlist.copy(paths = playlist.paths + paths.filterNot { it in playlist.paths }.distinct())
        }

    fun withRemovedFromPlaylist(id: String, index: Int, now: Long): MusicData =
        updating(id, now) { playlist ->
            if (index !in playlist.paths.indices) playlist
            else playlist.copy(paths = playlist.paths.toMutableList().apply { removeAt(index) })
        }

    fun withMovedInPlaylist(id: String, from: Int, to: Int, now: Long): MusicData =
        updating(id, now) { playlist ->
            if (from !in playlist.paths.indices || to !in playlist.paths.indices) playlist
            else playlist.copy(paths = playlist.paths.toMutableList().apply { add(to, removeAt(from)) })
        }

    private fun updating(id: String, now: Long, change: (Playlist) -> Playlist): MusicData = copy(
        playlists = playlists.map { if (it.id == id) change(it).copy(updatedAt = now) else it }
    )

    // ── Stats ─────────────────────────────────────────────────────────────────

    fun withListened(path: String, deltaMs: Long): MusicData {
        val entry = stats[path] ?: TrackStats()
        return copy(stats = stats + (path to entry.copy(listenedMs = entry.listenedMs + deltaMs)))
    }

    fun withPlay(path: String, now: Long): MusicData {
        val entry = stats[path] ?: TrackStats()
        return copy(
            stats = stats + (path to entry.copy(plays = entry.plays + 1, lastPlayedAt = now)),
            recent = (listOf(path) + (recent - path)).take(RecentLimit)
        )
    }

    fun mostPlayed(limit: Int = MostPlayedLimit): List<String> =
        stats.entries
            .filter { it.value.plays > 0 }
            .sortedWith(
                compareByDescending<Map.Entry<String, TrackStats>> { it.value.plays }
                    .thenByDescending { it.value.lastPlayedAt }
            )
            .take(limit)
            .map { it.key }

    val totalListenedMs: Long get() = stats.values.sumOf { it.listenedMs }

    fun withLyricOffset(path: String, offsetMs: Long): MusicData = copy(
        lyricOffsets = if (offsetMs == 0L) lyricOffsets - path else lyricOffsets + (path to offsetMs)
    )

    companion object {
        const val RecentLimit = 200
        const val MostPlayedLimit = 100
    }
}

/**
 * Turns playback into listening time and play counts. A play counts once per time through a song,
 * after half of it or thirty seconds, whichever comes first, so skipping past something doesn't
 * make it a favorite.
 */
class ListenTracker(
    private val onListened: (path: String, deltaMs: Long) -> Unit,
    private val onCounted: (path: String) -> Unit
) {
    private var path: String? = null
    private var threshold = CountAfterMs
    private var heard = 0L
    private var counted = false

    /** Starts a fresh time through [trackPath]. */
    fun begin(trackPath: String?, durationMs: Long) {
        path = trackPath
        threshold = if (durationMs > 0L) minOf(CountAfterMs, durationMs / 2L) else CountAfterMs
        heard = 0L
        counted = false
    }

    /** The real duration arrives after [begin] when the file couldn't say up front. */
    fun updateDuration(durationMs: Long) {
        if (durationMs > 0L) threshold = minOf(CountAfterMs, durationMs / 2L)
    }

    fun listened(deltaMs: Long) {
        val current = path ?: return
        if (deltaMs <= 0L) return
        onListened(current, deltaMs)
        heard += deltaMs
        if (!counted && heard >= threshold) {
            counted = true
            onCounted(current)
        }
    }

    private companion object {
        const val CountAfterMs = 30_000L
    }
}
