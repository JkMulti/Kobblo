package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.AuthReason
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.fileops.FailReason
import com.kobblo.gallery.file.explorer.fileops.TransferFailure
import com.kobblo.gallery.file.explorer.fileops.TransferReport
import com.kobblo.gallery.file.explorer.music.MusicPlaybackState
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileWorkTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val root: File get() = temporary.root

    private fun make(name: String): FileItem = File(root, name).run {
        writeText(name)
        fileAt(absolutePath)
    }

    private fun names() = root.list().orEmpty().toSet()

    // Hiding.

    private val moves = ArrayList<Pair<String, String>>()
    private val walk = HideWalk { from, to -> moves += from.name to to.name }

    @Test
    fun `hiding puts a dot in front of each name`() {
        val run = HideRun.over(listOf(make("a.txt"), make("b.txt")))
        assertFalse(run.unhide)
        assertEquals(HideStop.Finished(HideRun(emptyList(), unhide = false, done = 2)), walk.goOn(run))
        assertEquals(setOf(".a.txt", ".b.txt"), names())
        assertEquals(listOf("a.txt" to ".a.txt", "b.txt" to ".b.txt"), moves)
    }

    @Test
    fun `only when everything is hidden is it unhidden`() {
        val hidden = make(".a.txt")
        assertTrue(HideRun.over(listOf(hidden)).unhide)
        val mixed = HideRun.over(listOf(hidden, make("b.txt")))
        assertFalse(mixed.unhide)
        assertEquals(listOf("b.txt"), mixed.waiting.map { it.name })
        walk.goOn(HideRun.over(listOf(hidden)))
        assertEquals(setOf("a.txt", "b.txt"), names())
    }

    @Test
    fun `a file that has gone counts as failed and the rest go on`() {
        val run = HideRun.over(listOf(fileAt(File(root, "gone").path), make("a")))
        assertEquals(HideStop.Finished(HideRun(emptyList(), unhide = false, done = 1, failed = 1)), walk.goOn(run))
    }

    @Test
    fun `a name that's taken stops it, with how far it got`() {
        val first = make("a.txt")
        val second = make("b.txt")
        val third = make("c.txt")
        make(".b.txt")
        make(".b (1).txt")
        val stop = walk.goOn(HideRun.over(listOf(first, second, third))) as HideStop.Blocked
        val conflict = stop.conflict
        assertEquals(second, conflict.file)
        assertEquals(".b.txt", conflict.targetName)
        assertEquals(File(root, ".b.txt").absolutePath, conflict.targetPath)
        assertEquals(listOf(third), conflict.remainingFiles)
        assertEquals(1, conflict.successCount)
        assertEquals(0, conflict.failedCount)
        assertFalse(conflict.isUnhide)
        assertFalse(conflict.suggestedName in names())
        // Nothing past the conflict has been touched.
        assertTrue("c.txt" in names())

        val resumed = HideRun.resuming(conflict).after(succeeded = true)
        assertEquals(HideStop.Finished(HideRun(emptyList(), unhide = false, done = 3)), walk.goOn(resumed))
    }

    // Numbered names.

    private fun numbered(
        style: String,
        prefix: Boolean = false,
        extension: String = "jpg",
        padding: Int = 3,
        start: Int = 1,
        base: String = "Trip"
    ) = NumberedNames(base, extension, padding, start, prefix, style)

    @Test
    fun `numbers are written as the style says, before or after`() {
        assertEquals("Trip.jpg", numbered("None").nameAt(4))
        assertEquals("Trip (001).jpg", numbered("(1)").nameAt(0))
        assertEquals("(001) Trip.jpg", numbered("(1)", prefix = true).nameAt(0))
        assertEquals("Trip_001.jpg", numbered("0001").nameAt(0))
        assertEquals("001_Trip.jpg", numbered("0001", prefix = true).nameAt(0))
        assertEquals("Trip 001.jpg", numbered("1").nameAt(0))
    }

    @Test
    fun `numbers count up, and one that's long enough isn't padded`() {
        val names = numbered("0001", padding = 2, start = 99)
        assertEquals(listOf("Trip_99.jpg", "Trip_100.jpg"), List(2, names::nameAt))
        assertEquals("Trip_7.jpg", numbered("0001", padding = 0, start = 7).nameAt(0))
    }

    @Test
    fun `an ending has one dot, or none when there is no ending`() {
        assertEquals("Trip_001.jpg", numbered("0001", extension = ".jpg").nameAt(0))
        assertEquals("Trip_001", numbered("0001", extension = "").nameAt(0))
        assertEquals("001", numbered("1", base = "", extension = "", prefix = true).nameAt(0))
    }

    // Deleting.

    private fun plan(
        vararg files: FileItem,
        inDrive: Boolean = false,
        fromCleaner: Boolean = false,
        binEnabled: Boolean = true
    ) = DeletePlan.of(files.toList(), inDrive, fromCleaner, binEnabled)

    @Test
    fun `files go to the bin, or for good when there's no bin`() {
        val file = make("a")
        assertEquals(listOf(file.path), plan(file).toBin)
        assertEquals(listOf(file.path), plan(file, binEnabled = false).forGood)
        assertFalse(plan(file).onlyDrive)
    }

    @Test
    fun `what's in drive goes through drive`() {
        val cloud = fileAt("drive_id:1")
        val local = make("a")
        val both = plan(cloud, local)
        assertEquals(listOf(cloud), both.inDrive)
        assertEquals(listOf(local.path), both.toBin)
        assertTrue(plan(cloud).onlyDrive)
        assertEquals(listOf(local), plan(local, inDrive = true).inDrive)
    }

    @Test
    fun `a duplicate whose other copy has gone is turned down`() {
        val copy = make("copy").copy(duplicateOf = File(root, "original").path)
        val refused = plan(copy).refused.single()
        assertEquals(copy.path, refused.path)
        assertEquals(FailReason.NOT_POSSIBLE, refused.reason)
        make("original")
        assertEquals(listOf(copy.path), plan(copy).toBin)
    }

    @Test
    fun `the cleaner's empty folders just go, unless something has arrived in them`() {
        val folder = File(root, "empty").apply { mkdirs() }
        val row = fileAt(folder.path, type = "folder").copy(isEmptyFolder = true)
        assertEquals(listOf(folder.path), plan(row, fromCleaner = true).emptied)
        assertEquals(listOf(folder.path), plan(row).toBin)
        File(folder, "new").writeText("x")
        assertEquals(listOf(folder.path), plan(row, fromCleaner = true).refused.map { it.path })
        assertEquals(listOf(folder.path), plan(row.copy(isEmptyFolder = false), fromCleaner = true).toBin)
    }

    @Test
    fun `deleted files leave every list they were in`() {
        val a = fileAt("/x/a")
        val b = fileAt("/x/b")
        val state = appState().copy(
            files = persistentListOf(a, b),
            recentFiles = persistentListOf(a),
            folderCache = persistentMapOf("/x" to persistentListOf(a, b), "/y" to persistentListOf(b))
        ).without(setOf("/x/a"))
        assertEquals(listOf(b), state.files)
        assertEquals(listOf(b), state.folderCache["/x"])
        assertEquals(listOf(b), state.folderCache["/y"])
        assertNull(state.cleanerData)
    }

    @Test
    fun `how long a deleted file is kept`() {
        assertEquals(7L * 86_400_000L, retentionMillis(7, "Days"))
        assertEquals(30_000L, retentionMillis(30, "seconds"))
        assertEquals(60_000L, retentionMillis(1, "Minute"))
        assertEquals(2L * 3_600_000L, retentionMillis(2, "hours"))
        assertEquals(14L * 86_400_000L, retentionMillis(2, "Weeks"))
        assertEquals(30L * 86_400_000L, retentionMillis(1, "month"))
        assertEquals(365L * 86_400_000L, retentionMillis(1, "years"))
        assertEquals(7L * 86_400_000L, retentionMillis(1, "fortnights"))
    }

    // The clipboard after a transfer.

    private val paths = listOf("/a", "/b", "/c")

    @Test
    fun `a transfer that went well leaves the clipboard empty`() {
        val state = appState()
        assertSame(state, state.keepingUnfinished(TransferReport(done = 3), paths, "cut"))
    }

    @Test
    fun `one that never began can be tried again whole`() {
        val state = appState().keepingUnfinished(null, paths, "copy")
        assertEquals(paths, state.clipboardPaths)
        assertEquals("copy", state.clipboardAction)
    }

    @Test
    fun `what failed stays to be tried again`() {
        val report = TransferReport(done = 2, failed = listOf(TransferFailure("/b", FailReason.DENIED)))
        val state = appState().keepingUnfinished(report, paths, "cut")
        assertEquals(listOf("/b"), state.clipboardPaths)
        assertEquals("cut", state.clipboardAction)
    }

    @Test
    fun `a move that was stopped leaves off what has already gone`() {
        val report = TransferReport(done = 1, cancelled = true, movedFrom = listOf("/a"))
        assertEquals(listOf("/b", "/c"), appState().keepingUnfinished(report, paths, "cut").clipboardPaths)
        val whole = TransferReport(done = 3, cancelled = true, movedFrom = paths)
        val state = appState().keepingUnfinished(whole, paths, "cut")
        assertTrue(state.clipboardPaths.isEmpty())
        assertNull(state.clipboardAction)
    }

    @Test
    fun `something copied since is the newer wish`() {
        val state = appState().copy(clipboardPaths = persistentListOf("/new"), clipboardAction = "copy")
        assertSame(state, state.keepingUnfinished(null, paths, "cut"))
    }

    // Copies of files from elsewhere.

    @Test
    fun `a copy is found again by its file's id and name`() {
        val copies = CloudCopies(root)
        val file = fileAt("drive_id:1", id = -1).copy(name = "My trip: día 1.pdf")
        assertNull(copies.ready(file))
        val place = copies.fresh(file)
        assertEquals(File(root, "cloud_cache/4294967295_My_trip__d_a_1.pdf"), place)
        assertTrue(place.parentFile!!.isDirectory)
        place.writeText("")
        assertNull(copies.ready(file))
        // What an interrupted download left is cleared, and a whole copy isn't.
        assertFalse(copies.fresh(file).exists())
        place.writeText("pdf")
        assertEquals(place, copies.ready(file))
        assertTrue(copies.fresh(file).exists())
        assertNull(copies.ready(file.copy(id = 2)))
    }

    @Test
    fun `music that has stopped closes its player`() {
        val state = appState().copy(showFullScreenPlayer = true, isAudioPlaying = true, audioTitle = "x")
        val stopped = state.withMusic(MusicPlaybackState())
        assertFalse(stopped.showFullScreenPlayer)
        assertFalse(stopped.isAudioPlaying)
        assertNull(stopped.currentAudioFile)
        assertEquals("", stopped.audioTitle)
        assertEquals("", stopped.audioArtist)
    }

    @Test
    fun `asking for the lock names what it's for, and then stops asking`() {
        val state = appState().asking(LockQuestion(AuthReason.OPEN_FILE, "/x/a", 5, "a"))
        assertEquals(AuthReason.OPEN_FILE, state.authRequestReason)
        assertEquals("/x/a", state.authRequestPath)
        assertEquals(5, state.authRequestFileId)
        assertEquals("a", state.authRequestFolderName)
        assertEquals(appState(), state.asking(null))
    }

    @Test
    fun `an id goes in or comes out`() {
        assertEquals(setOf(1, 2), persistentSetOf(1).toggled(2))
        assertEquals(setOf(1), persistentSetOf(1, 2).toggled(2))
    }
}
