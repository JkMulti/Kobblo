package com.kobblo.gallery.file.explorer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageBarShareTest {
    private val gigabyte = 1_000_000_000L

    @Test
    fun aKindTakesItsShareOfTheSpaceInUse() {
        assertEquals(0.25f, storageBarShare(25 * gigabyte, 100 * gigabyte), 0.0001f)
        assertEquals(0.5f, storageBarShare(50 * gigabyte, 100 * gigabyte), 0.0001f)
    }

    @Test
    fun aKindWithNothingInItTakesNoRoom() {
        assertEquals(0f, storageBarShare(0L, 100 * gigabyte), 0f)
        assertEquals(0f, storageBarShare(0L, 0L), 0f)
    }

    @Test
    fun aKindWithAlmostNothingStillGetsASliver() {
        assertEquals(0.02f, storageBarShare(1L, 100 * gigabyte), 0f)
        assertEquals(0.02f, storageBarShare(gigabyte, 100 * gigabyte), 0f)
        assertEquals(0.03f, storageBarShare(3 * gigabyte, 100 * gigabyte), 0.0001f)
    }

    @Test
    fun aKindCountedAsMoreThanIsInUseFillsTheBarAndNoMore() {
        assertEquals(1f, storageBarShare(120 * gigabyte, 100 * gigabyte), 0f)
    }

    @Test
    fun withNoSpaceCountedAsInUseAnyFilesFillTheBar() {
        assertEquals(1f, storageBarShare(5L, 0L), 0f)
    }
}
