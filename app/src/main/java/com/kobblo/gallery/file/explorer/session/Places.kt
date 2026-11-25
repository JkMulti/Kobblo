package com.kobblo.gallery.file.explorer.session

/**
 * The cloud services an account can be signed in to. [key] begins every location in one, and its storage's
 * id: `drive` is its top for the account in use, `drive:me@example.com` the top for one account, and
 * `drive_id:abc` a folder or file by the service's own id.
 */
internal enum class CloudService(val key: String, private val namesAccounts: Boolean) {
    Drive("drive", namesAccounts = true),
    Mega("mega", namesAccounts = true),
    Dropbox("dropbox", namesAccounts = false);

    /** Whether [location] is this service's top folder. */
    fun isTop(location: String): Boolean = location == key || (namesAccounts && location.startsWith("$key:"))

    /** Whether [location] is something inside this service. */
    fun isFolder(location: String): Boolean = location.startsWith("${key}_id:")

    fun owns(location: String): Boolean = isTop(location) || isFolder(location)

    /** The service's own id in a location [isFolder] says yes to. */
    fun idIn(location: String): String = location.removePrefix("${key}_id:")

    /** The top folder of one account. */
    fun topOf(account: String): String = "$key:$account"

    /** The account [location] names, or null where it leaves that to whichever is in use. */
    fun accountIn(location: String): String? =
        if (location.startsWith("$key:")) location.removePrefix("$key:").substringBefore('/') else null

    /** The id of the storage [location] is in, which carries the account when there is one to tell. */
    fun storageId(location: String, accountInUse: String?): String {
        val account = accountIn(location).orEmpty().ifBlank { accountInUse.orEmpty() }
        return if (account.isBlank()) key else accountStorageId(account)
    }

    fun accountStorageId(account: String): String = "${key}_$account"

    /** The account in a storage's id, or null if [storageId] isn't one of this service's accounts. */
    fun accountOfStorage(storageId: String): String? =
        if (storageId.startsWith("${key}_")) storageId.removePrefix("${key}_") else null

    companion object {
        fun of(location: String): CloudService? = entries.firstOrNull { it.owns(location) }
    }
}

/**
 * The kinds of server a connection can be saved for. A location on one is its scheme, an underscore, the
 * connection's id, a colon and the server's own path: `smb_41c7:/`. WebDAV goes by two schemes, so that a
 * Nextcloud can be told apart by its location alone.
 *
 * [savedAs] is the name the kind has in saved data.
 */
internal enum class ServerKind(val savedAs: String, vararg val schemes: String) {
    Smb("smb", "smb"),
    Ftp("ftp", "ftp"),
    Sftp("sftp", "sftp"),
    WebDav("webdav", "webdav", NextcloudScheme);

    private fun owns(location: String): Boolean = schemes.any { location.startsWith(it + "_") }

    companion object {
        fun of(location: String): ServerKind? = entries.firstOrNull { it.owns(location) }

        /** The saved connection a server's [location] is on. */
        fun connectionIn(location: String): String = location.substringAfter('_').substringBefore(':')

        fun topOf(scheme: String, connectionId: String): String = "${scheme}_$connectionId:/"
    }
}

internal const val NextcloudScheme = "nextcloud"

/** Where a location is, asked in the ways that navigation, loading and search need to know. */
internal object Places {
    const val Home = "home"
    const val Gallery = "gallery"
    const val Locations = "locations"
    const val Send = "send"
    const val Recent = "recent"
    const val Pinned = "pinned"
    const val Card = "sd"

    private const val CategoryPrefix = "category/"

    /** The ways of naming the card that don't say where it's mounted. */
    private val CardShorthands = setOf(Card, "sd/", "sd:")

    fun isOnPhone(location: String): Boolean = location.startsWith("/")

    fun isCardShorthand(location: String): Boolean = location in CardShorthands

    /** The category [location] lists, or null if it isn't one. */
    fun categoryIn(location: String): String? =
        if (location.startsWith(CategoryPrefix)) location.removePrefix(CategoryPrefix) else null

    fun isCategory(location: String): Boolean = location.startsWith(CategoryPrefix)

    /** Inside a cloud service or on a server, where folders are found by id and the way back has to be kept. */
    fun isInsideCloud(location: String): Boolean =
        ServerKind.of(location) != null || CloudService.entries.any { it.isFolder(location) }

    /** Anywhere in the cloud or on a server, the top folders included. */
    fun isCloud(location: String): Boolean = isInsideCloud(location) || CloudService.of(location) != null

    /** The top of a cloud service or a server, which is where the way back begins again. */
    fun isCloudTop(location: String): Boolean =
        CloudService.entries.any { it.isTop(location) } || (ServerKind.of(location) != null && location.endsWith(":/"))

    /** Somewhere navigation keeps its place, so that coming back to the storage can pick it up. */
    fun isStorage(location: String): Boolean =
        isOnPhone(location) ||
            location == Card ||
            ServerKind.of(location) != null ||
            CloudService.entries.any { location.startsWith(it.key) }

    /**
     * Whether a search here looks through the phone. Anywhere else it can only go through the list that's
     * already been read.
     */
    fun searchesPhone(location: String): Boolean = !isCloud(location) && location != Recent && location != Pinned
}
