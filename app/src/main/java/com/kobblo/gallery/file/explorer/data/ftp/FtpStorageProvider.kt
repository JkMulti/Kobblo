package com.kobblo.gallery.file.explorer.data.ftp

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.data.remote.RemoteAddress
import com.kobblo.gallery.file.explorer.data.remote.RemoteEntry
import com.kobblo.gallery.file.explorer.data.remote.RemoteRows
import com.kobblo.gallery.file.explorer.data.remote.RemoteSession
import com.kobblo.gallery.file.explorer.data.remote.SlashPaths
import com.kobblo.gallery.file.explorer.data.remote.remoteCall
import com.kobblo.gallery.file.explorer.data.remote.serverPathOf
import com.kobblo.gallery.file.explorer.data.remote.withoutDotEntries
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.time.Duration

/**
 * Files on an FTP server, over plain FTP or either kind of FTPS. One server is open at a time; connecting
 * to another lets go of the first.
 *
 * Every call answers with a [Result] and does its work off the main thread, apart from [disconnect].
 */
class FtpStorageProvider {

    private val session = RemoteSession<FTPClient>()
    private val rows = RemoteRows()

    /** The saved connection that is open, or null. */
    val currentConnectionId: String? get() = session.connectionId

    suspend fun connect(connection: FtpConnection, password: String): Result<Unit> = remoteCall {
        disconnect()
        val ftp = newClient(connection)
        ftp.connectTimeout = ConnectTimeoutMillis
        ftp.connect(connection.host, connection.port)

        val greeting = ftp.replyCode
        if (!FTPReply.isPositiveCompletion(greeting)) hangUp(ftp, "Server refused connection ($greeting)")
        if (!ftp.login(connection.username, password)) hangUp(ftp, "Authentication failed")

        if (connection.passiveMode) ftp.enterLocalPassiveMode() else ftp.enterLocalActiveMode()
        // Text mode would change line endings on the way, and ruin anything that isn't text.
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        // A long transfer leaves the command connection silent, and routers drop silent connections.
        ftp.setControlKeepAliveTimeout(Duration.ofSeconds(KeepAliveSeconds))

        session.open(connection.id, ftp)
    }

    fun disconnect() {
        val ftp = session.close() ?: return
        try {
            if (ftp.isConnected) {
                ftp.logout()
                ftp.disconnect()
            }
        } catch (_: Exception) {
            // The server may be long gone, and there is nobody left to tell.
        }
    }

    suspend fun listFiles(path: String, connectionId: String): Result<List<FileItem>> =
        session.callFor(connectionId) { ftp ->
            val address = RemoteAddress("ftp", connectionId)
            val folder = SlashPaths.folder(address.pathIn(path))
            val entries = ftp.listFiles(folder).map(::entryOf).withoutDotEntries()
            rows.of(entries, address, owner = "FTP") { name -> SlashPaths.child(folder, name) }
        }

    suspend fun download(remotePath: String, localFile: File): Result<Unit> = session.call { ftp ->
        val from = serverPathOf(remotePath)
        val arrived = localFile.outputStream().buffered().use { ftp.retrieveFile(from, it) }
        ftp.expect(arrived, "Download")
    }

    suspend fun upload(localFile: File, remotePath: String): Result<Unit> = session.call { ftp ->
        val to = serverPathOf(remotePath)
        val stored = localFile.inputStream().buffered().use { ftp.storeFile(to, it) }
        ftp.expect(stored, "Upload")
    }

    /** FTP removes files and folders with different commands, so the caller says which [remotePath] is. */
    suspend fun delete(remotePath: String, isDirectory: Boolean): Result<Unit> = session.call { ftp ->
        val target = serverPathOf(remotePath)
        ftp.expect(if (isDirectory) ftp.removeDirectory(target) else ftp.deleteFile(target), "Delete")
    }

    /** Makes the folder [name] inside [path]. */
    suspend fun createFolder(path: String, name: String): Result<Unit> = session.call { ftp ->
        ftp.expect(ftp.makeDirectory(SlashPaths.child(serverPathOf(path), name)), "Create folder")
    }

    private fun newClient(connection: FtpConnection): FTPClient =
        if (connection.useImplicitFtps || connection.useFtps) {
            FTPSClient(/* isImplicit = */ connection.useImplicitFtps)
        } else {
            FTPClient()
        }

    /** Closes a connection the server answered but wouldn't let in, and fails the attempt with [reason]. */
    private fun hangUp(ftp: FTPClient, reason: String): Nothing {
        ftp.disconnect()
        throw Exception(reason)
    }

    /**
     * The library reports a command the server turned down by returning false; the server's own words are
     * in its last reply, and they are the only explanation there is.
     */
    private fun FTPClient.expect(done: Boolean, action: String) {
        if (!done) throw Exception("$action failed: $replyString")
    }

    private fun entryOf(file: FTPFile) = RemoteEntry(
        name = file.name,
        isFolder = file.isDirectory,
        sizeBytes = file.size,
        modifiedAt = file.timestamp?.timeInMillis ?: 0L
    )

    private companion object {
        const val ConnectTimeoutMillis = 10_000
        const val KeepAliveSeconds = 15L
    }
}
