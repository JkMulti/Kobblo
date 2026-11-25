package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.ConnectionStatus
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FtpError
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.SftpError
import com.kobblo.gallery.file.explorer.SmbError
import com.kobblo.gallery.file.explorer.WebDavError
import com.kobblo.gallery.file.explorer.data.ftp.FtpConnection
import com.kobblo.gallery.file.explorer.data.sftp.SftpConnection
import com.kobblo.gallery.file.explorer.data.sftp.SftpStorageProvider
import com.kobblo.gallery.file.explorer.data.smb.SmbConnection
import com.kobblo.gallery.file.explorer.data.webdav.WebDavConnection
import com.kobblo.gallery.file.explorer.data.webdav.WebDavException
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConnectionsTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val live = LiveState(appState().copy(query = "kept"))
    private val prefs = MemoryPreferences()
    private val settings = settingsIn(prefs)
    private val messages = Messages(scope)
    private val shown = ArrayList<String>()
    private val opened = ArrayList<String>()

    init {
        scope.launch { messages.flow.collect(shown::add) }
    }

    private class Shelf<C>(private val idOf: (C) -> String) : SavedServers<C> {
        val connections = ArrayList<C>()
        val passwords = HashMap<String, String>()
        override fun all(): ImmutableList<C> = connections.toImmutableList()
        override fun save(connection: C, password: String) {
            connections.removeAll { idOf(it) == idOf(connection) }
            connections += connection
            passwords[idOf(connection)] = password
        }
        override fun delete(connectionId: String) {
            connections.removeAll { idOf(it) == connectionId }
            passwords.remove(connectionId)
        }
        override fun passwordOf(connectionId: String) = passwords[connectionId]
    }

    /** A server that opens or fails as it's told, and only lists for the connection that's open. */
    private class FakeLink<C>(private val idOf: (C) -> String) : ServerLink<C> {
        override var openId: String? = null
        val log = ArrayList<String>()
        var opening: Result<Any?> = Result.success(Unit)
        var downloading: Result<Any?> = Result.success(Unit)
        var rows = listOf(fileAt("smb_1:a"))

        override suspend fun open(connection: C, password: String): Result<Any?> {
            log += "open ${idOf(connection)} $password"
            if (opening.isSuccess) openId = idOf(connection)
            return opening
        }
        override suspend fun close() {
            log += "close"
            openId = null
        }
        override suspend fun list(location: String, connectionId: String): Result<List<FileItem>> {
            log += "list $location"
            return if (openId == connectionId) Result.success(rows) else Result.failure(IllegalStateException("Not connected"))
        }
        override suspend fun download(path: String, target: File): Result<Any?> {
            log += "download $path"
            return downloading
        }
        override fun dropNow() {
            log += "drop"
        }
    }

    private fun smb(id: String) = SmbConnection(id, "Attic", "nas.local", shareName = "films", username = "me", savedAt = 0)

    private val shelf = Shelf<SmbConnection> { it.id }
    private val link = FakeLink<SmbConnection> { it.id }
    private fun <C : Any, E> connections(traits: ServerTraits<C, E>, shelf: SavedServers<C>, link: ServerLink<C>) =
        ServerConnections(traits, shelf, link, scope, live, settings, messages, PlainWords, opened::add, { "fallback" })

    private val server = connections(SmbTraits, shelf, link)
    private val state get() = live.now.smbState

    @Test
    fun `what was connected last time still is, if it's still saved`() {
        shelf.save(smb("1"), "pw")
        server.restore()
        assertEquals(ConnectionStatus.Idle, state.connectionStatus)
        assertNull(state.activeConnectionId)
        assertEquals(listOf("1"), state.savedConnections.map { it.id })

        settings.setActiveServer(ServerKind.Smb, "1")
        server.restore()
        assertEquals(ConnectionStatus.Connected, state.connectionStatus)
        assertEquals("1", state.activeConnectionId)

        settings.setActiveServer(ServerKind.Smb, "gone")
        server.restore()
        assertEquals(ConnectionStatus.Idle, state.connectionStatus)
        assertNull(state.activeConnectionId)
        assertTrue(link.log.isEmpty())
    }

    @Test
    fun `connecting goes to the top of the server and remembers it`() {
        shelf.save(smb("1"), "pw")
        server.connect("1")
        assertEquals(listOf("open 1 pw"), link.log)
        assertEquals(ConnectionStatus.Connected, state.connectionStatus)
        assertEquals("1", state.activeConnectionId)
        assertNull(state.error)
        assertEquals(listOf("smb_1:/"), opened)
        assertEquals("1", prefs.values["active_smb_connection"])
        assertEquals("kept", live.now.query)
    }

    @Test
    fun `a connection that isn't saved whole is left alone`() {
        shelf.connections += smb("1")
        server.connect("1")
        server.connect("2")
        assertTrue(link.log.isEmpty())
        assertEquals(ConnectionStatus.Idle, state.connectionStatus)
    }

    @Test
    fun `a connection that fails says why, and what was in use stays in use`() {
        shelf.save(smb("1"), "pw")
        shelf.save(smb("2"), "pw")
        server.connect("1")
        link.opening = Result.failure(UnknownHostException("nas.local"))
        server.connect("2")
        assertEquals(ConnectionStatus.Error, state.connectionStatus)
        assertEquals(SmbError.HOST_UNREACHABLE, state.error)
        assertEquals("1", state.activeConnectionId)
        assertEquals(listOf("smb_1:/"), opened)
        assertEquals("1", prefs.values["active_smb_connection"])
        assertEquals(listOf(PlainWords.text(R.string.app_connect_failed)), shown)
        // Trying again takes the last problem away.
        link.opening = Result.success(Unit)
        server.connect("2")
        assertNull(state.error)
        assertEquals(ConnectionStatus.Connected, state.connectionStatus)
    }

    @Test
    fun `letting go forgets it and goes elsewhere`() {
        shelf.save(smb("1"), "pw")
        server.connect("1")
        server.disconnect()
        assertEquals(listOf("open 1 pw", "close"), link.log)
        assertEquals(ConnectionStatus.Idle, state.connectionStatus)
        assertNull(state.activeConnectionId)
        assertEquals("", state.currentPath)
        assertFalse("active_smb_connection" in prefs.values)
        assertEquals(listOf("smb_1:/", "fallback"), opened)
    }

    @Test
    fun `deleting the one in use lets go of it, and any other only goes from the list`() {
        server.add(smb("1"), "pw")
        server.add(smb("2"), "pw")
        assertEquals(listOf("1", "2"), state.savedConnections.map { it.id })
        server.connect("1")
        server.delete("2")
        assertEquals("1", state.activeConnectionId)
        server.delete("1")
        assertNull(state.activeConnectionId)
        assertTrue(state.savedConnections.isEmpty())
        assertEquals("fallback", opened.last())
    }

    @Test
    fun `listing opens a server that isn't open with what was saved`() = runBlocking {
        shelf.save(smb("1"), "pw")
        assertSame(link.rows, server.list("smb_1:Photos"))
        assertEquals(listOf("list smb_1:Photos", "open 1 pw", "list smb_1:Photos"), link.log)
        link.log.clear()
        server.list("smb_1:Photos")
        assertEquals(listOf("list smb_1:Photos"), link.log)
        // Only the link was opened: nothing on screen was asked to change.
        assertEquals(ConnectionStatus.Idle, state.connectionStatus)
    }

    private fun failureOf(location: String): String? = runBlocking {
        try {
            server.list(location)
            null
        } catch (e: Exception) {
            e.message
        }
    }

    @Test
    fun `listing fails with what's missing, or with why the server wouldn't open`() {
        assertEquals("Connection not found", failureOf("smb_9:/"))
        shelf.connections += smb("1")
        assertEquals("Password not found", failureOf("smb_1:/"))
        shelf.passwords["1"] = "pw"
        link.opening = Result.failure(ConnectException("refused"))
        assertEquals("refused", failureOf("smb_1:/"))
    }

    @Test
    fun `downloading opens its server first, unless it's already open`() = runBlocking {
        shelf.save(smb("1"), "pw")
        assertTrue(server.download("smb_1:a", File("a")))
        assertTrue(server.download("smb_1:b", File("b")))
        assertEquals(listOf("open 1 pw", "download smb_1:a", "download smb_1:b"), link.log)
        link.downloading = Result.failure(Exception())
        assertFalse(server.download("smb_1:c", File("c")))
        // With nothing saved to open it with, the download itself is left to fail.
        link.log.clear()
        assertFalse(server.download("smb_9:c", File("c")))
        assertEquals(listOf("download smb_9:c"), link.log)
    }

    @Test
    fun `a nextcloud is gone to by its own scheme`() {
        val dav = Shelf<WebDavConnection> { it.id }
        val davServer = connections(WebDavTraits, dav, FakeLink { it.id })
        dav.save(WebDavConnection("4", "", "https://dav.example.org/", "me"), "pw")
        dav.save(WebDavConnection("5", "", "https://cloud.example.org/", "me", isNextcloud = true), "pw")
        davServer.restore()
        assertNull(davServer.activeTop(live.now))
        davServer.connect("4")
        assertEquals("webdav_4:/", davServer.activeTop(live.now))
        davServer.connect("5")
        assertEquals("nextcloud_5:/", davServer.activeTop(live.now))
        assertEquals(listOf("webdav_4:/", "nextcloud_5:/"), opened)
        assertEquals("5", prefs.values["active_webdav_connection"])
        // A connection whose details have gone still has a top to go to.
        live.update { it.copy(webDavState = it.webDavState.copy(savedConnections = persistentListOf())) }
        assertEquals("webdav_5:/", davServer.activeTop(live.now))
    }

    @Test
    fun `an sftp server's key is pinned the first time and never again`() {
        val pinned = ArrayList<Pair<String, String>>()
        val sftp = Shelf<SftpConnection> { it.id }
        val sftpLink = FakeLink<SftpConnection> { it.id }
        val sftpServer = connections(SftpTraits { id, key -> pinned += id to key }, sftp, sftpLink)
        fun saved(id: String, key: String?) =
            SftpConnection(id = id, displayName = "Box", host = "10.0.0.2", username = "me", hostKeyFingerprint = key, savedAt = 0)
        sftp.save(saved("1", null), "pw")
        sftp.save(saved("2", "known"), "pw")
        sftpLink.opening = Result.success(SftpStorageProvider.ConnectResult("aa:bb"))
        sftpServer.connect("1")
        sftpServer.connect("2")
        assertEquals(listOf("1" to "aa:bb"), pinned)
        sftpLink.opening = Result.success(SftpStorageProvider.ConnectResult(""))
        sftpServer.connect("1")
        assertEquals(1, pinned.size)
    }

    @Test
    fun `each kind keeps to its own part of the state`() {
        val ftp = Shelf<FtpConnection> { it.id }
        val ftpServer = connections(FtpTraits, ftp, FakeLink { it.id })
        val before: AppState = live.now
        ftpServer.add(FtpConnection(id = "2", displayName = "", host = "ftp.example.org", username = "me", savedAt = 0), "pw")
        assertEquals(before, live.now.copy(ftpState = before.ftpState))
        assertEquals(1, live.now.ftpState.savedConnections.size)
    }

    @Test
    fun `failures are explained by what they are, then by what they say`() {
        val unreachable = listOf(UnknownHostException(), ConnectException())
        val late = listOf(SocketTimeoutException("Login"), TimeoutException("Auth"))
        fun says(text: String) = Exception("xx $text yy")
        unreachable.forEach {
            assertEquals(SmbError.HOST_UNREACHABLE, SmbTraits.errorOf(it))
            assertEquals(FtpError.HOST_UNREACHABLE, FtpTraits.errorOf(it))
            assertEquals(SftpError.HOST_UNREACHABLE, sftpTraits.errorOf(it))
            assertEquals(WebDavError.HOST_UNREACHABLE, WebDavTraits.errorOf(it))
        }
        late.forEach {
            assertEquals(SmbError.TIMEOUT, SmbTraits.errorOf(it))
            assertEquals(FtpError.TIMEOUT, FtpTraits.errorOf(it))
            assertEquals(SftpError.TIMEOUT, sftpTraits.errorOf(it))
            assertEquals(WebDavError.UNKNOWN, WebDavTraits.errorOf(it))
        }
        fun <E> ServerTraits<*, E>.explain(error: E, vararg texts: String) =
            texts.forEach { assertEquals(it, error, errorOf(says(it))) }
        SmbTraits.explain(SmbError.AUTH_FAILED, "LOGON_FAILURE", "Access denied", "Authentication")
        SmbTraits.explain(SmbError.SHARE_NOT_FOUND, "BAD_NETWORK_NAME", "not found")
        FtpTraits.explain(FtpError.AUTH_FAILED, "Authentication", "Login", "530")
        sftpTraits.explain(SftpError.AUTH_FAILED, "Auth", "password", "publickey")
        sftpTraits.explain(SftpError.HOST_KEY_MISMATCH, "HostKeyChanged", "fingerprint")
        assertEquals(WebDavError.AUTH_FAILED, WebDavTraits.errorOf(WebDavException.AuthFailed()))
        assertEquals(WebDavError.NOT_FOUND, WebDavTraits.errorOf(WebDavException.NotFound("/x")))
        listOf(says("something"), Exception(), null).forEach {
            assertEquals(SmbError.UNKNOWN, SmbTraits.errorOf(it))
            assertEquals(FtpError.UNKNOWN, FtpTraits.errorOf(it))
            assertEquals(SftpError.UNKNOWN, sftpTraits.errorOf(it))
            assertEquals(WebDavError.UNKNOWN, WebDavTraits.errorOf(it))
        }
    }

    private val sftpTraits = SftpTraits { _, _ -> }

    /** A provider as the real ones are: calls that answer with a [Result] and have no type in common. */
    private class Provider {
        var current: String? = null
        val log = ArrayList<String>()
        suspend fun connect(connection: String, password: String): Result<Unit> {
            log += "connect $connection $password"
            return if (password == "bad") Result.failure(ConnectException("no")) else Result.success(Unit)
        }
        fun disconnect() {
            log += "disconnect"
        }
        suspend fun listFiles(path: String, connectionId: String): Result<List<FileItem>> =
            if (connectionId == "1") Result.success(listOf(fileAt(path))) else Result.failure(Exception("Not connected"))
        suspend fun download(path: String, target: File): Result<File> =
            if (path == "ok") Result.success(target) else Result.failure(Exception("gone"))
    }

    @Test
    fun `a provider's answers come through its link as they are`() = runBlocking {
        val provider = Provider()
        val real = ProviderLink<String>(
            currentId = provider::current,
            connect = provider::connect,
            disconnect = { provider.disconnect() },
            listFiles = provider::listFiles,
            fetch = provider::download,
            drop = provider::disconnect
        )
        assertNull(real.openId)
        provider.current = "1"
        assertEquals("1", real.openId)
        assertTrue(real.open("c", "pw").isSuccess)
        assertEquals("no", real.open("c", "bad").exceptionOrNull()?.message)
        assertEquals(listOf("p"), real.list("p", "1").getOrThrow().map { it.path })
        assertEquals("Not connected", real.list("p", "2").exceptionOrNull()?.message)
        assertEquals(File("f"), real.download("ok", File("f")).getOrNull())
        assertEquals("gone", real.download("x", File("f")).exceptionOrNull()?.message)
        real.close()
        real.dropNow()
        assertEquals(listOf("connect c pw", "connect c bad", "disconnect", "disconnect"), provider.log)
    }
}
