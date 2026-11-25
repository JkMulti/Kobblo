package com.kobblo.gallery.file.explorer.data.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.data.remote.NotConnected
import com.kobblo.gallery.file.explorer.data.remote.RemoteAddress
import com.kobblo.gallery.file.explorer.data.remote.RemoteEntry
import com.kobblo.gallery.file.explorer.data.remote.RemoteRows
import com.kobblo.gallery.file.explorer.data.remote.RemoteSession
import com.kobblo.gallery.file.explorer.data.remote.SharePaths
import com.kobblo.gallery.file.explorer.data.remote.remoteCall
import com.kobblo.gallery.file.explorer.data.remote.serverPathOf
import com.kobblo.gallery.file.explorer.data.remote.withoutDotEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import com.hierynomus.smbj.share.File as ShareFile

/**
 * Files on a Windows share (SMB 2 and 3). One share is open at a time; connecting to another lets go of
 * the first.
 *
 * Paths inside a share have no leading separator and use backslashes: the top folder is the empty path
 * and a file two folders down is `Photos\2024\beach.jpg`. Every call here takes the explorer's address
 * for a place, `smb_<connection id>:` followed by such a path, and does its work off the main thread.
 */
class SmbStorageProvider {

    private val session = RemoteSession<Link>()
    private val rows = RemoteRows()

    /** The saved connection that is open, or null. */
    val currentConnectionId: String? get() = session.connectionId

    suspend fun connect(connection: SmbConnection, password: String): Result<Unit> = remoteCall {
        disconnect()
        val client = SMBClient(waits())
        var signedIn: Session? = null
        try {
            val account = AuthenticationContext(connection.username, password.toCharArray(), connection.domain)
            val login = client.connect(connection.host, connection.port).authenticate(account)
            signedIn = login
            // A host also offers printers and pipes under share names; only a disk has files to browse.
            val share = login.connectShare(connection.shareName) as DiskShare
            session.open(connection.id, Link(client, login, share))
        } catch (failure: Exception) {
            closeQuietly(signedIn, client)
            throw failure
        }
    }

    suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        session.close()?.let { closeQuietly(it.share, it.login, it.client) }
    }

    suspend fun listFiles(path: String, connectionId: String): Result<List<FileItem>> =
        session.callFor(connectionId) { link ->
            val address = RemoteAddress("smb", connectionId)
            val folder = SharePaths.folder(address.pathIn(path))
            val entries = link.share.list(folder).map(::entryOf).withoutDotEntries()
            rows.of(entries, address, owner = "SMB") { name -> SharePaths.child(folder, name) }
        }

    /** Copies the file at [path] into [destination] and answers with it. */
    suspend fun download(path: String, destination: File): Result<File> = onShare { share ->
        share.open(
            serverPathOf(path),
            AccessMask.GENERIC_READ,
            SMB2ShareAccess.FILE_SHARE_READ,
            SMB2CreateDisposition.FILE_OPEN
        ).use { remote ->
            destination.outputStream().use { local -> remote.read(local) }
        }
        destination
    }

    suspend fun upload(localFile: File, destinationPath: String): Result<Unit> = onShare { share ->
        share.open(
            serverPathOf(destinationPath),
            AccessMask.GENERIC_WRITE,
            SMB2ShareAccess.FILE_SHARE_WRITE,
            SMB2CreateDisposition.FILE_OVERWRITE_IF
        ).use { remote ->
            localFile.inputStream().use { local ->
                val chunk = ByteArray(UploadChunkBytes)
                var sent = 0L
                while (true) {
                    val count = local.read(chunk)
                    if (count == -1) break
                    remote.write(chunk, sent, count, 0)
                    sent += count
                }
            }
        }
    }

    /** Removes the file at [path], or the folder there together with everything in it. */
    suspend fun delete(path: String): Result<Unit> = onShare { share ->
        val target = serverPathOf(path)
        // Something that can't be looked at is tried as a file, and the share's own refusal is the answer.
        val isFolder = try {
            share.getFileInformation(target).standardInformation.isDirectory
        } catch (_: Exception) {
            false
        }
        if (isFolder) share.rmdir(target, /* recursive = */ true) else share.rm(target)
    }

    suspend fun createFolder(path: String): Result<Unit> = onShare { share ->
        share.mkdir(serverPathOf(path))
    }

    private suspend fun <T> onShare(work: (DiskShare) -> T): Result<T> =
        remoteCall { work(session.client?.share ?: throw Exception(NotConnected)) }

    /** How long a request waits for the server's answer, and how long a read may hear nothing, before giving up. */
    private fun waits(): SmbConfig = SmbConfig.builder()
        .withTimeout(WaitSeconds, TimeUnit.SECONDS)
        .withSoTimeout(WaitSeconds, TimeUnit.SECONDS)
        .build()

    private fun DiskShare.open(
        path: String,
        access: AccessMask,
        sharing: SMB2ShareAccess,
        disposition: SMB2CreateDisposition
    ): ShareFile = openFile(
        path,
        setOf(access),
        setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
        setOf(sharing),
        disposition,
        emptySet()
    )

    private fun entryOf(listed: FileIdBothDirectoryInformation) = RemoteEntry(
        name = listed.fileName,
        isFolder = EnumWithValue.EnumUtils.isSet(listed.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
        sizeBytes = listed.endOfFile,
        modifiedAt = listed.changeTime.toEpochMillis()
    )

    /** Closes each of [parts] that was opened, in the order given; one that won't close doesn't stop the rest. */
    private fun closeQuietly(vararg parts: AutoCloseable?) {
        for (part in parts) {
            try {
                part?.close()
            } catch (_: Exception) {
                // It is being let go of either way.
            }
        }
    }

    /** The three things an open share consists of, innermost last. They are closed in the reverse order. */
    private class Link(val client: SMBClient, val login: Session, val share: DiskShare)

    private companion object {
        const val WaitSeconds = 15L
        const val UploadChunkBytes = 64 * 1024
    }
}
