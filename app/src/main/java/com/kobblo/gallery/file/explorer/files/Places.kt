package com.kobblo.gallery.file.explorer.files

/**
 * Locations that are a word rather than a path: the app's own lists, and the tops of the phone's storages.
 * Every screen passes a location around as text, and this is where that text gets its meaning.
 */
internal object Places {
    /** The top of the phone's own storage. */
    const val Home = "home"

    const val Recent = "recent"

    /** Favorites, under the word they were first saved with. */
    const val Favorites = "pinned"

    private val cardWords = setOf("sd", "sd/")

    /** Whether something new at [location] goes on the memory card. */
    fun isCard(location: String): Boolean = location in cardWords

    /** Whether opening [location] shows the memory card, which one more spelling can ask for. */
    fun isOpenableCard(location: String): Boolean = location in cardWords || location == "sd:"

    /** Lists the app puts together, where there's no folder to make anything in. */
    fun isOnlyAList(location: String): Boolean = location == Recent || location == Favorites
}

/**
 * A folder on Google Drive, as a location spells it: `drive` is the top of My Drive for the account in use,
 * `drive:<account>` the top for another account, and `drive_id:<id>` any folder further down.
 *
 * @property account whose Drive it is, or null for the account in use.
 * @property folderId null at the top, which has no id anyone has to know.
 */
internal data class DriveLocation(val account: String?, val folderId: String?) {
    val isTop: Boolean get() = folderId == null

    /** The id to give Drive, which has a word for the top folder. */
    val parentId: String get() = folderId ?: "root"

    companion object {
        private const val Top = "drive"
        private const val AccountPrefix = "drive:"
        private const val IdPrefix = "drive_id:"

        /** Null when [location] isn't on Drive at all. */
        fun of(location: String): DriveLocation? = when {
            location == Top -> DriveLocation(account = null, folderId = null)
            location.startsWith(AccountPrefix) -> DriveLocation(location.removePrefix(AccountPrefix).substringBefore('/'), folderId = null)
            else -> idIn(location)?.let { DriveLocation(account = null, folderId = it) }
        }

        /** The id in the path of something on Drive, or null for any other path. */
        fun idIn(path: String): String? = if (path.startsWith(IdPrefix)) path.substring(IdPrefix.length) else null

        fun pathOf(id: String): String = IdPrefix + id
    }
}
