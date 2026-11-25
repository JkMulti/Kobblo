package com.kobblo.gallery.file.explorer.localsend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class IncomingSessionsTest {
    private fun file(id: String, size: Long = 10) =
        AcceptedFile(FileDto(id = id, fileName = "$id.bin", size = size), token = "token-$id", relativeName = "$id.bin")

    private fun session(id: String = "s1", ip: String = "10.0.0.2", fingerprint: String = "AAAA", vararg files: String = arrayOf("a", "b")) =
        IncomingSession(
            id = id,
            sender = DeviceDto(alias = "Asha", fingerprint = fingerprint),
            senderIp = ip,
            root = File("/tmp/none"),
            grantHolder = fingerprint,
            files = files.associateWith { file(it) }
        )

    @Test fun aTokenOpensOnlyItsOwnFile() {
        val a = file("a")

        assertTrue(a.isUnlockedBy("token-a"))
        assertFalse(a.isUnlockedBy("token-b"))
        assertFalse(a.isUnlockedBy("token-"))
        assertFalse(a.isUnlockedBy(""))
    }

    @Test fun aFileCanBeArrivingOnlyOnceAtATime() {
        val session = session()

        assertTrue(session.claim("a"))
        assertFalse(session.claim("a"))
        assertTrue(session.claim("b"))

        session.release("a")
        assertTrue(session.claim("a"))
    }

    @Test fun aFileThatWasSavedIsNeverTakenAgain() {
        val session = session()
        session.claim("a")
        session.markSaved("a", File("/tmp/none/a.bin"))
        session.release("a")

        assertFalse(session.claim("a"))
        assertEquals(listOf(File("/tmp/none/a.bin")), session.savedFiles())
    }

    @Test fun itIsCompleteWhenEveryAcceptedFileIsSaved() {
        val session = session()
        assertFalse(session.isComplete)

        session.markSaved("a", File("a"))
        assertFalse(session.isComplete)
        session.markSaved("b", File("b"))
        assertTrue(session.isComplete)
    }

    @Test fun uploadsAreRunningFromClaimToRelease() {
        val session = session()
        assertFalse(session.hasUploadsRunning)

        session.claim("a")
        assertTrue(session.hasUploadsRunning)
        session.release("a")
        assertFalse(session.hasUploadsRunning)
    }

    @Test fun onlyOneCallerGetsToEndASession() {
        val sessions = IncomingSessions()
        val session = session()
        sessions.add(session)

        assertSame(session, sessions["s1"])
        assertTrue(sessions.end(session))
        assertFalse(sessions.end(session))
        assertNull(sessions["s1"])
        assertTrue(sessions.isEmpty)
    }

    @Test fun aSessionIsFoundByTheDeviceAndAddressItCameFrom() {
        val sessions = IncomingSessions()
        val session = session(ip = "10.0.0.2", fingerprint = "AAAA")
        sessions.add(session)

        assertSame(session, sessions.from("10.0.0.2", "aaaa"))
        assertNull(sessions.from("10.0.0.3", "AAAA"))
        assertNull(sessions.from("10.0.0.2", "BBBB"))
    }

    @Test fun aSessionNothingArrivesOnIsStalled() {
        val sessions = IncomingSessions()
        val session = session()
        sessions.add(session)
        val touched = session.lastActivityMs

        assertTrue(sessions.stalled(touched + 59_999, 60_000).isEmpty())
        assertEquals(listOf(session), sessions.stalled(touched + 60_000, 60_000))
    }

    @Test fun abandoningStopsEverythingAndForgetsIt() {
        val sessions = IncomingSessions()
        val one = session("s1")
        val two = session("s2", ip = "10.0.0.3")
        sessions.add(one)
        sessions.add(two)
        assertEquals(2, sessions.count)

        sessions.abandonAll()

        assertTrue(one.isStopped && two.isStopped)
        assertFalse(one.isReplaced)
        assertTrue(sessions.isEmpty)
    }

    @Test fun beingReplacedCutsItsConnectionsAndWaitsForThemToLetGo() {
        val session = session()
        val closed = CountDownLatch(1)
        val connection = object : InputStream() {
            override fun read(): Int = -1
            override fun close() = closed.countDown()
        }
        session.claim("a")
        session.arrivingOn("a", connection)
        // The upload lets go a moment after its connection is cut, as a real one would.
        Thread {
            closed.await()
            session.release("a")
        }.start()

        session.stopForReplacement(waitMs = 5_000)

        assertTrue(closed.await(1, TimeUnit.SECONDS))
        assertTrue(session.isStopped)
        assertTrue(session.isReplaced)
        assertFalse(session.hasUploadsRunning)
    }

    @Test fun beingReplacedDoesNotWaitForEverForAnUploadThatWillNotLetGo() {
        val session = session()
        session.claim("a")
        session.arrivingOn("a", ByteArrayInputStream(ByteArray(0)))

        val started = System.nanoTime()
        session.stopForReplacement(waitMs = 200)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(session.hasUploadsRunning)
        assertTrue("waited $tookMs ms", tookMs in 150..2_000)
    }
}

class ScanBatchTest {
    private var now = 10_000L
    private val sent = ArrayList<List<String>>()
    private val batch = ScanBatch(batchSize = 3, everyMs = 2_000, clock = { now }) { sent += it.toList() }

    @Test fun theFirstFileGoesAtOnceSoThereIsSomethingToSee() {
        batch.add("a")

        assertEquals(listOf(listOf("a")), sent)
    }

    @Test fun filesThatFollowCloselyWaitForEachOther() {
        batch.add("a")
        now += 100
        batch.add("b")
        now += 100
        batch.add("c")

        assertEquals(listOf(listOf("a")), sent)
    }

    @Test fun aFullBatchGoesWithoutWaiting() {
        batch.add("a")
        now += 10
        batch.add("b")
        batch.add("c")
        batch.add("d")

        assertEquals(listOf(listOf("a"), listOf("b", "c", "d")), sent)
    }

    @Test fun afterAWhileTheNextFileTakesTheWaitingOnesWithIt() {
        batch.add("a")
        now += 10
        batch.add("b")
        now += 2_000
        batch.add("c")

        assertEquals(listOf(listOf("a"), listOf("b", "c")), sent)
    }

    @Test fun flushingSendsWhatIsWaitingAndNothingWhenNothingIs() {
        batch.flush()
        assertTrue(sent.isEmpty())

        batch.add("a")
        now += 10
        batch.add("b")
        batch.flush()
        batch.flush()

        assertEquals(listOf(listOf("a"), listOf("b")), sent)
    }
}
