package com.kobblo.gallery.file.explorer.music

/**
 * One song. [path] is its identity everywhere: favorites, playlists and stats all key on it, so they
 * survive a MediaStore rescan, which can hand the same file a new id.
 */
data class MusicTrack(
    val path: String = "",
    val title: String = "",
    /** Empty when the file has no artist tag. */
    val artist: String = "",
    /** MediaStore fills this with the folder's name for an untagged file. */
    val album: String = "",
    val albumArtist: String = "",
    /** The MediaStore row, or 0 for a file MediaStore doesn't know, such as one opened from another app. */
    val mediaId: Long = 0L,
    val albumId: Long = 0L,
    val durationMs: Long = 0L,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val year: Int = 0,
    val dateAddedSec: Long = 0L,
    val sizeBytes: Long = 0L
) {
    val fileName: String get() = path.substringAfterLast('/')
    val folderPath: String get() = path.substringBeforeLast('/', "")
    val folderName: String get() = folderPath.substringAfterLast('/')

    /** The album to group under: an untagged file falls into its folder, which is how it was filed. */
    val albumOrFolder: String get() = album.ifBlank { folderName }
}

/** What an untagged file is called: its name, without the extension or the underscores of a dump. */
fun titleFromFileName(fileName: String): String {
    val base = fileName.substringBeforeLast('.', fileName)
    return base.replace('_', ' ').trim().ifEmpty { fileName }
}

/** A track built only from a path, for a file nothing else has described yet. */
fun trackFromPath(path: String, sizeBytes: Long = 0L): MusicTrack =
    MusicTrack(path = path, title = titleFromFileName(path.substringAfterLast('/')), sizeBytes = sizeBytes)

/** The real path of a file in shared storage, for the other names Android gives the same place. */
fun canonicalStoragePath(path: String, storageRoot: String): String {
    val root = storageRoot.trimEnd('/')
    for (alias in listOf("/sdcard", "/mnt/sdcard", "/storage/self/primary")) {
        if (path == alias) return root
        if (path.startsWith("$alias/")) return root + path.substring(alias.length)
    }
    return path
}

/** Extensions the app treats as audio, including some the player will decline with a message. */
val AudioExtensions: Set<String> = setOf(
    "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr", "3ga",
    "mka", "aif", "aiff", "ape", "alac", "ac3", "mid", "midi", "caf", "dsf", "weba"
)

fun isAudioFileName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in AudioExtensions
