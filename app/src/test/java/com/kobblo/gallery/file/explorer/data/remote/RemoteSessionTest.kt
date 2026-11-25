package com.kobblo.gallery.file.explorer.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class RemoteSessionTest {

    /** A stand-in for a client library's connection object. */
    private class Client(val name: String)

    private val session = RemoteSession<Client>()

    @Test
    fun `nothing is open to begin with`() {
        assertNull(session.connectionId)
        assertNull(session.client)
        assertNull(session.close())
    }

    @Test
    fun `an opened session is the one calls are made on`(): Unit = runBlocking {
        val nas = Client("nas")
        session.open("c1", nas)

        assertEquals("c1", session.connectionId)
        assertSame(nas, session.call { it }.getOrThrow())
        assertSame(nas, session.callFor("c1") { it }.getOrThrow())
    }

    @Test
    fun `a call with nothing open fails as not connected`(): Unit = runBlocking {
        val failure = session.call { it.name }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("Not connected", failure?.message)
    }

    @Test
    fun `a call for one connection is not answered by another's session`(): Unit = runBlocking {
        session.open("c1", Client("nas"))
        var reached = false

        val failure = session.callFor("c2") { reached = true }.exceptionOrNull()

        assertEquals("Not connected", failure?.message)
        assertEquals(Exception::class.java, failure?.javaClass)
        assertEquals(false, reached)
        // A call that names no connection takes whichever session there is.
        assertEquals("nas", session.call { it.name }.getOrThrow())
    }

    @Test
    fun `a call for a connection with nothing open fails as not connected`(): Unit = runBlocking {
        val failure = session.callFor("c1") { it.name }.exceptionOrNull()

        assertEquals("Not connected", failure?.message)
    }

    @Test
    fun `closing hands the client back and forgets the connection`(): Unit = runBlocking {
        val nas = Client("nas")
        session.open("c1", nas)

        assertSame(nas, session.close())
        assertNull(session.connectionId)
        assertNull(session.close())
        assertTrue(session.call { it.name }.isFailure)
    }

    @Test
    fun `opening again takes the place of the session before`(): Unit = runBlocking {
        session.open("c1", Client("nas"))
        session.open("c2", Client("attic"))

        assertEquals("c2", session.connectionId)
        assertEquals("attic", session.callFor("c2") { it.name }.getOrThrow())
        assertTrue(session.callFor("c1") { it.name }.isFailure)
    }

    @Test
    fun `what a call throws is what it fails with`(): Unit = runBlocking {
        val gone = IOException("connection reset")
        session.open("c1", Client("nas"))

        assertSame(gone, session.call { throw gone }.exceptionOrNull())
        assertSame(gone, session.callFor("c1") { throw gone }.exceptionOrNull())
        assertSame(gone, remoteCall { throw gone }.exceptionOrNull())
    }

    @Test
    fun `an error that is no exception is not turned into a failed call`() {
        class Broken : Error("the library is broken")

        try {
            runBlocking { remoteCall<Unit> { throw Broken() } }
            fail("The error was swallowed")
        } catch (expected: Broken) {
            // Left for whoever can deal with it.
        }
    }

    @Test
    fun `calls are made away from the caller's thread`(): Unit = runBlocking {
        val caller = Thread.currentThread()

        assertNotSame(caller, remoteCall { Thread.currentThread() }.getOrThrow())
    }
}
