package com.kobblo.gallery.file.explorer.data.ftp

import com.kobblo.gallery.file.explorer.FileItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.Closeable
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * [FtpStorageProvider] in conversation with a small FTP server that lives inside the test. It covers
 * plain FTP in both data modes; FTPS needs a real server with a certificate.
 */
class FtpStorageProviderTest {

    @get:Rule
    val giveUp: Timeout = Timeout.seconds(60)

    @get:Rule
    val scratch = TemporaryFolder()

    private val servers = mutableListOf<TinyFtpServer>()
    private val provider = FtpStorageProvider()

    @After
    fun hangUp() {
        provider.disconnect()
        servers.forEach { it.close() }
    }

    private fun server(greeting: String = "220 Ready") = TinyFtpServer(greeting).also { servers += it }

    private fun connectionTo(server: TinyFtpServer, id: String = "c1", passive: Boolean = true) = FtpConnection(
        id = id,
        displayName = "Test",
        host = "127.0.0.1",
        port = server.port,
        username = "sam",
        passiveMode = passive,
        savedAt = 0
    )

    private fun signedInTo(server: TinyFtpServer, passive: Boolean = true) = runBlocking {
        provider.connect(connectionTo(server, passive = passive), TinyFtpServer.Password).getOrThrow()
        server.heard.clear()
    }

    @Test
    fun `signing in asks for binary transfers and remembers the connection`(): Unit = runBlocking {
        val server = server()

        val result = provider.connect(connectionTo(server), TinyFtpServer.Password)

        assertTrue(result.isSuccess)
        assertEquals("c1", provider.currentConnectionId)
        assertEquals(listOf("USER sam", "PASS ${TinyFtpServer.Password}", "TYPE I"), server.heard)
    }

    @Test
    fun `a wrong password is an authentication failure`(): Unit = runBlocking {
        val result = provider.connect(connectionTo(server()), "not the password")

        assertEquals("Authentication failed", result.exceptionOrNull()?.message)
        assertNull(provider.currentConnectionId)
    }

    @Test
    fun `a server that answers only to turn the phone away is reported with its code`(): Unit = runBlocking {
        val result = provider.connect(connectionTo(server(greeting = "554 No service here")), TinyFtpServer.Password)

        assertEquals("Server refused connection (554)", result.exceptionOrNull()?.message)
        assertNull(provider.currentConnectionId)
    }

    @Test
    fun `nobody listening is a refused connection`(): Unit = runBlocking {
        val deaf = server()
        deaf.close()

        val result = provider.connect(connectionTo(deaf), TinyFtpServer.Password)

        assertTrue("${result.exceptionOrNull()}", result.exceptionOrNull() is ConnectException)
        assertNull(provider.currentConnectionId)
    }

    @Test
    fun `a failed attempt lets go of the connection that was open`(): Unit = runBlocking {
        val first = server()
        signedInTo(first)

        provider.connect(connectionTo(server(), id = "c2"), "not the password")

        assertNull(provider.currentConnectionId)
        assertEquals(listOf("QUIT"), first.heard)
        assertEquals("Not connected", provider.listFiles("ftp_c1:/", "c1").exceptionOrNull()?.message)
    }

    @Test
    fun `a folder's listing becomes rows without the folder itself or its parent`(): Unit = runBlocking {
        val server = server()
        server.listings["/pub"] = listOf(
            "total 20",
            "drwxr-xr-x    4 sam      staff        4096 Mar 10  2023 .",
            "drwxr-xr-x    9 sam      staff        4096 Mar 10  2023 ..",
            "-rw-r--r--    1 sam      staff        1234 Mar 10  2023 notes.txt",
            "drwxr-xr-x    2 sam      staff        4096 Mar 10  2023 Photos",
            "-rw-r--r--    1 sam      staff     2048576 Mar 10  2023 Beach Day.JPG"
        )
        signedInTo(server)

        val rows = provider.listFiles("ftp_c1:/pub", "c1").getOrThrow()

        val march10 = GregorianCalendar(2023, Calendar.MARCH, 10).timeInMillis
        assertEquals(
            listOf(
                FileItem(
                    id = "/pub/Photos".hashCode(), path = "ftp_c1:/pub/Photos", name = "Photos",
                    type = "folder", kind = "Folder", size = "", changed = "", owner = "FTP",
                    sizeBytes = 4096, lastModified = march10
                ),
                FileItem(
                    id = "/pub/Beach Day.JPG".hashCode(), path = "ftp_c1:/pub/Beach Day.JPG", name = "Beach Day.JPG",
                    type = "image", kind = "Image", size = "2.0 MB", changed = "", owner = "FTP",
                    sizeBytes = 2_048_576, lastModified = march10
                ),
                FileItem(
                    id = "/pub/notes.txt".hashCode(), path = "ftp_c1:/pub/notes.txt", name = "notes.txt",
                    type = "document", kind = "Document", size = "1.2 kB", changed = "", owner = "FTP",
                    sizeBytes = 1234, lastModified = march10
                )
            ),
            // The date is written the way the machine running the test writes dates.
            rows.map { it.copy(changed = "") }
        )
        assertTrue(rows.all { it.changed.isNotBlank() })
        assertEquals(listOf("SYST", "PASV", "LIST /pub"), server.heard)
    }

    @Test
    fun `the top folder is asked for as a slash however its address is written`(): Unit = runBlocking {
        val server = server()
        server.listings["/"] = listOf("-rw-r--r--    1 sam      staff          10 Mar 10  2023 readme.txt")
        signedInTo(server)

        val bare = provider.listFiles("ftp_c1:", "c1").getOrThrow()
        val slashed = provider.listFiles("ftp_c1:/", "c1").getOrThrow()

        assertEquals(listOf("ftp_c1:/readme.txt"), bare.map { it.path })
        assertEquals(bare, slashed)
        assertEquals(2, server.heard.count { it == "LIST /" })
    }

    @Test
    fun `a folder whose address ends in a slash doesn't get a second one in its rows`(): Unit = runBlocking {
        val server = server()
        server.listings["/pub/"] = listOf("-rw-r--r--    1 sam      staff          10 Mar 10  2023 readme.txt")
        signedInTo(server)

        val rows = provider.listFiles("ftp_c1:/pub/", "c1").getOrThrow()

        assertEquals(listOf("ftp_c1:/pub/readme.txt"), rows.map { it.path })
    }

    @Test
    fun `in active mode the server is told where to bring the data`(): Unit = runBlocking {
        val server = server()
        server.listings["/pub"] = listOf("-rw-r--r--    1 sam      staff          10 Mar 10  2023 readme.txt")
        signedInTo(server, passive = false)

        val rows = provider.listFiles("ftp_c1:/pub", "c1").getOrThrow()

        assertEquals(listOf("readme.txt"), rows.map { it.name })
        assertTrue("${server.heard}", server.heard.any { it.startsWith("PORT 127,0,0,1,") })
        assertFalse(server.heard.contains("PASV"))
    }

    @Test
    fun `a listing for another connection is not asked of this server`(): Unit = runBlocking {
        val server = server()
        signedInTo(server)

        val result = provider.listFiles("ftp_c2:/pub", "c2")

        assertEquals("Not connected", result.exceptionOrNull()?.message)
        assertTrue(server.heard.isEmpty())
    }

    @Test
    fun `a download fills the local file, from an address or from a bare path`(): Unit = runBlocking {
        val server = server()
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        server.files["/pub/data.bin"] = bytes
        signedInTo(server)
        val first = scratch.newFile()
        val second = scratch.newFile()

        assertTrue(provider.download("ftp_c1:/pub/data.bin", first).isSuccess)
        assertTrue(provider.download("/pub/data.bin", second).isSuccess)

        assertArrayEquals(bytes, first.readBytes())
        assertArrayEquals(bytes, second.readBytes())
        assertEquals(2, server.heard.count { it == "RETR /pub/data.bin" })
    }

    @Test
    fun `a download the server turns down fails in the server's words`(): Unit = runBlocking {
        val server = server()
        signedInTo(server)

        val result = provider.download("ftp_c1:/pub/missing.txt", scratch.newFile())

        assertEquals("Download failed: 550 No such file", result.exceptionOrNull()?.message?.trim())
    }

    @Test
    fun `an upload sends the file as it is`(): Unit = runBlocking {
        val server = server()
        signedInTo(server)
        // Every byte value, line endings among them, which a text-mode transfer would change.
        val bytes = ByteArray(4096) { it.toByte() }
        val local = scratch.newFile().apply { writeBytes(bytes) }

        assertTrue(provider.upload(local, "ftp_c1:/pub/new.bin").isSuccess)

        assertArrayEquals(bytes, server.files["/pub/new.bin"])
    }

    @Test
    fun `an upload the server turns down fails in the server's words`(): Unit = runBlocking {
        val server = server()
        signedInTo(server)

        val result = provider.upload(scratch.newFile(), "ftp_c1:/readonly/new.bin")

        assertEquals("Upload failed: 553 Not allowed here", result.exceptionOrNull()?.message?.trim())
    }

    @Test
    fun `files and folders are removed by their own commands`(): Unit = runBlocking {
        val server = server()
        server.files["/pub/old.txt"] = ByteArray(1)
        signedInTo(server)

        assertTrue(provider.delete("ftp_c1:/pub/old.txt", isDirectory = false).isSuccess)
        assertTrue(provider.delete("ftp_c1:/pub/2019", isDirectory = true).isSuccess)

        assertEquals(listOf("DELE /pub/old.txt", "RMD /pub/2019"), server.heard)
    }

    @Test
    fun `a removal the server turns down fails in the server's words`(): Unit = runBlocking {
        val server = server()
        signedInTo(server)

        val result = provider.delete("ftp_c1:/pub/never-there.txt", isDirectory = false)

        assertEquals("Delete failed: 550 No such file", result.exceptionOrNull()?.message?.trim())
    }

    @Test
    fun `a new folder is made by name inside its parent`(): Unit = runBlocking {
        val server = server()
        signedInTo(server)

        assertTrue(provider.createFolder("ftp_c1:/pub", "2025").isSuccess)
        assertTrue(provider.createFolder("ftp_c1:/", "top").isSuccess)
        assertTrue(provider.createFolder("", "also top").isSuccess)

        assertEquals(listOf("MKD /pub/2025", "MKD /top", "MKD /also top"), server.heard)
    }

    @Test
    fun `a folder the server won't make fails in the server's words`(): Unit = runBlocking {
        val server = server()
        signedInTo(server)

        val result = provider.createFolder("ftp_c1:/readonly", "2025")

        assertEquals("Create folder failed: 553 Not allowed here", result.exceptionOrNull()?.message?.trim())
    }

    @Test
    fun `disconnecting says goodbye and forgets the connection`(): Unit = runBlocking {
        val server = server()
        signedInTo(server)

        provider.disconnect()

        assertEquals(listOf("QUIT"), server.heard)
        assertNull(provider.currentConnectionId)
        assertEquals("Not connected", provider.listFiles("ftp_c1:/", "c1").exceptionOrNull()?.message)
        assertEquals("Not connected", provider.download("ftp_c1:/a", scratch.newFile()).exceptionOrNull()?.message)
    }
}

/**
 * Just enough of an FTP server for one client at a time: sign-in, listings, transfers in passive and
 * active mode, and the commands that remove and make things. Anything under `/readonly` is refused.
 */
private class TinyFtpServer(private val greeting: String) : Closeable {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val control = ServerSocket(0, 5, loopback)

    val port: Int get() = control.localPort

    /** Every command the client sent, in order. */
    val heard = CopyOnWriteArrayList<String>()

    /** The lines `LIST` answers with, by path. */
    val listings = ConcurrentHashMap<String, List<String>>()

    /** The files there are to fetch, and those that were stored, by path. */
    val files = ConcurrentHashMap<String, ByteArray>()

    init {
        thread(isDaemon = true, name = "tiny-ftp") {
            runCatching {
                while (true) control.accept().use(::converse)
            }
        }
    }

    override fun close() = control.close()

    private fun converse(client: Socket) {
        client.soTimeout = 30_000
        val lines = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val out = client.getOutputStream()
        fun say(line: String) {
            out.write("$line\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }

        // Where the next transfer's data goes: a socket the client will call, or a port of the client's to call.
        var passive: ServerSocket? = null
        var activePort: Int? = null

        fun transfer(work: (Socket) -> Unit) {
            say("150 Here it comes")
            val data = passive?.use { it.accept() } ?: Socket(loopback, activePort!!)
            passive = null
            data.use(work)
            say("226 Done")
        }

        fun refuse(reply: String) {
            passive?.close()
            passive = null
            say(reply)
        }

        say(greeting)
        while (true) {
            val line = lines.readLine() ?: return
            heard += line
            val path = line.substringAfter(' ', "")
            when (line.substringBefore(' ')) {
                "USER" -> say("331 Password, please")
                "PASS" -> say(if (path == Password) "230 Welcome" else "530 Login incorrect")
                "TYPE" -> say("200 Type set")
                "SYST" -> say("215 UNIX Type: L8")
                "NOOP" -> say("200 Still here")
                "PASV" -> {
                    val listening = ServerSocket(0, 1, loopback).also { it.soTimeout = 30_000 }
                    val port = listening.localPort
                    passive = listening
                    say("227 Entering Passive Mode (127,0,0,1,${port shr 8},${port and 0xFF})")
                }
                "PORT" -> {
                    val numbers = path.split(',').map { it.toInt() }
                    activePort = numbers[4] * 256 + numbers[5]
                    say("200 Will call")
                }
                "LIST" -> transfer { data ->
                    val text = listings[path].orEmpty().joinToString("") { "$it\r\n" }
                    data.getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1))
                }
                "RETR" -> {
                    val bytes = files[path]
                    if (bytes == null) refuse("550 No such file") else transfer { it.getOutputStream().write(bytes) }
                }
                "STOR" ->
                    if (path.startsWith("/readonly")) refuse("553 Not allowed here")
                    else transfer { files[path] = it.getInputStream().readBytes() }
                "DELE" -> say(if (files.remove(path) != null) "250 Removed" else "550 No such file")
                "RMD" -> say("250 Removed")
                "MKD" -> say(if (path.startsWith("/readonly")) "553 Not allowed here" else "257 \"$path\" made")
                "QUIT" -> {
                    say("221 Goodbye")
                    return
                }
                else -> say("502 Not understood")
            }
        }
    }

    companion object {
        const val Password = "secret"
    }
}
