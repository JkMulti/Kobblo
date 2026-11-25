package com.kobblo.gallery.file.explorer.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream

class IndexCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val canon = PhotoDetails("Canon EOS R5", 7, listOf("Holiday", "Zürich"))

    private fun file() = folder.root.resolve("gallery/details.bin")

    @Test
    fun `what's written is what's read`() {
        val before = mapOf(
            1 to IndexEntry(100L, ProbeFlags.MOTION_PHOTO, canon),
            2 to IndexEntry(200L, 0, canon),
            3 to IndexEntry(Long.MAX_VALUE, ProbeFlags.PANORAMA, null),
            -4 to IndexEntry(0L, 0, PhotoDetails(null, -1, listOf("only a tag")))
        )
        IndexCache.write(file(), 1, before)
        val after = IndexCache.read(file(), 1)
        assertEquals(4, after.size)
        assertEquals(100L, after[1]!!.sizeBytes)
        assertEquals(ProbeFlags.MOTION_PHOTO, after[1]!!.flags)
        assertEquals(canon, after[1]!!.details)
        assertNull(after[3]!!.details)
        assertEquals(ProbeFlags.PANORAMA, after[3]!!.flags)
        assertEquals(Long.MAX_VALUE, after[3]!!.sizeBytes)
        assertNull(after[-4]!!.details!!.camera)
    }

    @Test
    fun `photos with the same details share one copy after reading`() {
        IndexCache.write(file(), 1, mapOf(1 to IndexEntry(1L, 0, canon), 2 to IndexEntry(2L, 0, canon)))
        val after = IndexCache.read(file(), 1)
        assertSame(after[1]!!.details, after[2]!!.details)
    }

    @Test
    fun `no file is just an empty start`() {
        assertTrue(IndexCache.read(file(), 1).isEmpty())
    }

    @Test
    fun `a new table of places throws the stored ones away`() {
        IndexCache.write(file(), 1, mapOf(1 to IndexEntry(1L, 0, canon)))
        assertTrue(IndexCache.read(file(), 2).isEmpty())
    }

    @Test
    fun `a damaged file costs a new pass and nothing else`() {
        IndexCache.write(file(), 1, (1..50).associateWith { IndexEntry(it.toLong(), 0, canon) })
        val whole = file().readBytes()
        file().writeBytes(whole.copyOf(whole.size / 2))
        assertTrue(IndexCache.read(file(), 1).isEmpty())
        file().writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(IndexCache.read(file(), 1).isEmpty())
    }

    @Test
    fun `a count that claims the whole heap doesn't take the app down`() {
        file().parentFile!!.mkdirs()
        DataOutputStream(file().outputStream()).use {
            it.writeInt(2)
            it.writeInt(1)
            it.writeInt(Int.MAX_VALUE)
        }
        assertTrue(IndexCache.read(file(), 1).isEmpty())
    }

    @Test
    fun `text from a hostile photo can't stop anything being saved`() {
        val huge = "x".repeat(200_000)
        IndexCache.write(file(), 1, mapOf(1 to IndexEntry(1L, 0, PhotoDetails(huge, 1, listOf(huge)))))
        val after = IndexCache.read(file(), 1)[1]!!.details!!
        assertEquals(IndexCache.MaxTextLength, after.camera!!.length)
        assertEquals(IndexCache.MaxTextLength, after.tags.single().length)
    }

    @Test
    fun `no temporary file is left behind`() {
        IndexCache.write(file(), 1, mapOf(1 to IndexEntry(1L, 0, canon)))
        assertFalse(folder.root.resolve("gallery/details.bin.tmp").exists())
    }
}
