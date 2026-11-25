package com.kobblo.gallery.file.explorer.ui

import com.kobblo.gallery.file.explorer.archive.ArchiveProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class FootBarTest {
    private fun bar(
        selecting: Boolean = false,
        organisePending: Boolean = false,
        clipboardArmed: Boolean = false,
        extracting: Boolean = false,
        pasting: Boolean = false
    ) = footBar(selecting, organisePending, clipboardArmed, extracting, pasting)

    @Test
    fun withNothingGoingOnTheTabsShow() {
        assertEquals(FootBar.Tabs, bar())
    }

    @Test
    fun eachThingAloneGetsItsBar() {
        assertEquals(FootBar.Selection, bar(selecting = true))
        assertEquals(FootBar.Organise, bar(organisePending = true))
        assertEquals(FootBar.Clipboard, bar(clipboardArmed = true))
        assertEquals(FootBar.Extracting, bar(extracting = true))
        assertEquals(FootBar.Pasting, bar(pasting = true))
    }

    @Test
    fun aSelectionComesBeforeEverything() {
        assertEquals(
            FootBar.Selection,
            bar(selecting = true, organisePending = true, clipboardArmed = true, extracting = true, pasting = true)
        )
    }

    @Test
    fun filesWaitingForAYesComeBeforeWorkUnderWay() {
        assertEquals(FootBar.Organise, bar(organisePending = true, clipboardArmed = true, extracting = true, pasting = true))
        assertEquals(FootBar.Clipboard, bar(clipboardArmed = true, extracting = true, pasting = true))
    }

    @Test
    fun anExtractionComesBeforeAPaste() {
        assertEquals(FootBar.Extracting, bar(extracting = true, pasting = true))
    }

    @Test
    fun anExtractionIsAsFarAlongAsItsFiles() {
        assertEquals(0.25f, ArchiveProgress.Running(filesDone = 1, filesTotal = 4, currentEntryName = "a/b.txt").fraction(), 0f)
        assertEquals(1f, ArchiveProgress.Running(filesDone = 4, filesTotal = 4, currentEntryName = "").fraction(), 0f)
    }

    @Test
    fun anExtractionThatHasNotCountedItsFilesIsAtTheStart() {
        assertEquals(0f, ArchiveProgress.Running(filesDone = 0, filesTotal = 0, currentEntryName = "").fraction(), 0f)
        assertEquals(0f, ArchiveProgress.Running(filesDone = 3, filesTotal = 0, currentEntryName = "").fraction(), 0f)
    }

    @Test
    fun aFinishedExtractionIsFull() {
        assertEquals(1f, ArchiveProgress.Complete(outputDir = "/out").fraction(), 0f)
    }

    @Test
    fun anExtractionThatStoppedForAnotherReasonShowsNoProgress() {
        assertEquals(0f, ArchiveProgress.Failed("broken").fraction(), 0f)
        assertEquals(0f, ArchiveProgress.NeedsPassword(attemptFailed = false).fraction(), 0f)
    }
}
