package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.SortMode
import com.kobblo.gallery.file.explorer.explorer.search.SearchSnapshot
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderListsTest {
    private val folder = fileAt("/x/Zoo", type = "folder")
    private val photo = fileAt("/x/b.jpg", type = "image", modified = 3, size = 10)
    private val song = fileAt("/x/a.mp3", type = "audio", modified = 1, size = 30)
    private val hidden = fileAt("/x/.secret.jpg", type = "image", modified = 2, size = 20)
    private val all = listOf(photo, song, hidden, folder)

    private fun names(state: AppState, reversed: Boolean = false, files: List<FileItem> = all) =
        Arrangement { reversed }.of(files, state).map { it.name }

    private val state = appState().copy(location = "/x")

    @Test
    fun `folders come first, then the sort, and hidden files stay out`() {
        assertEquals(listOf("Zoo", "a.mp3", "b.jpg"), names(state))
        assertEquals(listOf("Zoo", "b.jpg", "a.mp3"), names(state, reversed = true))
        assertEquals(listOf("Zoo", "b.jpg", ".secret.jpg", "a.mp3"), names(state.copy(showHiddenFiles = true, sortMode = SortMode.DATE)))
        assertEquals(listOf("Zoo", "a.mp3", "b.jpg"), names(state.copy(sortMode = SortMode.SIZE)))
    }

    @Test
    fun `a filter keeps its kind, and a search what its words are in`() {
        assertEquals(listOf("b.jpg"), names(state.copy(filter = "image")))
        assertEquals(listOf("a.mp3"), names(state.copy(query = "MP3")))
        assertEquals(emptyList<String>(), names(state.copy(filter = "image", query = "mp3")))
    }

    @Test
    fun `a category ignores the filter and the sort, and reads newest first`() {
        val category = state.copy(location = "category/Large", filter = "image", sortMode = SortMode.SIZE)
        assertEquals(listOf("b.jpg", "a.mp3", "Zoo"), names(category, reversed = true))
        assertEquals(listOf("a.mp3"), names(category.copy(query = "a.m")))
    }

    @Test
    fun `the home feed is shown as it was read`() {
        val feed = state.copy(location = "gallery", query = "zzz", filter = "audio")
        assertSame(all, Arrangement { true }.of(all, feed))
    }

    @Test
    fun `which way round is only asked of folders that are sorted`() {
        val asked = ArrayList<String>()
        val arrangement = Arrangement { asked += it; false }
        arrangement.of(all, state.copy(location = "gallery"))
        arrangement.of(all, state.copy(location = "category/Large"))
        arrangement.of(all, state)
        assertEquals(listOf("/x"), asked)
    }

    private val read = all.toImmutableList()

    @Test
    fun `what was read is shown and remembered`() {
        val shown = state.copy(isLoading = true).withListing("/x", read)
        assertSame(read, shown.files)
        assertSame(read, shown.folderCache["/x"])
        assertTrue(shown.galleryFiles.isEmpty())
        assertFalse(shown.isLoading)
        assertSame(read, state.withListing("gallery", read).galleryFiles)
    }

    @Test
    fun `nothing is remembered from a search`() {
        val cache = persistentMapOf("/x" to persistentListOf(song))
        val shown = state.copy(query = "a", folderCache = cache).withListing("/x", read)
        assertSame(read, shown.files)
        assertSame(cache, shown.folderCache)
    }

    @Test
    fun `a huge list isn't remembered, and drops what was, unless it's the home feed`() {
        val huge = List(20_001) { fileAt("/x/$it", id = it) }.toImmutableList()
        val remembering = state.copy(folderCache = persistentMapOf("/x" to persistentListOf(song), "/y" to persistentListOf(photo)))
        assertNull(remembering.withListing("/x", huge).folderCache["/x"])
        assertEquals(setOf("/y"), remembering.withListing("/x", huge).folderCache.keys)
        assertSame(huge, remembering.withListing("gallery", huge).folderCache["gallery"])
        val large = huge.subList(0, 20_000)
        assertSame(large, remembering.withListing("/x", large).folderCache["/x"])
    }

    private fun found(done: Boolean, vararg files: FileItem) = SearchSnapshot(files.toList(), done, capped = false)

    @Test
    fun `search results show as they come, and the wait ends when it's done`() {
        val searching = state.copy(query = "a", isLoading = true)
        val some = searching.withFound(found(done = false, song), searched = "a", at = "/x")
        assertEquals(listOf(song), some.files)
        assertTrue(some.isLoading)
        val whole = some.withFound(found(done = true, song, photo), searched = "a", at = "/x")
        assertEquals(listOf(song, photo), whole.files)
        assertFalse(whole.isLoading)
    }

    @Test
    fun `a late answer changes nothing`() {
        val searching = state.copy(query = "a", isLoading = true)
        assertSame(searching, searching.withFound(found(done = true, song), searched = "ab", at = "/x"))
        assertSame(searching, searching.withFound(found(done = true, song), searched = "a", at = "/y"))
    }

    @Test
    fun `a selection is kept while a search is still looking, and then only what it found`() {
        val searching = state.copy(query = "a", selectedFiles = persistentSetOf(song.id, photo.id), isSelectionMode = true)
        val some = searching.withFound(found(done = false), searched = "a", at = "/x")
        assertEquals(setOf(song.id, photo.id), some.selectedFiles)
        val whole = some.withFound(found(done = true, song), searched = "a", at = "/x")
        assertEquals(setOf(song.id), whole.selectedFiles)
        assertTrue(whole.isSelectionMode)
        val none = some.withFound(found(done = true), searched = "a", at = "/x")
        assertTrue(none.selectedFiles.isEmpty())
        assertFalse(none.isSelectionMode)
    }

    @Test
    fun `stars are put on and taken off, and a file that's right stays the same`() {
        val starred = photo.copy(isPinned = true)
        val stamped = listOf(starred, song, folder).stamped(setOf("/x/a.mp3"))
        assertEquals(listOf(false, true, false), stamped.map { it.isPinned })
        assertSame(folder, stamped[2])
    }

    private val live = LiveState(state.copy(files = persistentListOf(photo, song.copy(isLocked = true), folder)))
    private val selection = Selection(live)

    @Test
    fun `choosing a file and choosing it again`() {
        selection.toggle(photo.id)
        assertEquals(setOf(photo.id), live.now.selectedFiles)
        assertTrue(live.now.isSelectionMode)
        assertEquals(listOf(photo), selection.files())
        selection.toggle(photo.id)
        assertTrue(live.now.selectedFiles.isEmpty())
        assertFalse(live.now.isSelectionMode)
    }

    @Test
    fun `nothing locked can be chosen, but it can be let go of`() {
        selection.toggle(song.id)
        assertTrue(live.now.selectedFiles.isEmpty())
        live.update { it.copy(selectedFiles = persistentSetOf(song.id), isSelectionMode = true) }
        selection.toggle(song.id)
        assertTrue(live.now.selectedFiles.isEmpty())
        selection.all(null)
        assertEquals(setOf(photo.id, folder.id), live.now.selectedFiles)
        selection.all(listOf(song.id, folder.id))
        assertEquals(setOf(folder.id), live.now.selectedFiles)
        selection.all(listOf(song.id))
        assertFalse(live.now.isSelectionMode)
    }

    @Test
    fun `choosing nothing ends it`() {
        selection.all(null)
        selection.none()
        assertTrue(live.now.selectedFiles.isEmpty())
        assertFalse(live.now.isSelectionMode)
    }
}
