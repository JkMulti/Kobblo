package com.kobblo.gallery.file.explorer.ui.root

import com.kobblo.gallery.file.explorer.ui.ApkVersionInfo
import com.kobblo.gallery.file.explorer.ui.InstallMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkReadingTest {
    private val file = testFile("app.apk")

    private fun reading(code: Long, installed: ApkVersion? = null, name: String? = "v$code", label: String? = "App") =
        ApkReading("com.example", label, ApkVersion(name, code), installed)

    private fun line(reading: ApkReading?) = versionLine(reading, unknownName = "Unknown", unknownVersion = "Unknown version")

    @Test
    fun onlyAnOlderVersionIsADowngrade() {
        assertTrue(reading(1, ApkVersion("v2", 2)).isDowngrade)
        assertFalse(reading(2, ApkVersion("v2", 2)).isDowngrade)
        assertFalse(reading(3, ApkVersion("v2", 2)).isDowngrade)
        assertFalse(reading(1).isDowngrade)
    }

    @Test
    fun theLineShowsTheChangeOfVersion() {
        assertEquals("v2 (2)", line(reading(2)))
        assertEquals("v1 (1) → v2 (2)", line(reading(2, ApkVersion("v1", 1))))
        assertEquals("Unknown (1) → Unknown (2)", line(reading(2, ApkVersion(null, 1), name = null)))
        assertEquals("Unknown version", line(null))
    }

    @Test
    fun whatCantBeReadIsAnAppWithNoPackage() {
        assertEquals(ApkVersionInfo("", 0, "Unknown"), candidate(null, "Unknown"))
        assertNull(installedCopy(null, "Unknown"))
        assertNull(installedCopy(reading(2), "Unknown"))
    }

    @Test
    fun theInstalledCopyIsTheSameAppAtItsOwnVersion() {
        val read = reading(2, ApkVersion(null, 1))
        assertEquals(ApkVersionInfo("com.example", 2, "v2"), candidate(read, "Unknown"))
        assertEquals(ApkVersionInfo("com.example", 1, "Unknown"), installedCopy(read, "Unknown"))
    }

    @Test
    fun aBatchEntryTakesWhatWasRead() {
        val entry = batchEntry(file, reading(1, ApkVersion("v2", 2)), "Unknown")
        assertEquals("App", entry.appName)
        assertEquals("v1", entry.versionName)
        assertEquals(1L, entry.versionCode)
        assertTrue(entry.isDowngrade)
        assertEquals("v2", entry.installedVersionName)
        assertEquals(2L, entry.installedVersionCode)
    }

    @Test
    fun aBatchEntryThatCouldNotBeReadGoesByItsFile() {
        val entry = batchEntry(file, null, "Unknown")
        assertEquals("app.apk", entry.appName)
        assertEquals("Unknown", entry.versionName)
        assertEquals(0L, entry.versionCode)
        assertFalse(entry.isDowngrade)
        assertNull(entry.installedVersionName)
        assertNull(entry.installedVersionCode)
        assertEquals("app.apk", batchEntry(file, reading(1, label = null), "Unknown").appName)
    }

    @Test
    fun eachWayOfInstallingTakesItsOwn() {
        val older = batchEntry(file, reading(1, ApkVersion("v2", 2)), "Unknown")
        val newer = batchEntry(file, reading(3, ApkVersion("v2", 2)), "Unknown")
        val fresh = batchEntry(file, reading(1), "Unknown")
        val batch = listOf(older, newer, fresh)
        assertEquals(listOf(newer, fresh), batch.installableBy(InstallMode.NORMAL))
        assertEquals(listOf(newer, fresh), batch.installableBy(InstallMode.SILENT))
        assertEquals(listOf(older), batch.installableBy(InstallMode.DOWNGRADE))
    }

    @Test
    fun aRowShowsTheChangeWhenTheInstalledVersionHasAName() {
        val row = batchEntry(file, reading(2, ApkVersion("v1", 1), label = "my app"), "Unknown").asRow()
        assertEquals("my app", row.name)
        assertEquals("M", row.initial)
        assertEquals("v1 (1) → v2 (2)", row.versionTransition)
        assertNull(row.note)
        assertEquals("v2 (2)", batchEntry(file, reading(2, ApkVersion(null, 1)), "Unknown").asRow().versionTransition)
        assertEquals("v2 (2)", batchEntry(file, reading(2), "Unknown").asRow().versionTransition)
    }
}
