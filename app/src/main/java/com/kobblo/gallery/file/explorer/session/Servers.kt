package com.kobblo.gallery.file.explorer.session

import android.content.Context
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FtpError
import com.kobblo.gallery.file.explorer.SftpError
import com.kobblo.gallery.file.explorer.SmbError
import com.kobblo.gallery.file.explorer.WebDavError
import com.kobblo.gallery.file.explorer.data.ftp.FtpConnection
import com.kobblo.gallery.file.explorer.data.ftp.FtpStorageProvider
import com.kobblo.gallery.file.explorer.data.ftp.FtpStore
import com.kobblo.gallery.file.explorer.data.remote.SavedConnections
import com.kobblo.gallery.file.explorer.data.sftp.SftpConnection
import com.kobblo.gallery.file.explorer.data.sftp.SftpStorageProvider
import com.kobblo.gallery.file.explorer.data.sftp.SftpStore
import com.kobblo.gallery.file.explorer.data.smb.SmbConnection
import com.kobblo.gallery.file.explorer.data.smb.SmbStorageProvider
import com.kobblo.gallery.file.explorer.data.smb.SmbStore
import com.kobblo.gallery.file.explorer.data.webdav.WebDavConnection
import com.kobblo.gallery.file.explorer.data.webdav.WebDavStorageProvider
import com.kobblo.gallery.file.explorer.data.webdav.WebDavStore
import java.io.File

/** Every kind of server, found by the location something is at or by the action a screen sends. */
internal class Servers(
    private val smb: ServerConnections<SmbConnection, SmbError>,
    private val ftp: ServerConnections<FtpConnection, FtpError>,
    private val sftp: ServerConnections<SftpConnection, SftpError>,
    private val webDav: ServerConnections<WebDavConnection, WebDavError>
) {
    /** In the order the cloud tab looks for one that's connected. */
    private val all = listOf(smb, ftp, sftp, webDav)

    fun restore() = all.forEach { it.restore() }

    fun dropNow() = all.forEach { it.dropNow() }

    /** The top of the first server that's connected, or null when none is. */
    fun connectedTop(state: AppState): String? = all.firstNotNullOfOrNull { it.activeTop(state) }

    suspend fun list(kind: ServerKind, location: String): List<FileItem> = of(kind).list(location)

    suspend fun download(kind: ServerKind, path: String, target: File): Boolean = of(kind).download(path, target)

    private fun of(kind: ServerKind) = all.first { it.kind == kind }

    fun handle(action: AppAction.SmbAction) = when (action) {
        is AppAction.SmbAction.AddConnection -> smb.add(action.connection, action.password)
        is AppAction.SmbAction.DeleteConnection -> smb.delete(action.connectionId)
        is AppAction.SmbAction.Connect -> smb.connect(action.connectionId)
        is AppAction.SmbAction.Disconnect -> smb.disconnect()
        is AppAction.SmbAction.NavigateTo -> smb.goTo(action.path)
        // How a connection went shows in its status, so there's nothing to do about being told.
        is AppAction.SmbAction.ConnectSucceeded, is AppAction.SmbAction.ConnectFailed -> Unit
    }

    fun handle(action: AppAction.FtpAction) = when (action) {
        is AppAction.FtpAction.AddConnection -> ftp.add(action.connection, action.password)
        is AppAction.FtpAction.DeleteConnection -> ftp.delete(action.connectionId)
        is AppAction.FtpAction.Connect -> ftp.connect(action.connectionId)
        is AppAction.FtpAction.Disconnect -> ftp.disconnect()
        is AppAction.FtpAction.NavigateTo -> ftp.goTo(action.path)
    }

    fun handle(action: AppAction.SftpAction) = when (action) {
        is AppAction.SftpAction.AddConnection -> sftp.add(action.connection, action.password)
        is AppAction.SftpAction.DeleteConnection -> sftp.delete(action.connectionId)
        is AppAction.SftpAction.Connect -> sftp.connect(action.connectionId)
        is AppAction.SftpAction.Disconnect -> sftp.disconnect()
        is AppAction.SftpAction.NavigateTo -> sftp.goTo(action.path)
    }

    fun handle(action: AppAction.WebDavAction) = when (action) {
        is AppAction.WebDavAction.AddConnection -> webDav.add(action.connection, action.password)
        is AppAction.WebDavAction.DeleteConnection -> webDav.delete(action.connectionId)
        is AppAction.WebDavAction.Connect -> webDav.connect(action.connectionId)
        is AppAction.WebDavAction.Disconnect -> webDav.disconnect()
        is AppAction.WebDavAction.NavigateTo -> webDav.goTo(action.path)
    }

    /** Puts the connections of each kind together: what's particular to it, what it has saved and its one open server. */
    interface Maker {
        fun <C : Any, E> make(traits: ServerTraits<C, E>, saved: SavedServers<C>, link: ServerLink<C>): ServerConnections<C, E>
    }

    companion object {
        fun of(context: Context, maker: Maker): Servers {
            val smb = SmbStorageProvider()
            val ftp = FtpStorageProvider()
            val sftp = SftpStorageProvider()
            val webDav = WebDavStorageProvider()
            val sftpStore = SftpStore(context)
            return Servers(
                smb = maker.make(
                    SmbTraits,
                    SavedInApp(SmbStore(context)),
                    ProviderLink(
                        currentId = smb::currentConnectionId,
                        connect = smb::connect,
                        disconnect = { smb.disconnect() },
                        listFiles = smb::listFiles,
                        fetch = smb::download
                    )
                ),
                ftp = maker.make(
                    FtpTraits,
                    SavedInApp(FtpStore(context)),
                    ProviderLink(
                        currentId = ftp::currentConnectionId,
                        connect = ftp::connect,
                        disconnect = { ftp.disconnect() },
                        listFiles = ftp::listFiles,
                        fetch = ftp::download,
                        drop = ftp::disconnect
                    )
                ),
                sftp = maker.make(
                    SftpTraits(sftpStore::updateHostKeyFingerprint),
                    SavedInApp(sftpStore),
                    ProviderLink(
                        currentId = sftp::currentConnectionId,
                        connect = sftp::connect,
                        disconnect = { sftp.disconnect() },
                        listFiles = sftp::listFiles,
                        fetch = sftp::download,
                        drop = sftp::disconnect
                    )
                ),
                webDav = maker.make(
                    WebDavTraits,
                    SavedInApp(WebDavStore(context)),
                    ProviderLink(
                        currentId = webDav::currentConnectionId,
                        connect = webDav::connect,
                        disconnect = { webDav.disconnect() },
                        listFiles = webDav::listFiles,
                        fetch = webDav::download
                    )
                )
            )
        }
    }
}

private class SavedInApp<C : Any>(private val store: SavedConnections<C>) : SavedServers<C> {
    override fun all() = store.getConnections()
    override fun save(connection: C, password: String) = store.saveConnection(connection, password)
    override fun delete(connectionId: String) = store.deleteConnection(connectionId)
    override fun passwordOf(connectionId: String) = store.getPassword(connectionId)
}

/** A storage provider as a link. The four have the same calls, but no type in common to make them through. */
internal class ProviderLink<C>(
    private val currentId: () -> String?,
    private val connect: suspend (C, String) -> Result<Any?>,
    private val disconnect: suspend () -> Unit,
    private val listFiles: suspend (String, String) -> Result<List<FileItem>>,
    private val fetch: suspend (String, File) -> Result<Any?>,
    private val drop: () -> Unit = {}
) : ServerLink<C> {
    override val openId: String? get() = currentId()
    override suspend fun open(connection: C, password: String) = connect(connection, password)
    override suspend fun close() = disconnect()
    override suspend fun list(location: String, connectionId: String) = listFiles(location, connectionId)
    override suspend fun download(path: String, target: File) = fetch(path, target)
    override fun dropNow() = drop()
}
