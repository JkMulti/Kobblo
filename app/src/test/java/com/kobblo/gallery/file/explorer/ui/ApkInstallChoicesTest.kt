package com.kobblo.gallery.file.explorer.ui

import com.kobblo.gallery.file.explorer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallChoicesTest {
    private fun version(code: Long) = ApkVersionInfo("org.example.notes", code, "$code.0")

    private fun modes(file: Long, installed: Long?, shizuku: Boolean) =
        evaluateInstallModes(selectedApk = version(file), installedApp = installed?.let(::version), shizukuConnected = shizuku)

    @Test
    fun `an ordinary install is always there to use`() {
        for (shizuku in listOf(true, false)) {
            for (installed in listOf(null, 1L, 5L, 9L)) {
                val normal = modes(file = 5, installed = installed, shizuku = shizuku).normal
                assertTrue(normal.offered)
                assertTrue(normal.usable)
                assertNull(normal.block)
            }
        }
    }

    @Test
    fun `a silent install is always offered, and needs Shizuku`() {
        val with = modes(file = 5, installed = null, shizuku = true).silent
        assertTrue(with.offered)
        assertTrue(with.usable)

        val without = modes(file = 5, installed = null, shizuku = false).silent
        assertTrue(without.offered)
        assertFalse(without.usable)
        assertEquals(InstallBlock.NoShizuku, without.block)
    }

    @Test
    fun `a downgrade isn't offered for an app that isn't installed`() {
        for (shizuku in listOf(true, false)) {
            val downgrade = modes(file = 5, installed = null, shizuku = shizuku).downgrade
            assertFalse(downgrade.offered)
            assertFalse(downgrade.usable)
            assertEquals(InstallBlock.NotInstalled, downgrade.block)
        }
    }

    @Test
    fun `a downgrade is offered but held back when the file isn't older`() {
        for (installed in listOf(5L, 4L)) {
            for (shizuku in listOf(true, false)) {
                val downgrade = modes(file = 5, installed = installed, shizuku = shizuku).downgrade
                assertTrue(downgrade.offered)
                assertFalse(downgrade.usable)
                assertEquals(InstallBlock.NotOlder, downgrade.block)
            }
        }
    }

    @Test
    fun `a downgrade to an older file needs Shizuku`() {
        val with = modes(file = 5, installed = 6, shizuku = true).downgrade
        assertTrue(with.usable)
        assertNull(with.block)

        val without = modes(file = 5, installed = 6, shizuku = false).downgrade
        assertTrue(without.offered)
        assertFalse(without.usable)
        assertEquals(InstallBlock.NoShizuku, without.block)
    }

    @Test
    fun `without Shizuku the notice is about Shizuku, whatever else is true`() {
        for (installed in listOf(null, 4L, 5L, 6L)) {
            assertEquals(InstallBlock.NoShizuku, modes(file = 5, installed = installed, shizuku = false).notice)
        }
    }

    @Test
    fun `with Shizuku the notice explains a downgrade that's shown and can't be used`() {
        assertEquals(InstallBlock.NotOlder, modes(file = 5, installed = 5, shizuku = true).notice)
        assertEquals(InstallBlock.NotOlder, modes(file = 5, installed = 3, shizuku = true).notice)
    }

    @Test
    fun `with nothing held back there's no notice`() {
        assertNull(modes(file = 5, installed = 6, shizuku = true).notice)
    }

    @Test
    fun `a downgrade that isn't shown needs no explaining`() {
        assertNull(modes(file = 5, installed = null, shizuku = true).notice)
    }

    @Test
    fun `the same answer twice compares equal`() {
        assertEquals(modes(file = 5, installed = 6, shizuku = false), modes(file = 5, installed = 6, shizuku = false))
    }

    @Test
    fun `each block has its own line of text`() {
        assertEquals(R.string.view_apk_not_installed, InstallBlock.NotInstalled.message)
        assertEquals(R.string.view_apk_not_older, InstallBlock.NotOlder.message)
        assertEquals(R.string.view_apk_shizuku_not_connected, InstallBlock.NoShizuku.message)
    }

    @Test
    fun `each way of installing is named and explained`() {
        assertEquals(R.string.view_apk_normal_install, InstallMode.NORMAL.title)
        assertEquals(R.string.view_apk_normal_install_subtitle, InstallMode.NORMAL.explanation)
        assertEquals(R.string.view_apk_downgrade, InstallMode.DOWNGRADE.title)
        assertEquals(R.string.view_apk_downgrade_subtitle, InstallMode.DOWNGRADE.explanation)
        assertEquals(R.string.view_apk_silent_install, InstallMode.SILENT.title)
        assertEquals(R.string.view_apk_silent_install_subtitle, InstallMode.SILENT.explanation)
    }

    @Test
    fun `the confirmation sheet spells a downgrade out, and heads the others by their names`() {
        assertEquals(R.string.view_apk_normal_install, InstallMode.NORMAL.sheetTitle)
        assertEquals(R.string.view_apk_downgrade_install, InstallMode.DOWNGRADE.sheetTitle)
        assertEquals(R.string.view_apk_silent_install, InstallMode.SILENT.sheetTitle)
    }

    @Test
    fun `a batch row shows the version change, and its note when it has one`() {
        assertEquals("1.0 (10) → 1.1 (11)", BatchApkItem("Notes", "N", "1.0 (10) → 1.1 (11)").summary)
        assertEquals("1.1 (11) · Not installed", BatchApkItem("Notes", "N", "1.1 (11)", note = "Not installed").summary)
    }
}
