package com.kobblo.gallery.file.explorer.gallery

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.gallery.similar.DifferenceHash
import com.kobblo.gallery.file.explorer.gallery.similar.DuplicateGroup
import com.kobblo.gallery.file.explorer.gallery.similar.PhotoPrint
import com.kobblo.gallery.file.explorer.gallery.similar.SimilarDistance
import com.kobblo.gallery.file.explorer.gallery.similar.bestFirst
import com.kobblo.gallery.file.explorer.gallery.similar.buildReview
import com.kobblo.gallery.file.explorer.gallery.similar.filesSharingSize
import com.kobblo.gallery.file.explorer.gallery.similar.groupPhotos
import com.kobblo.gallery.file.explorer.gallery.similar.looksLikeCopyName
import com.kobblo.gallery.file.explorer.gallery.similar.looksSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SimilarLogicTest {
    private fun file(
        id: Int,
        name: String = "$id.jpg",
        folder: String = "/sdcard/Pictures",
        bytes: Long = 1000,
        moment: Long = 1000,
        pinned: Boolean = false
    ) = FileItem(
        id = id,
        path = "$folder/$name",
        name = name,
        type = "image",
        kind = "media",
        size = "",
        changed = "",
        owner = "Me",
        isPinned = pinned,
        sizeBytes = bytes,
        lastModified = moment
    )

    /** A hash with a good spread of bits, so it isn't taken for a plain picture. */
    private val base = 0x5A3C_96F0_0FC3_A569L

    private fun print(hash: Long = base, width: Int = 800, height: Int = 600, r: Int = 100, g: Int = 100, b: Int = 100) =
        PhotoPrint(hash, width, height, r, g, b)

    private fun flip(hash: Long, vararg bits: Int): Long = bits.fold(hash) { h, bit -> h xor (1L shl bit) }

    @Test
    fun `the hash compares each pixel to its right-hand neighbour`() {
        val luma = IntArray(72) { 0 }
        // Row 0 falls from left to right across every pair, so its eight bits are all set.
        for (x in 0 until 9) luma[x] = 100 - x
        assertEquals(0xFFL, DifferenceHash.of(luma))
    }

    @Test
    fun `brightness alone doesn't change the hash`() {
        val random = Random(1)
        val luma = IntArray(72) { random.nextInt(0, 200) }
        val brighter = IntArray(72) { luma[it] + 40 }
        assertEquals(DifferenceHash.of(luma), DifferenceHash.of(brighter))
    }

    @Test
    fun `plain pictures don't count as having a hash worth matching`() {
        assertTrue(DifferenceHash.isTooPlain(0L))
        assertTrue(DifferenceHash.isTooPlain(-1L))
        assertFalse(DifferenceHash.isTooPlain(base))
    }

    @Test
    fun `close hashes match and far ones don't`() {
        assertTrue(looksSame(print(), print(hash = flip(base, 1, 9, 20, 40, 63))))
        assertFalse(looksSame(print(), print(hash = flip(base, 1, 9, 20, 40, 60, 63))))
    }

    @Test
    fun `lookalikes of a different shape or color don't match`() {
        assertFalse(looksSame(print(), print(width = 600, height = 800)))
        assertFalse(looksSame(print(), print(r = 200)))
        assertTrue(looksSame(print(), print(r = 120)))
    }

    @Test
    fun `near photos group, and strangers stay out`() {
        val prints = listOf(print(), print(hash = flip(base, 3)), print(hash = flip(base, 3, 4, 5)), print(hash = base.inv() xor 0x1234L))
        val groups = groupPhotos(prints, List(4) { null })
        assertEquals(listOf(listOf(0, 1, 2)), groups)
    }

    @Test
    fun `plain pictures never group by hash`() {
        val prints = listOf(print(hash = 0L), print(hash = 0L), print(hash = 1L))
        assertTrue(groupPhotos(prints, List(3) { null }).isEmpty())
    }

    @Test
    fun `identical content groups even without a print`() {
        val groups = groupPhotos(listOf(null, null, print()), listOf("abc", "abc", null))
        assertEquals(listOf(listOf(0, 1)), groups)
    }

    @Test
    fun `an exact copy and a lookalike join one group`() {
        // 0 and 1 are the same file. 2 only looks like 1. They're all one set to choose from.
        val groups = groupPhotos(listOf(print(), print(), print(hash = flip(base, 7))), listOf("x", "x", null))
        assertEquals(listOf(listOf(0, 1, 2)), groups)
    }

    @Test
    fun `the bucket search finds what a full comparison finds`() {
        val random = Random(42)
        val hashes = ArrayList<Long>()
        repeat(300) {
            val root = random.nextLong()
            hashes.add(root)
            // Some have a close relative, at every distance up to the limit and just past it.
            if (it % 3 == 0) {
                val bits = (0 until 64).shuffled(random).take(random.nextInt(1, SimilarDistance + 2)).toIntArray()
                hashes.add(flip(root, *bits))
            }
        }
        val prints = hashes.map { print(hash = it) }
        val fast = groupPhotos(prints, List(prints.size) { null }).map { it.toSet() }.toSet()

        val parent = IntArray(prints.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) x = parent[x]
            return x
        }
        for (a in prints.indices) for (b in a + 1 until prints.size) {
            val plain = DifferenceHash.isTooPlain(prints[a].hash) || DifferenceHash.isTooPlain(prints[b].hash)
            if (!plain && looksSame(prints[a], prints[b])) parent[find(b)] = find(a)
        }
        val slow = prints.indices.groupBy { find(it) }.values.filter { it.size > 1 }.map { it.toSet() }.toSet()
        assertEquals(slow, fast)
    }

    @Test
    fun `copy-sounding names are noticed`() {
        assertTrue(looksLikeCopyName("IMG (1).jpg"))
        assertTrue(looksLikeCopyName("IMG copy.jpg"))
        assertTrue(looksLikeCopyName("exact_copy.jpg"))
        assertFalse(looksLikeCopyName("original.jpg"))
    }

    @Test
    fun `the best is a favorite, then the largest, then the most original looking`() {
        val noSize: (FileItem) -> Pair<Int, Int> = { 0 to 0 }
        val big = file(1, bytes = 5000)
        val small = file(2, bytes = 100)
        val favorite = file(3, bytes = 100, pinned = true)
        assertEquals(listOf(3, 1, 2), bestFirst(listOf(small, big, favorite), noSize).map { it.id })

        val sizes: (FileItem) -> Pair<Int, Int> = { if (it.id == 2) 4000 to 3000 else 800 to 600 }
        assertEquals(2, bestFirst(listOf(big, small), sizes).first().id)

        val copy = file(4, name = "photo (1).jpg")
        val original = file(5, name = "photo.jpg")
        assertEquals(5, bestFirst(listOf(copy, original), noSize).first().id)

        val elsewhere = file(6, name = "a.jpg", folder = "/sdcard/Download")
        val camera = file(7, name = "b.jpg", folder = "/sdcard/DCIM/Camera")
        assertEquals(7, bestFirst(listOf(elsewhere, camera), noSize).first().id)

        val newer = file(8, name = "a.jpg", moment = 9000)
        val older = file(9, name = "b.jpg", moment = 10)
        assertEquals(9, bestFirst(listOf(newer, older), noSize).first().id)
    }

    @Test
    fun `proven copies are marked and lead, and lookalikes are marked for nobody`() {
        val files = listOf(
            file(1, bytes = 100),
            file(2, bytes = 100),
            file(3, bytes = 9000),
            file(4, bytes = 4000)
        )
        val review = buildReview(files, listOf(listOf(0, 1), listOf(2, 3)), listOf("a", "a", null, null)) { 0 to 0 }
        assertTrue(review[0].isExact)
        assertEquals(setOf(2), review[0].remove)
        assertEquals(100L, review[0].freeableBytes)

        assertFalse(review[1].isExact)
        assertEquals("Nothing that only looks the same goes without someone choosing it", emptySet<Int>(), review[1].remove)
        assertEquals(0L, review[1].freeableBytes)
        assertEquals(listOf(3, 4), review[1].members.map { it.id })
        assertEquals(3, review[1].keeper?.id)
    }

    @Test
    fun `same size and different bytes is a lookalike, not a copy`() {
        val files = listOf(file(1, bytes = 500), file(2, bytes = 500))
        val review = buildReview(files, listOf(listOf(0, 1)), listOf("one checksum", "another checksum")) { 0 to 0 }
        assertFalse(review.single().isExact)
        assertEquals(emptySet<Int>(), review.single().remove)
    }

    @Test
    fun `a group with both only marks the proven copies`() {
        val files = listOf(
            file(1, name = "a.jpg", bytes = 500),
            file(2, name = "a copy.jpg", bytes = 500),
            file(3, name = "b.jpg", bytes = 400)
        )
        val review = buildReview(files, listOf(listOf(0, 1, 2)), listOf("same", "same", null)) { 0 to 0 }
        val group = review.single()
        assertFalse(group.isExact)
        assertEquals(setOf(2), group.remove)
        // The copy's twin stays, whichever order they sort into.
        assertTrue(group.members.first { it.id == 1 }.id !in group.remove)
    }

    @Test
    fun `lookalikes can still be chosen by hand`() {
        val files = listOf(file(1), file(2))
        val group = buildReview(files, listOf(listOf(0, 1)), listOf(null, null)) { 0 to 0 }.single()
        assertEquals(setOf(2), group.toggled(2).remove)
    }

    @Test
    fun `one photo in a group always stays`() {
        val group = DuplicateGroup(listOf(file(1), file(2)), isExact = true, remove = setOf(2))
        val refused = group.toggled(1)
        assertSame(group, refused)
        val freed = group.toggled(2)
        assertEquals(emptySet<Int>(), freed.remove)
        assertEquals(setOf(1), freed.toggled(1).remove)
    }

    @Test
    fun `only files sharing a size get read whole`() {
        val files = listOf(file(1, bytes = 10), file(2, bytes = 10), file(3, bytes = 20), file(4, bytes = 0), file(5, bytes = 0))
        assertEquals(setOf(1, 2), filesSharingSize(files))
    }
}
