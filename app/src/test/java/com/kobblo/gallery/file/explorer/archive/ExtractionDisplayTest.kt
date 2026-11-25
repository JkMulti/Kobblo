package com.kobblo.gallery.file.explorer.archive

import com.kobblo.gallery.file.explorer.fileops.ExtractOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionDisplayTest {
    private val running = ArchiveProgress.Running(filesDone = 40, filesTotal = 100, currentEntryName = "a.jpg")

    @Test
    fun `a job's fraction becomes a whole percent, rounded down`() {
        assertEquals(0, ExtractionDisplay.percentOf(0f))
        assertEquals(12, ExtractionDisplay.percentOf(0.129f))
        assertEquals(99, ExtractionDisplay.percentOf(0.999f))
        assertEquals(100, ExtractionDisplay.percentOf(1f))
    }

    @Test
    fun `a job that hasn't said how far along it is shows as zero`() {
        assertEquals(0, ExtractionDisplay.percentOf(null))
    }

    @Test
    fun `the first progress is always shown`() {
        assertTrue(ExtractionDisplay.isWorthShowing(null, percent = 0, sinceShownMillis = 0L))
    }

    @Test
    fun `progress replaces an ending left on screen`() {
        assertTrue(ExtractionDisplay.isWorthShowing(ArchiveProgress.Complete("/out"), percent = 0, sinceShownMillis = 0L))
        assertTrue(ExtractionDisplay.isWorthShowing(ArchiveProgress.NeedsPassword(true), percent = 0, sinceShownMillis = 0L))
        assertTrue(ExtractionDisplay.isWorthShowing(ArchiveProgress.Failed("no"), percent = 0, sinceShownMillis = 0L))
    }

    @Test
    fun `a percent that moved is shown at once`() {
        assertTrue(ExtractionDisplay.isWorthShowing(running, percent = 41, sinceShownMillis = 1L))
        assertTrue(ExtractionDisplay.isWorthShowing(running, percent = 0, sinceShownMillis = 1L))
    }

    @Test
    fun `a percent that hasn't moved waits its turn`() {
        assertFalse(ExtractionDisplay.isWorthShowing(running, percent = 40, sinceShownMillis = 0L))
        assertFalse(ExtractionDisplay.isWorthShowing(running, percent = 40, sinceShownMillis = ExtractionDisplay.REFRESH_MILLIS - 1))
        assertTrue(ExtractionDisplay.isWorthShowing(running, percent = 40, sinceShownMillis = ExtractionDisplay.REFRESH_MILLIS))
    }

    @Test
    fun `each way a job can end has its own thing to show`() {
        assertEquals(ArchiveProgress.Complete("/sdcard/out"), ExtractionDisplay.endingFor(ExtractOutcome.Complete("/sdcard/out")))
        assertEquals(ArchiveProgress.NeedsPassword(false), ExtractionDisplay.endingFor(ExtractOutcome.NeedsPassword(false)))
        assertEquals(ArchiveProgress.NeedsPassword(true), ExtractionDisplay.endingFor(ExtractOutcome.NeedsPassword(true)))
        assertEquals(ArchiveProgress.Failed("Not enough space"), ExtractionDisplay.endingFor(ExtractOutcome.Failed("Not enough space")))
    }

    @Test
    fun `a job the person stopped leaves nothing on screen`() {
        assertNull(ExtractionDisplay.endingFor(null))
    }
}
