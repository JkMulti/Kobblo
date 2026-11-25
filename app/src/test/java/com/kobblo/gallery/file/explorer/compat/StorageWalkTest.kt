package com.kobblo.gallery.file.explorer.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Android 8 to 10 get every category total from this walk, and no such device has run it. */
class StorageWalkTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class Found {
        val files = HashMap<String, Long>()
        val empty = HashSet<String>()
    }

    private fun walk(root: File = temp.root, check: () -> Unit = {}): Found {
        val found = Found()
        StorageWalk.walk(
            root.toPath(),
            check,
            onFile = { path, size, _ -> found.files[path.removePrefix(root.path + "/")] = size },
            onEmptyFolder = { path, _ -> found.empty.add(path.removePrefix(root.path + "/")) }
        )
        return found
    }

    private fun file(path: String, size: Int = 3) = File(temp.root, path).also {
        it.parentFile?.mkdirs()
        it.writeBytes(ByteArray(size))
    }

    private fun folder(path: String) = File(temp.root, path).also { it.mkdirs() }

    @Test
    fun `it finds files at any depth, with their sizes`() {
        file("a.jpg", 10)
        file("DCIM/Camera/b.mp4", 20)
        file("Music/Album/Disc/c.flac", 30)
        val found = walk()
        assertEquals(mapOf("a.jpg" to 10L, "DCIM/Camera/b.mp4" to 20L, "Music/Album/Disc/c.flac" to 30L), found.files)
    }

    @Test
    fun `hidden files and folders don't count`() {
        file(".nomedia")
        file(".kobblo_trash/gone.jpg")
        file("Pictures/.thumbnails/t.jpg")
        file("Pictures/seen.jpg")
        assertEquals(setOf("Pictures/seen.jpg"), walk().files.keys)
    }

    @Test
    fun `what the system keeps for other apps is left alone`() {
        file("Android/data/com.other/cache/x.bin")
        file("Android/obb/com.other/y.obb")
        file("Android/media/com.other/z.mp3")
        assertEquals(setOf("Android/media/com.other/z.mp3"), walk().files.keys)
    }

    @Test
    fun `a folder with nothing in it is empty, and its parent isn't`() {
        folder("Download/Old/Older")
        file("Download/kept.txt")
        val empty = walk().empty
        assertTrue("Download/Old/Older" in empty)
        // It holds a folder, so it isn't. A walk can't say a folder holding only empties is empty in one pass.
        assertFalse("Download/Old" in empty)
        assertFalse("Download" in empty)
    }

    @Test
    fun `a folder holding only something hidden isn't empty, since deleting it would lose that`() {
        file("Pictures/Album/.nomedia")
        folder("Pictures/Hidden/.cache")
        val empty = walk().empty
        assertFalse("Pictures/Album" in empty)
        assertFalse("Pictures/Hidden" in empty)
    }

    @Test
    fun `the root is never called empty, even with nothing there`() {
        assertTrue(walk().empty.isEmpty())
    }

    @Test
    fun `a storage root it can't read gives nothing rather than failing`() {
        val missing = File(temp.root, "not/there")
        val found = walk(missing)
        assertTrue(found.files.isEmpty())
        assertTrue(found.empty.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `stopping is checked at each folder, so leaving the screen ends the walk`() {
        file("a/b/c.txt")
        var folders = 0
        walk(check = {
            folders++
            if (folders == 2) throw IllegalStateException("cancelled")
        })
    }
}
