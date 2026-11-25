package com.kobblo.gallery.file.explorer.fileops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the real commands through a real `sh`, inside a throwaway folder. The attack in each name only ever
 * creates a marker file in that folder, so a failure shows up as the marker existing and does no other harm.
 */
class ShellCommandTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var work: File
    private lateinit var marker: File

    private val hostileNames: List<String>
        get() = listOf(
            "plain.txt",
            "with space.txt",
            "\$(touch ATTACK_RAN)",
            "\$(touch ATTACK_RAN).jpg",
            "`touch ATTACK_RAN`",
            "\"; touch ATTACK_RAN; echo \"",
            "'; touch ATTACK_RAN; echo '",
            "a\"b.txt",
            "a'b.txt",
            "back\\slash.txt",
            "ends with backslash\\",
            "new\nline.txt",
            "tab\there.txt",
            "-rf",
            "--help",
            "-",
            "star*.txt",
            "\$HOME",
            "\${PATH}",
            "a;b&c|d>e<f.txt",
            "#hash",
            "~tilde",
            "!bang",
            "ünïcødé 日本語.txt"
        )

    @Before
    fun setUp() {
        work = temp.newFolder("work")
        marker = File(work, "ATTACK_RAN")
    }

    /** In the work folder, so that even a broken command's stray relative paths land somewhere harmless. */
    private fun sh(command: String): Int {
        val process = ProcessBuilder("/bin/sh", "-c", command).directory(work).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText()
        assertTrue("Timed out: $command", process.waitFor(20, TimeUnit.SECONDS))
        return process.exitValue()
    }

    private fun shOutput(command: String): String {
        val process = ProcessBuilder("/bin/sh", "-c", command).directory(work).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(20, TimeUnit.SECONDS)
        return out
    }

    private fun assertNoAttackRan(name: String) {
        assertFalse("A command ran from the name: $name", marker.exists())
    }

    @Test
    fun `quoting makes a word that means itself`() {
        assertEquals("'plain'", ShellCommand.quote("plain"))
        assertEquals("'it'\\''s'", ShellCommand.quote("it's"))
        assertEquals("'\$(x)'", ShellCommand.quote("\$(x)"))
        assertEquals("'`x`'", ShellCommand.quote("`x`"))
        assertEquals("''", ShellCommand.quote(""))
    }

    @Test
    fun `a name with a NUL is refused rather than cut short`() {
        assertThrows(IllegalArgumentException::class.java) { ShellCommand.quote("a\u0000b") }
    }

    @Test
    fun `deleting the root or nothing is refused`() {
        assertThrows(IllegalArgumentException::class.java) { ShellCommand.deleteTree("") }
        assertThrows(IllegalArgumentException::class.java) { ShellCommand.deleteTree("   ") }
        assertThrows(IllegalArgumentException::class.java) { ShellCommand.deleteTree("/") }
        assertThrows(IllegalArgumentException::class.java) { ShellCommand.deleteTree("///") }
    }

    @Test
    fun `every hostile name makes a folder and nothing else`() {
        for (name in hostileNames) {
            val folder = File(work, "mk").resolve(name)
            assertEquals(name, 0, sh(ShellCommand.makeFolders(folder.path)))
            assertTrue(name, folder.isDirectory)
            assertNoAttackRan(name)
            File(work, "mk").deleteRecursively()
        }
    }

    @Test
    fun `every hostile name copies, moves, lists and deletes as itself`() {
        for (name in hostileNames) {
            val source = File(work, name).apply { writeText("contents of $name") }
            val copied = File(temp.newFolder(), name)

            assertEquals("cp $name", 0, sh(ShellCommand.copyTree(source.path, copied.path)))
            assertEquals(source.readText(), copied.readText())
            assertNoAttackRan(name)

            assertTrue(shOutput(ShellCommand.listFolder(work.path)).contains(name))
            assertNoAttackRan(name)

            assertEquals("exists $name", 0, sh(ShellCommand.exists(source.path)))
            assertNoAttackRan(name)

            val moved = File(temp.newFolder(), name)
            assertEquals("mv $name", 0, sh(ShellCommand.moveTo(source.path, moved.path)))
            assertFalse(source.exists())
            assertTrue(moved.exists())
            assertNoAttackRan(name)

            assertEquals("rm $name", 0, sh(ShellCommand.deleteTree(moved.path)))
            assertFalse(moved.exists())
            assertNotEquals0(sh(ShellCommand.exists(moved.path)), name)
            assertNoAttackRan(name)
        }
    }

    private fun assertNotEquals0(code: Int, name: String) = assertTrue("Should be gone: $name", code != 0)

    @Test
    fun `a move never replaces what's already there`() {
        val source = File(work, "a.txt").apply { writeText("new") }
        val target = File(work, "b.txt").apply { writeText("precious") }
        assertTrue(sh(ShellCommand.moveTo(source.path, target.path)) != 0)
        assertEquals("precious", target.readText())
        assertEquals("new", source.readText())
    }

    @Test
    fun `deleting a folder takes only what's in it`() {
        val keep = File(work, "keep").apply { mkdirs(); resolve("safe.txt").writeText("x") }
        val hostile = File(work, "\$(rm -rf keep)").apply { mkdirs(); resolve("inner.txt").writeText("y") }
        assertEquals(0, sh(ShellCommand.deleteTree(hostile.path)))
        assertFalse(hostile.exists())
        assertTrue(keep.resolve("safe.txt").exists())
    }

    @Test
    fun `reading an apk from a hostile path runs nothing`() {
        val apk = File(work, "\$(touch ATTACK_RAN).apk").apply { writeText("bytes") }
        val command = ShellCommand.installApk(apk.path, 5L, allowDowngrade = true)
        assertEquals("bytes", shOutput(command.substringBefore(" | ")))
        assertNoAttackRan(apk.name)
        assertTrue(command.endsWith("cmd package install -r -d -S 5"))
        assertTrue(ShellCommand.installApk(apk.path, 5L, allowDowngrade = false).endsWith("cmd package install -r -S 5"))
    }

    @Test
    fun `only a real package name reaches the uninstaller`() {
        assertEquals("cmd package uninstall 'com.example.app'", ShellCommand.uninstallApk("com.example.app"))
        assertEquals("cmd package uninstall 'a.b_c.D9'", ShellCommand.uninstallApk("a.b_c.D9"))
        assertNull(ShellCommand.uninstallApk("com.example; touch /tmp/x"))
        assertNull(ShellCommand.uninstallApk("com.example.\$(x)"))
        assertNull(ShellCommand.uninstallApk("--user"))
        assertNull(ShellCommand.uninstallApk("nodots"))
        assertNull(ShellCommand.uninstallApk(""))
    }
}
