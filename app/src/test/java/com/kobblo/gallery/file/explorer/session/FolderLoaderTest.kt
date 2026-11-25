package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import java.io.IOException
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderLoaderTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val live = LiveState(appState().copy(location = "/a", isLoading = true, showHiddenFiles = true))
    private val arrangement = Arrangement { false }
    private val contents = FolderContents(scope, live, arrangement)

    private class Around : FolderLoader.Outside {
        val log = ArrayList<String>()
        override fun isSearch(query: String) = query.isNotEmpty()
        override fun searchAgain(query: String) { log += "search $query" }
        override fun favorites(): ImmutableList<String> = persistentListOf("/starred")
        override fun countCategories() { log += "count" }
        override fun timeRead(location: String): (count: Int) -> Unit {
            log += "read $location"
            return { log += "came $location $it" }
        }
    }

    private val around = Around()

    /** Answers each location when the test says so, whether or not anyone is still waiting: a read nothing can interrupt. */
    private class Answers : LocationReader {
        private val answers = HashMap<String, CompletableDeferred<List<FileItem>>>()
        var firstRows: List<FileItem>? = null
        fun of(location: String) = answers.getOrPut(location) { CompletableDeferred() }
        override suspend fun read(location: String, firstRows: suspend (List<FileItem>) -> Unit): List<FileItem> {
            this.firstRows?.let { firstRows(it) }
            return withContext(NonCancellable) { of(location).await() }
        }
    }

    private val answers = Answers()
    private val threads = Dispatchers.Unconfined.let { LoaderThreads(it, it, it) }
    private val loader = FolderLoader(scope, live, PlainWords, answers, contents, arrangement, around, threads)

    private val inA = listOf(fileAt("/a/2"), fileAt("/a/1"))
    private val inB = listOf(fileAt("/b/1"))

    private fun open(location: String) {
        live.update { it.copy(location = location, isLoading = true) }
        loader.load(location)
    }

    private fun shown() = live.now.files.map { it.path }

    @Test
    fun `what was read is arranged, shown and remembered`() {
        open("/a")
        assertTrue(live.now.isLoading)
        answers.of("/a").complete(inA)
        assertEquals(listOf("/a/1", "/a/2"), shown())
        assertEquals(inA, contents.all)
        assertEquals(listOf("/a/1", "/a/2"), live.now.folderCache["/a"]?.map { it.path })
        assertFalse(live.now.isLoading)
        assertEquals(listOf("read /a", "came /a 2"), around.log)
    }

    @Test
    fun `a folder that has been left changes nothing when it comes at last`() {
        open("/a")
        open("/b")
        answers.of("/b").complete(inB)
        answers.of("/a").complete(inA)
        assertEquals(listOf("/b/1"), shown())
        assertEquals(inB, contents.all)
        assertNull(live.now.folderCache["/a"])
        assertEquals(listOf("read /a", "read /b", "came /b 1"), around.log)
    }

    @Test
    fun `it changes nothing before the new one comes, either`() {
        open("/a")
        open("/b")
        answers.of("/a").complete(inA)
        assertTrue(shown().isEmpty())
        assertTrue(contents.all.isEmpty())
        assertTrue(live.now.isLoading)
        answers.of("/b").complete(inB)
        assertEquals(listOf("/b/1"), shown())
    }

    @Test
    fun `a folder that has been left can't fail the one that's open`() {
        open("/a")
        open("/b")
        answers.of("/b").complete(inB)
        answers.of("/a").completeExceptionally(IOException("late"))
        assertEquals(listOf("/b/1"), shown())
        assertEquals(inB, contents.all)
        assertNull(live.now.errorMessage)
    }

    @Test
    fun `a folder that can't be read is empty and says so`() {
        contents.all = inA
        live.update { it.copy(files = persistentListOf(fileAt("/old"))) }
        open("/a")
        answers.of("/a").completeExceptionally(IOException("no"))
        assertTrue(shown().isEmpty())
        assertTrue(contents.all.isEmpty())
        assertFalse(live.now.isLoading)
        assertEquals(PlainWords.text(R.string.app_folder_open_failed), live.now.errorMessage)
        assertNull(live.now.recoverableAuthIntent)
    }

    @Test
    fun `while a search is on screen it's asked again instead`() {
        live.update { it.copy(files = persistentListOf(fileAt("/found"))) }
        open("/a")
        live.update { it.copy(query = "x") }
        answers.of("/a").complete(inA)
        assertEquals(listOf("/found"), shown())
        // Clearing the search shows what has been read now.
        assertEquals(inA, contents.all)
        assertEquals(listOf("read /a", "search x"), around.log)
    }

    @Test
    fun `the first rows of a long list show while the rest is read`() {
        answers.firstRows = inB
        open("gallery")
        assertEquals(listOf("/b/1"), shown())
        assertEquals(inB, live.now.galleryFiles)
        assertFalse(live.now.isLoading)
        answers.of("gallery").complete(inA)
        // The feed keeps the order it was read in.
        assertEquals(listOf("/a/2", "/a/1"), shown())
        assertEquals(inA, live.now.galleryFiles)
    }

    @Test
    fun `the list of locations stops a read and has no files`() {
        open("/a")
        open("locations")
        answers.of("/a").complete(inA)
        assertTrue(shown().isEmpty())
        assertTrue(contents.all.isEmpty())
        assertFalse(live.now.isLoading)
        assertEquals(listOf("/starred"), live.now.favoritePaths)
        assertEquals(listOf("read /a", "count"), around.log)
    }

    @Test
    fun `opening Send lets a read go on`() {
        open("/a")
        open("send")
        assertEquals("send", live.now.location)
        assertFalse(live.now.isLoading)
        answers.of("/a").complete(inA)
        assertEquals(listOf("/a/1", "/a/2"), live.now.folderCache["/a"]?.map { it.path })
    }
}
