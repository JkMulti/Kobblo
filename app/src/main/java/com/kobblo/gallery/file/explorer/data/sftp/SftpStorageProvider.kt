package com.kobblo.gallery.file.explorer.data.sftp

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.data.remote.RemoteAddress
import com.kobblo.gallery.file.explorer.data.remote.RemoteEntry
import com.kobblo.gallery.file.explorer.data.remote.RemoteRows
import com.kobblo.gallery.file.explorer.data.remote.RemoteSession
import com.kobblo.gallery.file.explorer.data.remote.SlashPaths
import com.kobblo.gallery.file.explorer.data.remote.remoteCall
import com.kobblo.gallery.file.explorer.data.remote.serverPathOf
import com.kobblo.gallery.file.explorer.data.remote.withoutDotEntries
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.FingerprintVerifier
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.File
import java.security.PublicKey

/**
 * Files on a server reached over SSH. One server is open at a time; connecting to another lets go of the
 * first.
 *
 * Every call answers with a [Result] and does its work off the main thread, apart from [disconnect].
 */
class SftpStorageProvider {

    private val session = RemoteSession<Link>()
    private val rows = RemoteRows()

    /** The saved connection that is open, or null. */
    val currentConnectionId: String? get() = session.connectionId

    /**
     * What a successful [connect] hands back: the fingerprint of the host key the server showed. For a
     * connection that had none pinned, this is the one to pin.
     */
    data class ConnectResult(val observedFingerprint: String)

    /**
     * Opens [connection]. [password] is the account's and goes unused when the connection signs in with
     * a key.
     */
    suspend fun connect(connection: SftpConnection, password: String): Result<ConnectResult> = remoteCall {
        disconnect()
        val ssh = SSHClient()
        ssh.connectTimeout = ConnectTimeoutMillis

        val pinned = connection.hostKeyFingerprint
        val firstUse = if (pinned == null) TrustOnFirstUse() else null
        ssh.addHostKeyVerifier(firstUse ?: FingerprintVerifier.getInstance(pinned))

        ssh.connect(connection.host, connection.port)
        signIn(ssh, connection, password)
        session.open(connection.id, Link(ssh, openFiles(ssh)))

        ConnectResult(firstUse?.fingerprint ?: pinned.orEmpty())
    }

    fun disconnect() {
        val link = session.close() ?: return
        try {
            link.files.close()
            link.ssh.disconnect()
        } catch (_: Exception) {
            // The server may be long gone, and there is nobody left to tell.
        }
    }

    suspend fun listFiles(path: String, connectionId: String): Result<List<FileItem>> =
        session.callFor(connectionId) { link ->
            val address = RemoteAddress("sftp", connectionId)
            val folder = SlashPaths.folder(address.pathIn(path))
            val entries = link.files.ls(folder).map(::entryOf).withoutDotEntries()
            rows.of(entries, address, owner = "SFTP") { name -> SlashPaths.child(folder, name) }
        }

    suspend fun download(remotePath: String, localFile: File): Result<Unit> = session.call { link ->
        link.files.get(serverPathOf(remotePath), localFile.absolutePath)
    }

    suspend fun upload(localFile: File, remotePath: String): Result<Unit> = session.call { link ->
        link.files.put(localFile.absolutePath, serverPathOf(remotePath))
    }

    /** SFTP removes files and folders with different requests, so the caller says which [remotePath] is. */
    suspend fun delete(remotePath: String, isDirectory: Boolean): Result<Unit> = session.call { link ->
        val target = serverPathOf(remotePath)
        if (isDirectory) link.files.rmdir(target) else link.files.rm(target)
    }

    /** Makes the folder [name] inside [path]. */
    suspend fun createFolder(path: String, name: String): Result<Unit> = session.call { link ->
        link.files.mkdir(SlashPaths.child(serverPathOf(path), name))
    }

    private fun signIn(ssh: SSHClient, connection: SftpConnection, password: String) {
        when (connection.authType) {
            SftpAuthType.PASSWORD -> ssh.authPassword(connection.username, password)
            SftpAuthType.KEY -> {
                val unlock = connection.privateKeyPassphrase
                    .takeIf { it.isNotBlank() }
                    ?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                // The key is held as text, so it is read from the text; there is no public half to go with it.
                ssh.authPublickey(connection.username, ssh.loadKeys(connection.privateKeyPem, null, unlock))
            }
        }
    }

    /** Starts the file-transfer side of a signed-in session, which is no use to anyone if that fails. */
    private fun openFiles(ssh: SSHClient): SFTPClient = try {
        ssh.newSFTPClient()
    } catch (failure: Exception) {
        runCatching { ssh.disconnect() }
        throw failure
    }

    private fun entryOf(listed: RemoteResourceInfo): RemoteEntry {
        val attributes = listed.attributes
        return RemoteEntry(
            name = listed.name,
            isFolder = attributes.type == FileMode.Type.DIRECTORY,
            sizeBytes = attributes.size,
            // The server counts in seconds.
            modifiedAt = attributes.mtime * 1000L
        )
    }

    /** The SSH connection and the file-transfer channel running over it; they open and close together. */
    private class Link(val ssh: SSHClient, val files: SFTPClient)

    /**
     * The host key check for a server seen for the first time: whichever key it shows is accepted, and its
     * fingerprint kept so the connection can be held to that key from then on.
     */
    private class TrustOnFirstUse : HostKeyVerifier {
        @Volatile
        var fingerprint: String = ""
            private set

        override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
            if (key != null) fingerprint = SecurityUtils.getFingerprint(key)
            return true
        }

        // No key of this server's is known yet, so there is no algorithm to ask it to prefer.
        override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> = mutableListOf()
    }

    private companion object {
        const val ConnectTimeoutMillis = 10_000
    }
}
