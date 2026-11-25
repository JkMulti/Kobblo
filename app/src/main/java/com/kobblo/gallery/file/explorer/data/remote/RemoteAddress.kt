package com.kobblo.gallery.file.explorer.data.remote

/**
 * The explorer's name for a place on a saved server: `<scheme>_<connection id>:` and then the path the way
 * the server itself writes it, as in `sftp_41c7:/home/sam/notes.txt`. These travel through the app as plain
 * strings (a row's path, the open location, the way back), so the form is fixed.
 */
internal class RemoteAddress(scheme: String, connectionId: String) {
    /** Everything up to and including the colon. */
    val prefix: String = "${scheme}_$connectionId:"

    /** The address of [serverPath] on this connection. */
    fun of(serverPath: String): String = prefix + serverPath

    /** The server's own path in [address]. An address that isn't this connection's comes back whole. */
    fun pathIn(address: String): String = address.removePrefix(prefix)
}

/**
 * The server's path out of an address of any connection: whatever follows the first colon. Callers pass
 * whole addresses and paths they have already cut down alike, and one with no colon in it comes through
 * untouched.
 */
internal fun serverPathOf(addressOrPath: String): String = addressOrPath.substringAfter(':')

/** Paths on the servers that write them with slashes from the top down: FTP, SFTP and WebDAV. */
internal object SlashPaths {
    const val Top = "/"

    /** [path] as a folder, where nothing at all stands for the top one. */
    fun folder(path: String): String = path.ifEmpty { Top }

    /** The path of [name] inside [folder]. A folder given as nothing is the top one here too. */
    fun child(folder: String, name: String): String =
        if (folder.endsWith('/')) folder + name else "$folder/$name"

    /**
     * [path] cut into the folder it lies in and its own name. Directly under the top the folder comes out
     * empty, which [child] reads as the top again; a path with no slash in it is taken to lie there as well.
     */
    fun split(path: String): Pair<String, String> =
        path.substringBeforeLast('/', Top) to path.substringAfterLast('/')
}

/**
 * Paths inside an SMB share: backslashes between names, nothing in front of the first one, and so the
 * empty path for the top folder.
 */
internal object SharePaths {
    /** [path] as a folder. The explorer opens a share at `/`, which inside the share is the empty path. */
    fun folder(path: String): String = path.removePrefix("/")

    /** The path of [name] inside [folder]. */
    fun child(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder\\$name"
}

/**
 * Where a file called [name] goes when it is put into [folder], on a server that separates names with
 * [separator]. A folder that already ends in the separator isn't given a second one. With no folder at all
 * the name stands alone, which a server reads from wherever the session starts.
 */
internal fun pathInFolder(folder: String, name: String, separator: Char): String =
    if (folder.isEmpty() || folder.endsWith(separator)) folder + name else folder + separator + name
