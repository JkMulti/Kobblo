package com.kobblo.gallery.file.explorer.ui.expressive

import com.kobblo.gallery.file.explorer.data.core.FileSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallRulesTest {

    // ── Whether the phone wants motion ──

    @Test
    fun `motion is on when every scale is and animators run`() {
        assertFalse(motionIsOff(listOf(1f, 1f), animatorsEnabled = true))
        assertFalse(motionIsOff(listOf(0.5f, 2f), animatorsEnabled = true))
    }

    @Test
    fun `either scale at zero turns motion off`() {
        assertTrue(motionIsOff(listOf(0f, 1f), animatorsEnabled = true))
        assertTrue(motionIsOff(listOf(1f, 0f), animatorsEnabled = true))
    }

    @Test
    fun `with animators off motion is off, whatever the scales say or whether they could be read`() {
        assertTrue(motionIsOff(listOf(1f, 1f), animatorsEnabled = false))
        assertTrue(motionIsOff(emptyList(), animatorsEnabled = false))
        assertFalse(motionIsOff(emptyList(), animatorsEnabled = true))
    }

    // ── The toolbar's small title ──

    @Test
    fun `the small title stays out of sight until the large one is mostly folded`() {
        assertEquals(0f, smallTitleAlpha(0f), 0f)
        assertEquals(0f, smallTitleAlpha(0.6f), 0f)
        assertEquals(0.5f, smallTitleAlpha(0.8f), 0.0001f)
        assertEquals(1f, smallTitleAlpha(1f), 0.0001f)
    }

    @Test
    fun `its opacity never leaves the range a layer can draw`() {
        assertEquals(0f, smallTitleAlpha(-3f), 0f)
        assertEquals(1f, smallTitleAlpha(7f), 0f)
    }

    // ── A toast's tone ──

    @Test
    fun `a message that mentions a problem is one`() {
        assertEquals(ToastTone.Problem, toastTone("Couldn't copy 3 files", "", ""))
        assertEquals(ToastTone.Problem, toastTone("Permission DENIED", "", ""))
    }

    @Test
    fun `a message that says something was done is marked so`() {
        assertEquals(ToastTone.Done, toastTone("Copied to Downloads", "", ""))
        assertEquals(ToastTone.Done, toastTone("3 files moved", "", ""))
    }

    @Test
    fun `a problem outweighs whatever else the message says`() {
        assertEquals(ToastTone.Problem, toastTone("Copied 2, failed 1", "", ""))
    }

    @Test
    fun `anything else has no tone`() {
        assertNull(toastTone("Scanning", "", ""))
        assertNull(toastTone("", "", ""))
    }

    @Test
    fun `the language's own words count as much as the English ones`() {
        assertEquals(ToastTone.Problem, toastTone("Fehler beim Kopieren", "fehler, nicht möglich", "kopiert"))
        assertEquals(ToastTone.Done, toastTone("Nach Downloads KOPIERT", "fehler", " kopiert , verschoben"))
        assertEquals(ToastTone.Done, toastTone("Saved", "fehler", "kopiert"))
        // A list with gaps in it doesn't make every message match.
        assertNull(toastTone("Scanning", ", ,", ","))
    }

    // ── The save sheet ──

    @Test
    fun `a total is a size, or a dash when the senders gave none`() {
        assertEquals(FileSizes.label(5_678_000), totalSizeText(5_678_000))
        assertEquals("--", totalSizeText(0))
        assertEquals("--", totalSizeText(-1))
    }

    @Test
    fun `a destination is called by its last folder, and the phone's root by the storage's name`() {
        val root = "/storage/emulated/0"
        assertEquals("Internal storage", destinationTitle(root, root, "Internal storage"))
        assertEquals("Download", destinationTitle("$root/Download", root, "Internal storage"))
        assertEquals("Internal storage", destinationTitle("/", root, "Internal storage"))
        assertEquals("Internal storage", destinationTitle("", root, "Internal storage"))
    }
}
