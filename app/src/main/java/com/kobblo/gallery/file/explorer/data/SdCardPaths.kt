package com.kobblo.gallery.file.explorer.data

/**
 * Everything about a memory card that can be told from text alone: which paths are on one, and how to read
 * its root out of what the system reports. [StorageUtils] does the asking. Kept apart from it so none of
 * this needs a phone to check.
 */
internal object SdCardPaths {
    /** Where the system itself mounts a card. Apps can't read there, and see the same card under [SharedRoot]. */
    private const val RawRoot = "/mnt/media_rw/"
    private const val SharedRoot = "/storage/"

    /** The folders under /storage that aren't cards: the built-in storage, its alias, and Samsung's Knox. */
    private val NotCards = setOf("emulated", "self", "knox")

    private val Blanks = Regex("\\s+")

    /** [path] as an app can open it. */
    fun readable(path: String): String = if (path.startsWith(RawRoot)) path.replace(RawRoot, SharedRoot) else path

    /**
     * Whether one of the app's own folders is somewhere other than the built-in storage, which is under
     * [primaryRoot] and has "emulated" in its path however it's reached.
     */
    fun isOffBuiltIn(path: String, primaryRoot: String): Boolean = !path.startsWith(primaryRoot) && !path.contains("emulated")

    /**
     * The root of the storage one of the app's folders is on: `/storage/1A2B-3C4D/Android/data/app/files`
     * gives `/storage/1A2B-3C4D`.
     */
    fun rootOfAppFolder(path: String): String {
        val androidFolder = path.indexOf("/Android", ignoreCase = true)
        return if (androidFolder < 0) path else path.substring(0, androidFolder)
    }

    /** Whether [path] is under /storage without being the built-in storage. */
    fun isOnCardMount(path: String): Boolean =
        path.startsWith(SharedRoot) && !path.startsWith("/storage/emulated") && !path.startsWith("/storage/self")

    /**
     * Where a card is mounted, from the lines of `/proc/mounts`. Each line is a device, the place it's
     * mounted and its options, with blanks between.
     */
    fun mountedCard(mountLines: Sequence<String>): String? =
        mountLines.mapNotNull { it.split(Blanks).getOrNull(1) }.firstOrNull(::isOnCardMount)

    /** Whether a folder called [name] directly under /storage could be a card. */
    fun isCardFolderName(name: String): Boolean = name !in NotCards && !name.startsWith(".")

    /**
     * Whether [location] is on the card: the app's own "sd" shorthand, anything under [sdCardPath], or a
     * path that can only be on removable storage.
     */
    fun isSdCardLocation(location: String, sdCardPath: String?): Boolean = when {
        location == "sd" || location.startsWith("sd:") || location.startsWith("sd/") -> true
        sdCardPath != null && (location == sdCardPath || location.startsWith("$sdCardPath/")) -> true
        else -> isOnCardMount(location) || location.startsWith(RawRoot)
    }

    /** Storage is sold and shown in decimal gigabytes. */
    fun gigabytes(bytes: Long): Float = bytes / 1_000_000_000f
}
