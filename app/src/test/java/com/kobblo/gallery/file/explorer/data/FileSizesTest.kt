package com.kobblo.gallery.file.explorer.data

import com.kobblo.gallery.file.explorer.data.core.FileSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Random

class FileSizesTest {

    @Test
    fun `sizes read as the system's short format does`() {
        mapOf(
            0L to "0 B",
            1L to "1 B",
            145L to "145 B",
            900L to "900 B",
            901L to "0.90 kB",
            1_000L to "1.0 kB",
            1_234L to "1.2 kB",
            30_420L to "30 kB",
            31_499L to "31 kB",
            123_400L to "123 kB",
            999_999L to "1.00 MB",
            5_678_000L to "5.7 MB",
            52_428_800L to "52 MB",
            1_500_000_000L to "1.5 GB",
            2_000_000_000_000L to "2.0 TB"
        ).forEach { (bytes, text) -> assertEquals("for $bytes", text, FileSizes.plainLabel(bytes)) }
    }

    @Test
    fun `no size comes back with empty decimals`() {
        val random = Random(7)
        repeat(50_000) {
            val bytes = random.nextLong().ushr(1 + random.nextInt(62))
            assertFalse("for $bytes", FileSizes.plainLabel(bytes).endsWith(".00 B"))
        }
    }

    @Test
    fun `a size below zero reads as nothing rather than as a negative`() {
        assertEquals("0 B", FileSizes.label(-5L))
    }

    @Test
    fun `sizes share a label only when it can't tell them apart`() {
        assertEquals(0L, FileSizes.labelKey(0))
        assertEquals(9_999L, FileSizes.labelKey(9_999))
        assertEquals(12_340L, FileSizes.labelKey(12_345))
        assertEquals(1_959_000L, FileSizes.labelKey(1_959_999))

        // The label rounds on the fourth digit, so a remembered label is right for its whole key.
        listOf(123_400L, 123_499L).forEach {
            assertEquals(123_400L, FileSizes.labelKey(it))
            assertEquals("123 kB", FileSizes.plainLabel(it))
        }
        listOf(123_500L, 123_599L).forEach {
            assertEquals(123_500L, FileSizes.labelKey(it))
            assertEquals("124 kB", FileSizes.plainLabel(it))
        }
    }

    @Test
    fun `the same file reads the same however often it's asked`() {
        val first = FileSizes.label(31_499L)
        assertEquals(first, FileSizes.label(31_499L))
        assertEquals("31 kB", first)
    }
}
