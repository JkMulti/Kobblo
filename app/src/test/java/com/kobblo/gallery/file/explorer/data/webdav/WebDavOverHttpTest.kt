package com.kobblo.gallery.file.explorer.data.webdav

import com.kobblo.gallery.file.explorer.FileItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * [WebDavClient] and [WebDavStorageProvider] in conversation with a small WebDAV server that lives inside
 * the test. It speaks plain HTTP, so what a certificate does to a connection is left to a real server.
 * Uploads are left to one as well: the library sends them with a part of Android that isn't there off a
 * phone.
 */
class WebDavOverHttpTest {

    @get:Rule
    val giveUp: Timeout = Timeout.seconds(60)

    @get:Rule
    val scratch = TemporaryFolder()

    private val server = TinyDavServer()
    private val provider = WebDavStorageProvider()

    @After
    fun stop() = server.close()

    private fun connection(
        address: String = server.address,
        id: String = "c1",
        acceptAnyCertificate: Boolean = false
    ) = WebDavConnection(id, "Test", address, TinyDavServer.User, allowSelfSignedCert = acceptAnyCertificate)

    private fun client(address: String = server.address) = WebDavClient(connection(address), TinyDavServer.Password)

    private fun connected() = runBlocking {
        provider.connect(connection(), TinyDavServer.Password).getOrThrow()
        server.heard.clear()
    }

    @Test
    fun `the right password is let in by asking for the connection's own folder`(): Unit = runBlocking {
        assertTrue(client().testConnection())
        assertEquals(listOf("PROPFIND /dav/"), server.heard)
    }

    @Test
    fun `the address works the same with a slash at its end`(): Unit = runBlocking {
        assertTrue(client("${server.address}/").testConnection())
        assertEquals(listOf("PROPFIND /dav/"), server.heard)
    }

    @Test
    fun `a wrong password is told apart as an authentication failure`(): Unit = runBlocking {
        val stranger = WebDavClient(connection(), "not the password")

        try {
            stranger.testConnection()
            fail("A wrong password was let in")
        } catch (expected: WebDavException.AuthFailed) {
            assertEquals("Incorrect username or password", expected.message)
        }
    }

    @Test
    fun `a server that is not there can't be reached`(): Unit = runBlocking {
        server.close()

        try {
            client().testConnection()
            fail("A closed port answered")
        } catch (expected: WebDavException.ServerUnreachable) {
            assertEquals("Couldn't reach the server", expected.message)
        }
    }

    @Test
    fun `a connection that accepts any certificate still works`(): Unit = runBlocking {
        val trusting = WebDavClient(connection(acceptAnyCertificate = true), TinyDavServer.Password)

        assertTrue(trusting.testConnection())
    }

    @Test
    fun `a listing is what is in the folder, without the folder itself`(): Unit = runBlocking {
        server.folders += "/dav/Photos/"
        server.files["/dav/My%20notes.txt"] = "hello".toByteArray()
        server.files["/dav/Photos/beach.jpg"] = ByteArray(3)

        val listed = client().list("")

        assertEquals(
            listOf(
                // A folder has no length of its own, and the library fills in what it has for that.
                WebDavEntry("Photos", "/dav/Photos/", true, -1, TinyDavServer.ModifiedAt, "httpd/unix-directory"),
                WebDavEntry("My notes.txt", "/dav/My%20notes.txt", false, 5, TinyDavServer.ModifiedAt, "text/plain")
            ),
            listed
        )
    }

    @Test
    fun `a path is added to the address with or without a slash in front`(): Unit = runBlocking {
        server.folders += "/dav/Photos/"
        server.files["/dav/Photos/beach.jpg"] = ByteArray(3)

        assertEquals(listOf("beach.jpg"), client().list("Photos").map { it.name })
        assertEquals(listOf("beach.jpg"), client().list("/Photos").map { it.name })
        assertEquals(listOf("PROPFIND /dav/Photos", "PROPFIND /dav/Photos"), server.heard)
    }

    @Test
    fun `a download fills the file it was given and hands it back`(): Unit = runBlocking {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        server.files["/dav/data.bin"] = bytes
        val destination = scratch.newFile()

        assertSame(destination, client().download("data.bin", destination))
        assertArrayEquals(bytes, destination.readBytes())
    }

    @Test
    fun `a file that is not there is told apart as not found`(): Unit = runBlocking {
        try {
            client().download("never-uploaded.txt", scratch.newFile())
            fail("A file came out of nowhere")
        } catch (expected: WebDavException.NotFound) {
            assertTrue(expected.message, expected.message!!.startsWith("Not found: "))
        }
    }

    @Test
    fun `folders are made, files moved and things removed by their own requests`(): Unit = runBlocking {
        server.files["/dav/old.txt"] = ByteArray(1)
        val dav = client()

        dav.createFolder("Archive")
        dav.move("old.txt", "Archive/older.txt")
        dav.delete("Archive/older.txt")
        dav.delete("Archive")

        assertEquals(
            listOf("MKCOL /dav/Archive", "MOVE /dav/old.txt", "DELETE /dav/Archive/older.txt", "DELETE /dav/Archive"),
            server.heard
        )
        assertTrue(server.files.isEmpty())
        assertEquals(setOf("/dav/"), server.folders.toSet())
    }

    @Test
    fun `connecting remembers the connection, and a wrong password does not`(): Unit = runBlocking {
        assertTrue(provider.connect(connection(), TinyDavServer.Password).isSuccess)
        assertEquals("c1", provider.currentConnectionId)

        val refused = provider.connect(connection(id = "c2"), "not the password")

        assertTrue("${refused.exceptionOrNull()}", refused.exceptionOrNull() is WebDavException.AuthFailed)
        assertNull(provider.currentConnectionId)
        assertEquals("Not connected", provider.listFiles("webdav_c1:/", "c1").exceptionOrNull()?.message)
    }

    @Test
    fun `a listing becomes rows under the name the connection goes by`(): Unit = runBlocking {
        server.folders += "/dav/Photos/"
        server.files["/dav/My%20notes.txt"] = "hello".toByteArray()
        connected()

        val asWebDav = provider.listFiles("webdav_c1:/", "c1").getOrThrow()
        val asNextcloud = provider.listFiles("nextcloud_c1:/", "c1").getOrThrow()

        assertEquals(
            listOf(
                FileItem(
                    id = "/Photos".hashCode(), path = "webdav_c1:/Photos", name = "Photos",
                    type = "folder", kind = "Folder", size = "", changed = "", owner = "WebDAV",
                    sizeBytes = -1, lastModified = TinyDavServer.ModifiedAt
                ),
                FileItem(
                    id = "/My notes.txt".hashCode(), path = "webdav_c1:/My notes.txt", name = "My notes.txt",
                    type = "document", kind = "Document", size = "5 B", changed = "", owner = "WebDAV",
                    sizeBytes = 5, lastModified = TinyDavServer.ModifiedAt
                )
            ),
            // The date is written the way the machine running the test writes dates.
            asWebDav.map { it.copy(changed = "") }
        )
        assertEquals(listOf("nextcloud_c1:/Photos", "nextcloud_c1:/My notes.txt"), asNextcloud.map { it.path })
        assertEquals(listOf("Nextcloud", "Nextcloud"), asNextcloud.map { it.owner })
        assertEquals(asWebDav.map { it.id }, asNextcloud.map { it.id })
    }

    @Test
    fun `the top folder is listed however its address is written`(): Unit = runBlocking {
        server.files["/dav/readme.txt"] = ByteArray(2)
        connected()

        val bare = provider.listFiles("webdav_c1:", "c1").getOrThrow()
        val slashed = provider.listFiles("webdav_c1:/", "c1").getOrThrow()

        assertEquals(listOf("webdav_c1:/readme.txt"), bare.map { it.path })
        assertEquals(bare, slashed)
        assertEquals(listOf("PROPFIND /dav/", "PROPFIND /dav/"), server.heard)
    }

    @Test
    fun `a folder with a space in its name is opened from its row`(): Unit = runBlocking {
        server.folders += "/dav/Summer%202024/"
        server.files["/dav/Summer%202024/beach.jpg"] = ByteArray(3)
        connected()

        val folder = provider.listFiles("webdav_c1:/", "c1").getOrThrow().single()
        val inside = provider.listFiles(folder.path, "c1").getOrThrow()

        assertEquals("webdav_c1:/Summer 2024", folder.path)
        assertEquals(listOf("webdav_c1:/Summer 2024/beach.jpg"), inside.map { it.path })
        assertEquals("PROPFIND /dav/Summer%202024", server.heard.last())
    }

    @Test
    fun `a listing for another connection is not asked of this server`(): Unit = runBlocking {
        connected()

        assertEquals("Not connected", provider.listFiles("webdav_c2:/", "c2").exceptionOrNull()?.message)
        assertTrue(server.heard.isEmpty())
    }

    @Test
    fun `a file is fetched by its address, or by its path alone`(): Unit = runBlocking {
        val bytes = ByteArray(1000) { it.toByte() }
        server.files["/dav/Photos/data.bin"] = bytes
        val first = scratch.newFile()
        val second = scratch.newFile()
        connected()

        assertTrue(provider.download("webdav_c1:/Photos/data.bin", first).isSuccess)
        assertTrue(provider.download("/Photos/data.bin", second).isSuccess)

        assertArrayEquals(bytes, first.readBytes())
        assertArrayEquals(bytes, second.readBytes())
        assertEquals(listOf("GET /dav/Photos/data.bin", "GET /dav/Photos/data.bin"), server.heard)
    }

    @Test
    fun `a download that fails keeps the client's reason`(): Unit = runBlocking {
        connected()

        val failure = provider.download("webdav_c1:/missing.txt", scratch.newFile()).exceptionOrNull()

        assertTrue("$failure", failure is WebDavException.NotFound)
    }

    @Test
    fun `folders are made by name inside their parent, and removed by address`(): Unit = runBlocking {
        connected()

        assertTrue(provider.createFolder("webdav_c1:/", "Archive").isSuccess)
        assertTrue(provider.createFolder("webdav_c1:/Archive", "2024").isSuccess)
        assertTrue(provider.delete("webdav_c1:/Archive/2024").isSuccess)

        assertEquals(listOf("MKCOL /dav/Archive", "MKCOL /dav/Archive/2024", "DELETE /dav/Archive/2024"), server.heard)
        assertEquals(setOf("/dav/", "/dav/Archive/"), server.folders.toSet())
    }

    @Test
    fun `disconnecting forgets the connection without a word to the server`(): Unit = runBlocking {
        connected()

        provider.disconnect()

        assertNull(provider.currentConnectionId)
        assertFalse(provider.download("webdav_c1:/a.txt", scratch.newFile()).isSuccess)
        assertTrue(server.heard.isEmpty())
    }
}

/**
 * Just enough of a WebDAV server to list, fetch, move, remove and make folders, for one user with one
 * password. Paths are kept the way they arrive, percent-encoded.
 */
private class TinyDavServer : Closeable {
    private val listening = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))

    val address: String get() = "http://127.0.0.1:${listening.localPort}/dav"

    /** Every request that came with the right password, as its method and path. */
    val heard = CopyOnWriteArrayList<String>()
    val folders: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add("/dav/") }
    val files = ConcurrentHashMap<String, ByteArray>()

    init {
        thread(isDaemon = true, name = "tiny-dav") {
            runCatching {
                while (true) {
                    val client = listening.accept()
                    thread(isDaemon = true) { runCatching { client.use(::answer) } }
                }
            }
        }
    }

    override fun close() = listening.close()

    private fun answer(client: Socket) {
        client.soTimeout = 30_000
        val input = DataInputStream(client.getInputStream().buffered())
        val (method, path) = input.line()?.split(' ') ?: return
        val headers = generateSequence { input.line()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        // Whatever came with the request is read off the connection, and not looked at.
        input.readFully(ByteArray(headers["content-length"]?.toInt() ?: 0))

        fun reply(status: String, content: ByteArray = ByteArray(0), extraHeader: String = "") {
            val head = "HTTP/1.1 $status\r\nContent-Length: ${content.size}\r\nConnection: close\r\n$extraHeader\r\n"
            client.getOutputStream().run {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(content)
                flush()
            }
        }

        if (headers["authorization"] != Expected) {
            return reply("401 Unauthorized", extraHeader = "WWW-Authenticate: Basic realm=\"test\"\r\n")
        }
        heard += "$method $path"

        val asFolder = if (path.endsWith("/")) path else "$path/"
        when (method) {
            "PROPFIND" -> when {
                asFolder in folders -> reply("207 Multi-Status", listing(asFolder), "Content-Type: text/xml\r\n")
                files.containsKey(path) ->
                    reply("207 Multi-Status", multistatus(fileEntry(path)), "Content-Type: text/xml\r\n")
                else -> reply("404 Not Found")
            }
            "GET" -> files[path]?.let { reply("200 OK", it) } ?: reply("404 Not Found")
            "MKCOL" -> {
                folders += asFolder
                reply("201 Created")
            }
            "MOVE" -> {
                val moved = files.remove(path) ?: return reply("404 Not Found")
                files[URI(headers.getValue("destination")).rawPath] = moved
                reply("201 Created")
            }
            "DELETE" -> when {
                files.remove(path) != null -> reply("204 No Content")
                folders.remove(asFolder) -> {
                    folders.removeIf { it.startsWith(asFolder) }
                    files.keys.removeIf { it.startsWith(asFolder) }
                    reply("204 No Content")
                }
                else -> reply("404 Not Found")
            }
            else -> reply("405 Method Not Allowed")
        }
    }

    /** The folder first, as WebDAV has it, then what lies directly in it in name order. */
    private fun listing(folder: String): ByteArray {
        val inside = folders.filter { it != folder && parentOf(it) == folder }.sorted().map(::folderEntry) +
            files.keys.filter { parentOf(it) == folder }.sorted().map(::fileEntry)
        return multistatus(folderEntry(folder) + inside.joinToString(""))
    }

    private fun parentOf(path: String) = path.trimEnd('/').substringBeforeLast('/') + "/"

    private fun folderEntry(path: String) =
        entry(path, "<D:resourcetype><D:collection/></D:resourcetype>")

    private fun fileEntry(path: String) = entry(
        path,
        "<D:resourcetype/><D:getcontentlength>${files.getValue(path).size}</D:getcontentlength>" +
            "<D:getcontenttype>text/plain</D:getcontenttype>"
    )

    private fun entry(path: String, properties: String) =
        "<D:response><D:href>$path</D:href><D:propstat><D:prop>$properties" +
            "<D:getlastmodified>Tue, 14 Nov 2023 22:13:20 GMT</D:getlastmodified>" +
            "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"

    private fun multistatus(entries: String) =
        """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">$entries</D:multistatus>""".toByteArray()

    /** One line of the request's head, without its line ending, or null when the client has gone. */
    private fun InputStream.line(): String? {
        val text = StringBuilder()
        while (true) {
            when (val byte = read()) {
                -1 -> return if (text.isEmpty()) null else text.toString()
                '\n'.code -> return text.toString().trimEnd('\r')
                else -> text.append(byte.toChar())
            }
        }
    }

    companion object {
        const val User = "sam"
        const val Password = "secret"

        /** The moment every entry says it was last changed: 14 November 2023, 22:13:20 UTC. */
        const val ModifiedAt = 1_700_000_000_000L

        private val Expected = "Basic " + Base64.getEncoder().encodeToString("$User:$Password".toByteArray())
    }
}
