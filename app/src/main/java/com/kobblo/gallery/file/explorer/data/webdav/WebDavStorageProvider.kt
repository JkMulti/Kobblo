package com.kobblo.gallery.file.explorer.data.webdav

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.data.remote.RemoteAddress
import com.kobblo.gallery.file.explorer.data.remote.RemoteEntry
import com.kobblo.gallery.file.explorer.data.remote.RemoteRows
import com.kobblo.gallery.file.explorer.data.remote.RemoteSession
import com.kobblo.gallery.file.explorer.data.remote.SlashPaths
import com.kobblo.gallery.file.explorer.data.remote.remoteCall
import com.kobblo.gallery.file.explorer.data.remote.serverPathOf
import java.io.File

/**
 * Files on a WebDAV server, Nextcloud included. One server is open at a time; connecting to another lets
 * go of the first.
 *
 * WebDAV is plain HTTP requests, so being connected means no more than holding a [WebDavClient] whose
 * first request the server accepted. Every call answers with a [Result] and does its work off the main
 * thread, apart from [disconnect], which has nothing to wait for.
 */
class WebDavStorageProvider {

    private val session = RemoteSession<WebDavClient>()
    private val rows = RemoteRows()

    /** The saved connection that is open, or null. */
    val currentConnectionId: String? get() = session.connectionId

    suspend fun connect(connection: WebDavConnection, password: String): Result<Unit> = remoteCall {
        disconnect()
        val dav = WebDavClient(connection, password)
        if (!dav.testConnection()) throw Exception("Authentication failed")
        session.open(connection.id, dav)
    }

    fun disconnect() {
        session.close()
    }

    /**
     * The same server is listed under `webdav_` or `nextcloud_` depending on how its connection was
     * saved, and the rows answer in whichever of the two [path] came in.
     */
    suspend fun listFiles(path: String, connectionId: String): Result<List<FileItem>> =
        session.callFor(connectionId) { dav ->
            val asNextcloud = path.startsWith("nextcloud_")
            val address = RemoteAddress(if (asNextcloud) "nextcloud" else "webdav", connectionId)
            val folder = SlashPaths.folder(address.pathIn(path))
            val entries = dav.list(folder).map(::entryOf)
            rows.of(entries, address, owner = if (asNextcloud) "Nextcloud" else "WebDAV") { name ->
                SlashPaths.child(folder, name)
            }
        }

    suspend fun download(remotePath: String, localFile: File): Result<Unit> = session.call { dav ->
        dav.download(serverPathOf(remotePath), localFile)
    }

    suspend fun upload(localFile: File, remotePath: String): Result<Unit> = session.call { dav ->
        dav.upload(localFile, serverPathOf(remotePath))
    }

    /** Removes the file at [remotePath], or the folder there together with everything in it. */
    suspend fun delete(remotePath: String): Result<Unit> = session.call { dav ->
        dav.delete(serverPathOf(remotePath))
    }

    /** Makes the folder [name] inside [path]. */
    suspend fun createFolder(path: String, name: String): Result<Unit> = session.call { dav ->
        dav.createFolder(SlashPaths.child(serverPathOf(path), name))
    }

    private fun entryOf(listed: WebDavEntry) = RemoteEntry(
        name = listed.name,
        isFolder = listed.isDirectory,
        sizeBytes = listed.sizeBytes,
        modifiedAt = listed.lastModifiedMillis
    )
}
