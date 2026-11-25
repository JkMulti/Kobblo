package com.kobblo.gallery.file.explorer.transfer

import com.kobblo.gallery.file.explorer.transfer.core.RetryWindow
import com.kobblo.gallery.file.explorer.transfer.core.WriteWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendPatienceTest {
    private val mb = 1024L * 1024

    @Test fun aDropAfterALongSendStillGetsTheWholeWindow() {
        val window = RetryWindow(45_000)
        // Ten minutes into the send is the first time anything went wrong.
        assertTrue(window.mayRetry(now = 600_000, reached = 5_000 * mb))
        assertTrue(window.mayRetry(now = 644_000, reached = 5_000 * mb))
        assertFalse(window.mayRetry(now = 645_000, reached = 5_000 * mb))
    }

    @Test fun gettingWellPastItStartsTheClockOver() {
        val window = RetryWindow(45_000)
        assertTrue(window.mayRetry(now = 0, reached = 100 * mb))
        assertTrue(window.mayRetry(now = 40_000, reached = 200 * mb))
        assertTrue(window.mayRetry(now = 80_000, reached = 300 * mb))
        assertFalse(window.mayRetry(now = 125_000, reached = 300 * mb))
    }

    @Test fun fallingOverAtTheSamePlaceRunsOutOfPatience() {
        val window = RetryWindow(45_000)
        assertTrue(window.mayRetry(now = 0, reached = 6 * mb))
        // Starting a file over counts for nothing until it's past where it got to, and what's sitting in the
        // network's buffers makes "the same place" come out a little different each time.
        assertTrue(window.mayRetry(now = 33_000, reached = 4 * mb))
        assertTrue(window.mayRetry(now = 40_000, reached = 7 * mb))
        assertFalse(window.mayRetry(now = 66_000, reached = 6 * mb))
    }

    @Test fun nothingSentAtAllStillGetsTheWindow() {
        val window = RetryWindow(45_000)
        assertTrue(window.mayRetry(now = 1_000, reached = 0))
        assertFalse(window.mayRetry(now = 46_000, reached = 0))
    }

    @Test fun aWriteIsOnlyBlockedWhileItsGoing() {
        val watch = WriteWatch()
        assertEquals(0, watch.blockedFor(5_000))
        watch.begin(5_000)
        assertEquals(0, watch.blockedFor(5_000))
        assertEquals(30_000, watch.blockedFor(35_000))
        watch.end()
        assertEquals(0, watch.blockedFor(40_000))
    }
}
