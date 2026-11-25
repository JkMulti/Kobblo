package com.kobblo.gallery.file.explorer.fileops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedPathsTest {
    @Test
    fun `a lock covers the item itself`() {
        assertTrue(LockedPaths(setOf("/s/DCIM/a.jpg")).covers("/s/DCIM/a.jpg"))
    }

    @Test
    fun `a lock on a folder covers everything in it at any depth`() {
        val locks = LockedPaths(setOf("/s/Private"))
        assertTrue(locks.covers("/s/Private"))
        assertTrue(locks.covers("/s/Private/a.jpg"))
        assertTrue(locks.covers("/s/Private/x/y/z.jpg"))
    }

    @Test
    fun `a lock doesn't reach a neighbour with a similar name`() {
        val locks = LockedPaths(setOf("/s/Private"))
        assertFalse(locks.covers("/s/Private2/a.jpg"))
        assertFalse(locks.covers("/s/Privat"))
        assertFalse(locks.covers("/s/Other/Private"))
    }

    @Test
    fun `no locks covers nothing`() {
        val locks = LockedPaths(emptySet())
        assertFalse(locks.covers("/s/a.jpg"))
        assertNull(locks.coveringLock("/s/a.jpg"))
    }

    @Test
    fun `answers stay right when a folder has already been worked out`() {
        val locks = LockedPaths(setOf("/s/Private"))
        assertTrue(locks.covers("/s/Private/x/a.jpg"))
        assertTrue(locks.covers("/s/Private/x/b.jpg"))
        assertFalse(locks.covers("/s/Open/a.jpg"))
        assertFalse(locks.covers("/s/Open/b.jpg"))
    }

    @Test
    fun `once a folder's been opened its contents show, but not its own locked items`() {
        val locks = LockedPaths(setOf("/s/Private", "/s/Private/secret.jpg"), opened = setOf("/s/Private"))
        assertFalse(locks.covers("/s/Private/a.jpg"))
        assertFalse(locks.covers("/s/Private"))
        assertTrue(locks.covers("/s/Private/secret.jpg"))
    }

    @Test
    fun `a direct path is stopped by the topmost lock in the way`() {
        val locks = LockedPaths(setOf("/s/Private", "/s/Private/x/Inner"))
        assertEquals("/s/Private", locks.coveringLock("/s/Private/x/Inner/a.jpg"))
        assertNull(locks.coveringLock("/s/Open/a.jpg"))
    }

    @Test
    fun `a folder opened leaves any lock further in still in the way`() {
        val locks = LockedPaths(setOf("/s/Private", "/s/Private/x/Inner"), opened = setOf("/s/Private"))
        assertEquals("/s/Private/x/Inner", locks.coveringLock("/s/Private/x/Inner/a.jpg"))
        assertNull(locks.coveringLock("/s/Private/x/a.jpg"))
    }

    @Test
    fun `a lock follows its item when it moves`() {
        assertEquals("/b/a.jpg", LockedPaths.afterMove("/a/a.jpg", "/a/a.jpg", "/b/a.jpg"))
    }

    @Test
    fun `a lock follows when a folder above it is renamed`() {
        assertEquals("/new/x/a.jpg", LockedPaths.afterMove("/old/x/a.jpg", "/old", "/new"))
        assertEquals("/new", LockedPaths.afterMove("/old", "/old", "/new"))
    }

    @Test
    fun `a lock doesn't follow a move of something it isn't in`() {
        assertNull(LockedPaths.afterMove("/old2/a.jpg", "/old", "/new"))
        assertNull(LockedPaths.afterMove("/x/a.jpg", "/old", "/new"))
    }

    @Test
    fun `the widget leaves out what's inside a locked folder`() {
        val locked = setOf("/s/Private")
        assertFalse(com.kobblo.gallery.file.explorer.gallery.widget.isWidgetEligible("/s/Private/a.jpg", locked))
        assertFalse(com.kobblo.gallery.file.explorer.gallery.widget.isWidgetEligible("/s/Private/x/a.jpg", locked))
        assertTrue(com.kobblo.gallery.file.explorer.gallery.widget.isWidgetEligible("/s/Open/a.jpg", locked))
    }

    @Test
    fun `answers agree when a long list asks from many threads at once`() {
        val locks = LockedPaths(setOf("/s/Private"))
        val paths = List(2000) { i -> if (i % 2 == 0) "/s/Private/f${i % 7}/$i.jpg" else "/s/Open/f${i % 7}/$i.jpg" }
        val answers = paths.parallelStream().map { locks.covers(it) }.collect(java.util.stream.Collectors.toList())
        paths.forEachIndexed { index, path -> assertEquals(path, path.startsWith("/s/Private"), answers[index]) }
    }

    @Test
    fun `a folder being sent leaves out what's locked in it`() {
        val root = java.nio.file.Files.createTempDirectory("walk").toFile()
        try {
            root.resolve("album/secret").mkdirs()
            root.resolve("album/open.jpg").writeText("a")
            root.resolve("album/secret/hidden.jpg").writeText("b")
            root.resolve("album/locked.jpg").writeText("c")
            val locks = LockedPaths(setOf(root.resolve("album/secret").path, root.resolve("album/locked.jpg").path))
            val sent = com.kobblo.gallery.file.explorer.transfer.core.FolderWalker
                .walk(root.resolve("album"), skip = { locks.covers(it.path) })
                .map { it.relativePath }
            assertEquals(listOf("album/open.jpg"), sent)
        } finally {
            root.deleteRecursively()
        }
    }
}
