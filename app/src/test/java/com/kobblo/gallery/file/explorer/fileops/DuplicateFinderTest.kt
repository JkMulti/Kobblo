package com.kobblo.gallery.file.explorer.fileops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

class DuplicateFinderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun bytes(size: Int, seed: Long = 7L): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun file(name: String, content: ByteArray): String = temp.newFile(name).apply { writeBytes(content) }.path

    private fun groups(vararg paths: String): List<Set<String>> {
        val size = File(paths.first()).length()
        return DuplicateFinder.provenGroups(paths.toList(), size).map { it.toSet() }
    }

    @Test
    fun `two files differing only near the end aren't duplicates`() {
        val content = bytes(4 * 1024 * 1024)
        val other = content.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        assertEquals(emptyList<Set<String>>(), groups(file("a.mp4", content), file("b.mp4", other)))
    }

    @Test
    fun `two files differing only in the middle aren't duplicates, though both ends match`() {
        // This is the one a quick look can't see: same size, same first 16 KB, same last 16 KB.
        val content = bytes(4 * 1024 * 1024)
        val other = content.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertEquals(emptyList<Set<String>>(), groups(file("a.img", content), file("b.img", other)))
    }

    @Test
    fun `a single flipped byte at every kind of boundary is found`() {
        val size = 3 * DuplicateFinder.QuickBytes
        val content = bytes(size)
        val a = file("base.bin", content)
        for (position in listOf(0, DuplicateFinder.QuickBytes - 1, DuplicateFinder.QuickBytes, size / 2, size - DuplicateFinder.QuickBytes, size - 1)) {
            val other = content.copyOf().also { it[position] = (it[position] + 1).toByte() }
            val b = file("flip$position.bin", other)
            assertEquals("position $position", emptyList<Set<String>>(), groups(a, b))
        }
    }

    @Test
    fun `identical files are proven the same`() {
        val content = bytes(1024 * 1024)
        val a = file("a.jpg", content)
        val b = file("b.jpg", content)
        assertEquals(listOf(setOf(a, b)), groups(a, b))
    }

    @Test
    fun `files smaller than the quick look are still compared whole`() {
        val a = file("small1.txt", ByteArray(2000) { 1 })
        val b = file("small2.txt", ByteArray(2000) { 1 })
        val c = file("small3.txt", ByteArray(2000) { 1 }.also { it[1999] = 2 })
        assertEquals(listOf(setOf(a, b)), groups(a, b, c))
    }

    @Test
    fun `a set splits into what's really the same`() {
        val content = bytes(2 * 1024 * 1024)
        val changed = content.copyOf().also { it[1024 * 1024] = (it[1024 * 1024] + 1).toByte() }
        val a1 = file("a1", content)
        val a2 = file("a2", content)
        val b1 = file("b1", changed)
        val b2 = file("b2", changed)
        val odd = file("odd", bytes(2 * 1024 * 1024, seed = 99L))
        val result = groups(a1, b1, odd, a2, b2)
        assertEquals(2, result.size)
        assertTrue(setOf(a1, a2) in result)
        assertTrue(setOf(b1, b2) in result)
    }

    @Test
    fun `a file that can't be read or has changed size is in no group`() {
        val content = bytes(100 * 1024)
        val a = file("a", content)
        val b = file("b", content)
        val missing = File(temp.root, "gone").path
        val grown = file("grown", content + 1)
        val found = DuplicateFinder.provenGroups(listOf(a, b, missing, grown), content.size.toLong())
        assertEquals(listOf(setOf(a, b)), found.map { it.toSet() })
    }

    @Test
    fun `leaving stops the comparison`() {
        val content = bytes(2 * 1024 * 1024)
        val a = file("a", content)
        val b = file("b", content)
        var reads = 0
        assertThrows(IllegalStateException::class.java) {
            DuplicateFinder.provenGroups(listOf(a, b), content.size.toLong()) {
                reads++
                if (reads > 3) throw IllegalStateException("left")
            }
        }
        // The comparison stopped early rather than reading both files to the end.
        assertTrue("Checked $reads times", reads <= 4)
    }

    @Test
    fun `comparing stops at the first block that differs`() {
        val content = bytes(8 * 1024 * 1024)
        val other = content.copyOf().also { it[100] = (it[100] + 1).toByte() }
        var checks = 0
        assertFalse(DuplicateFinder.sameBytes(temp.newFile("x").apply { writeBytes(content) }, temp.newFile("y").apply { writeBytes(other) }) { checks++ })
        assertEquals(1, checks)
    }

    @Test
    fun `a whole-file checksum sees a difference at the very end`() {
        val content = bytes(1024 * 1024)
        val other = content.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val first = DuplicateFinder.checksum(File(file("c1", content)))
        assertEquals(first, DuplicateFinder.checksum(File(file("c2", content))))
        assertNotEquals(first, DuplicateFinder.checksum(File(file("c3", other))))
        assertNull(DuplicateFinder.checksum(File(temp.root, "no-such-file")))
    }

    /** Prints the real cost, for the verification notes. Not a limit: a build machine's disk is no phone's. */
    @Test
    fun `timing at a realistic size`() {
        val size = 256 * 1024 * 1024
        val block = bytes(1024 * 1024)
        fun write(name: String, lastByte: Byte): String {
            val target = temp.newFile(name)
            target.outputStream().use { out ->
                repeat(size / block.size - 1) { out.write(block) }
                out.write(block.copyOf(block.size - 1))
                out.write(lastByte.toInt())
            }
            return target.path
        }
        val a = write("big-a", 1)
        val same = write("big-same", 1)
        val different = write("big-different", 2)

        var start = System.nanoTime()
        assertEquals(1, DuplicateFinder.provenGroups(listOf(a, same), size.toLong()).size)
        println("Proving 2 identical 256 MB files: ${(System.nanoTime() - start) / 1_000_000} ms")

        start = System.nanoTime()
        assertEquals(0, DuplicateFinder.provenGroups(listOf(a, different), size.toLong()).size)
        println("Telling apart 2 256 MB files that differ in the last byte: ${(System.nanoTime() - start) / 1_000_000} ms")
    }
}
