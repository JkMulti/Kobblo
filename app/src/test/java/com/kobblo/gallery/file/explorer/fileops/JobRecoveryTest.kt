package com.kobblo.gallery.file.explorer.fileops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What a kill leaves is whatever the journal and the disk say, so these write both and read back the verdict. */
class JobRecoveryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun File.withFile(name: String, text: String = "data"): File =
        resolve(name).also { it.parentFile?.mkdirs(); it.writeText(text) }

    private fun journal(kind: JobKind, sources: List<File> = emptyList(), label: String = "dest", folder: String? = null): JobJournal {
        val journal = JobJournal(temp.newFile())
        journal.begin(1L, JobPlan(kind, label, folder, sources.map { it.path }), System.currentTimeMillis())
        journal.ran()
        return journal
    }

    private fun settled(journal: JobJournal): SettledJob = JobRecovery.settle(JobJournal.read(journal.file)!!)

    @Test
    fun `the note reads back as written, with awkward names`() {
        val awkward = "/x/with\ttab\nand\\slash.txt"
        val journal = JobJournal(temp.newFile())
        journal.begin(7L, JobPlan(JobKind.MOVE, "Dest\tname", "/y/dest", listOf(awkward, "/x/plain")), 1234L)
        journal.ran()
        journal.started(awkward, "/y/dest/a")
        journal.verified(awkward, "/y/dest/a")
        val record = JobJournal.read(journal.file)!!
        assertEquals(JobKind.MOVE, record.kind)
        assertEquals("Dest\tname", record.label)
        assertEquals("/y/dest", record.folder)
        assertEquals(1234L, record.startedAt)
        assertEquals(listOf(awkward, "/x/plain"), record.sources)
        assertTrue(record.ran)
        assertEquals("/y/dest/a", record.begun[awkward])
        assertTrue(awkward in record.verified)
    }

    @Test
    fun `a last line cut short by the kill is just skipped`() {
        val journal = journal(JobKind.COPY)
        journal.started("/x/a", "/y/a")
        journal.finished("/x/a", "/y/a")
        journal.file.appendText("B\t/x/half")
        val record = JobJournal.read(journal.file)!!
        assertEquals(1, record.finished.size)
        assertTrue(record.begun.isEmpty())
    }

    @Test
    fun `a note with no header isn't taken for anything`() {
        val file = temp.newFile()
        file.writeText("nonsense\n")
        assertNull(JobJournal.read(file))
    }

    @Test
    fun `a job that never got its turn says so and nothing is touched`() {
        val source = temp.newFolder("from").withFile("a.txt")
        val journal = JobJournal(temp.newFile())
        journal.begin(1L, JobPlan(JobKind.COPY, "dest", "/y", listOf(source.path)), 0L)
        val job = settled(journal)
        assertFalse(job.ran)
        assertEquals(0, job.finished)
        assertTrue(source.exists())
    }

    @Test
    fun `a copy killed partway keeps what's whole, removes what isn't and leaves the originals`() {
        val from = temp.newFolder("from")
        val to = temp.newFolder("to")
        val done = from.withFile("done.txt")
        val half = from.withFile("half/inner.txt")
        val untouched = from.withFile("untouched.txt")
        val journal = journal(JobKind.COPY, listOf(done, half.parentFile!!, untouched))
        val doneCopy = to.withFile("done.txt")
        journal.started(done.path, doneCopy.path)
        journal.finished(done.path, doneCopy.path)
        // A folder is made under its real name as it goes, which is exactly what could look finished.
        val partial = to.resolve("half").also { it.mkdirs() }
        partial.withFile("first.txt")
        journal.started(half.parentFile!!.path, partial.path)

        val job = settled(journal)

        assertEquals(3, job.total)
        assertEquals(1, job.finished)
        assertEquals(1, job.partialsRemoved)
        assertFalse("the half-made folder has to go", partial.exists())
        assertTrue(doneCopy.exists())
        assertTrue(done.exists())
        assertTrue(half.exists())
        assertTrue(untouched.exists())
        assertEquals(listOf(doneCopy.path), job.outputs)
    }

    @Test
    fun `a temporary file left by a kill goes too`() {
        val from = temp.newFolder("from")
        val to = temp.newFolder("to")
        val source = from.withFile("big.bin")
        val journal = journal(JobKind.COPY, listOf(source))
        journal.started(source.path, to.resolve("big.bin").path)
        val part = SafeFiles.partFileIn(to).also { it.writeText("half") }
        settled(journal)
        assertFalse(part.exists())
    }

    @Test
    fun `an older temporary file isn't this job's to clear`() {
        val from = temp.newFolder("from")
        val to = temp.newFolder("to")
        val source = from.withFile("big.bin")
        val older = SafeFiles.partFileIn(to).also { it.writeText("someone else's") }
        older.setLastModified(System.currentTimeMillis() - 60_000)
        val journal = journal(JobKind.COPY, listOf(source))
        journal.started(source.path, to.resolve("big.bin").path)
        settled(journal)
        assertTrue(older.exists())
    }

    @Test
    fun `if the original of a half-made copy has gone, what's left is kept and said`() {
        val to = temp.newFolder("to")
        val partial = to.resolve("half").also { it.mkdirs() }
        partial.withFile("first.txt")
        val journal = journal(JobKind.COPY, listOf(File(temp.root, "gone")))
        journal.started(File(temp.root, "gone").path, partial.path)
        val job = settled(journal)
        assertTrue(partial.exists())
        assertEquals(listOf(partial.path), job.partialsKept)
    }

    @Test
    fun `a finished item that has since gone isn't counted as arrived`() {
        val from = temp.newFolder("from")
        val source = from.withFile("a.txt")
        val journal = journal(JobKind.COPY, listOf(source))
        journal.started(source.path, "/nowhere/a.txt")
        journal.finished(source.path, "/nowhere/a.txt")
        assertEquals(0, settled(journal).finished)
    }

    @Test
    fun `a rename that happened counts as moved even though its finish wasn't noted`() {
        val from = temp.newFolder("from")
        val to = temp.newFolder("to")
        val source = from.resolve("a.txt")
        val dest = to.withFile("a.txt")
        val journal = journal(JobKind.MOVE, listOf(source))
        journal.started(source.path, dest.path)
        val job = settled(journal)
        assertEquals(1, job.finished)
        assertEquals(0, job.partialsRemoved)
        assertTrue(dest.exists())
        assertEquals(listOf(source.path to dest.path), job.moved)
        assertEquals(listOf(source.path), job.gone)
    }

    @Test
    fun `a rename that hadn't happened leaves the file where it was`() {
        val from = temp.newFolder("from")
        val to = temp.newFolder("to")
        val source = from.withFile("a.txt")
        val journal = journal(JobKind.MOVE, listOf(source))
        journal.started(source.path, to.resolve("a.txt").path)
        val job = settled(journal)
        assertEquals(0, job.finished)
        assertTrue(source.exists())
        assertTrue(job.moved.isEmpty())
    }

    @Test
    fun `a move across volumes killed while copying removes the partial and keeps the source`() {
        val from = temp.newFolder("from")
        val to = temp.newFolder("to")
        val source = from.withFile("album/a.jpg", "original")
        val folder = source.parentFile!!
        val partial = to.resolve("album").also { it.mkdirs() }
        partial.withFile("half.jpg")
        val journal = journal(JobKind.MOVE, listOf(folder))
        journal.started(folder.path, partial.path)
        val job = settled(journal)
        assertEquals(0, job.finished)
        assertEquals(1, job.partialsRemoved)
        assertFalse(partial.exists())
        assertEquals("original", source.readText())
    }

    @Test
    fun `a move killed while removing the original keeps both and says what's left`() {
        val from = temp.newFolder("from")
        val to = temp.newFolder("to")
        val folder = from.resolve("album").also { it.mkdirs() }
        folder.withFile("left.jpg", "still here")
        val whole = to.resolve("album").also { it.mkdirs() }
        whole.withFile("left.jpg", "still here")
        whole.withFile("gone.jpg", "copied before it went")
        val journal = journal(JobKind.MOVE, listOf(folder))
        journal.started(folder.path, whole.path)
        journal.verified(folder.path, whole.path)
        val job = settled(journal)
        assertEquals(1, job.finished)
        assertEquals(listOf(folder.path), job.originalsLeft)
        assertEquals(0, job.partialsRemoved)
        // The checked copy stays, and nothing more of the original is removed by a guess.
        assertEquals("copied before it went", whole.resolve("gone.jpg").readText())
        assertEquals("still here", folder.resolve("left.jpg").readText())
        assertTrue(job.moved.isEmpty())
    }

    @Test
    fun `a move that finished removing the original just before the kill counts as done`() {
        val from = temp.newFolder("from")
        val to = temp.newFolder("to")
        val source = from.resolve("a.txt")
        val dest = to.withFile("a.txt")
        val journal = journal(JobKind.MOVE, listOf(source))
        journal.started(source.path, dest.path)
        journal.verified(source.path, dest.path)
        val job = settled(journal)
        assertEquals(1, job.finished)
        assertTrue(job.originalsLeft.isEmpty())
        assertEquals(listOf(source.path to dest.path), job.moved)
    }

    @Test
    fun `a delete counts what's really gone`() {
        val from = temp.newFolder("from")
        val here = from.withFile("still.txt")
        val gone = from.resolve("gone.txt")
        val job = settled(journal(JobKind.TRASH, listOf(here, gone)))
        assertEquals(2, job.total)
        assertEquals(1, job.finished)
        assertEquals(listOf(gone.path), job.gone)
        assertTrue(here.exists())
    }

    @Test
    fun `an archive killed while being made leaves nothing`() {
        val folder = temp.newFolder("out")
        val part = SafeFiles.partFileIn(folder).let { File(it.path + ".zip") }.also { it.writeText("half") }
        val journal = journal(JobKind.COMPRESS, label = "a.zip", folder = folder.path)
        journal.building(part.path, folder.resolve("a.zip").path)
        val job = settled(journal)
        assertFalse(part.exists())
        assertEquals(1, job.partialsRemoved)
        assertNull(job.archiveMade)
    }

    @Test
    fun `an archive finished just before the kill is said to be whole`() {
        val folder = temp.newFolder("out")
        val made = folder.withFile("a.zip")
        val journal = journal(JobKind.COMPRESS, label = "a.zip", folder = folder.path)
        journal.building(folder.resolve(".kobblo-part-gone.zip").path, made.path)
        val job = settled(journal)
        assertEquals(made.path, job.archiveMade)
        assertTrue(made.exists())
    }

    @Test
    fun `an extraction into a new folder killed partway leaves nothing behind`() {
        val folder = temp.newFolder("parent")
        val archive = folder.withFile("a.zip")
        val dest = folder.resolve("a").also { it.mkdirs() }
        dest.withFile("half.jpg")
        val journal = journal(JobKind.EXTRACT, label = "a.zip", folder = dest.path)
        journal.unpacking(archive.path, dest.path, startedEmpty = true, staging = null)
        val job = settled(journal)
        assertFalse(dest.exists())
        assertEquals(1, job.partialsRemoved)
        assertTrue(archive.exists())
    }

    @Test
    fun `an extraction into a folder with things in it only removes its own`() {
        val folder = temp.newFolder("parent")
        val archive = folder.withFile("a.zip")
        val dest = folder.resolve("dest").also { it.mkdirs() }
        val mine = dest.withFile("mine.txt", "was here first")
        val staging = SafeFiles.partFileIn(dest).also { it.mkdirs() }
        staging.withFile("half.jpg")
        val joined = dest.withFile("arrived.jpg")
        val journal = journal(JobKind.EXTRACT, label = "a.zip", folder = dest.path)
        journal.unpacking(archive.path, dest.path, startedEmpty = false, staging = staging.path)
        journal.joined(joined.path)
        settled(journal)
        assertEquals("was here first", mine.readText())
        assertFalse(staging.exists())
        assertFalse("half a result must not look like a whole one", joined.exists())
    }

    @Test
    fun `an extraction whose archive has gone keeps what it made, since nothing else has it now`() {
        val folder = temp.newFolder("parent")
        val dest = folder.resolve("a").also { it.mkdirs() }
        dest.withFile("half.jpg")
        val journal = journal(JobKind.EXTRACT, label = "a.zip", folder = dest.path)
        journal.unpacking(folder.resolve("gone.zip").path, dest.path, startedEmpty = true, staging = null)
        val job = settled(journal)
        assertTrue(dest.exists())
        assertEquals(listOf(dest.path), job.partialsKept)
    }
}

class TransferEventsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class Recorder : TransferEvents {
        val log = ArrayList<String>()
        override fun started(source: File, dest: File) { log += "started ${source.name}" }
        override fun verified(source: File, dest: File) { log += "verified ${source.name}" }
        override fun finished(source: File, dest: File) { log += "finished ${source.name}" }
        override fun rolledBack(source: File, dest: File) { log += "rolledBack ${source.name}" }
    }

    private fun File.withFile(name: String, text: String = "data"): File =
        resolve(name).also { it.parentFile?.mkdirs(); it.writeText(text) }

    @Test
    fun `a copy reports each item start and finish`() = kotlinx.coroutines.runBlocking<Unit> {
        val from = temp.newFolder("from")
        val to = temp.newFolder("to")
        val events = Recorder()
        LocalTransfer(events = events).copyInto(listOf(from.withFile("a.txt"), from.withFile("b.txt")), to)
        assertEquals(listOf("started a.txt", "finished a.txt", "started b.txt", "finished b.txt"), events.log)
    }

    @Test
    fun `a rename reports no verified step, because there's no copy to check`() = kotlinx.coroutines.runBlocking<Unit> {
        val from = temp.newFolder("from")
        val to = temp.newFolder("to")
        val events = Recorder()
        LocalTransfer(events = events).moveInto(listOf(from.withFile("a.txt")), to)
        assertEquals(listOf("started a.txt", "finished a.txt"), events.log)
    }

    @Test
    fun `a move across volumes reports the verified copy before the original goes`() = kotlinx.coroutines.runBlocking<Unit> {
        val from = temp.newFolder("from")
        val to = temp.newFolder("to")
        val source = from.withFile("a.txt", "keep")
        val events = object : TransferEvents {
            var sourceAtVerified: Boolean? = null
            var destAtVerified: Boolean? = null
            override fun verified(source: File, dest: File) {
                sourceAtVerified = source.exists()
                destAtVerified = dest.exists()
            }
        }
        LocalTransfer(events = events, rename = { _, _ -> false }).moveInto(listOf(source), to)
        assertEquals(true, events.sourceAtVerified)
        assertEquals(true, events.destAtVerified)
        assertFalse(source.exists())
        assertEquals("keep", to.resolve("a.txt").readText())
    }

    @Test
    fun `a failed copy is reported as rolled back`() = kotlinx.coroutines.runBlocking<Unit> {
        val from = temp.newFolder("from")
        val to = temp.newFolder("to")
        val events = Recorder()
        val report = LocalTransfer(events = events, openOutput = { throw java.io.IOException("ENOSPC") })
            .copyInto(listOf(from.withFile("a.txt")), to)
        assertEquals(listOf("started a.txt", "rolledBack a.txt"), events.log)
        assertEquals(1, report.failed.size)
    }

    @Test
    fun `a failed move leaves the source and is reported as rolled back`() = kotlinx.coroutines.runBlocking<Unit> {
        val from = temp.newFolder("from")
        val to = temp.newFolder("to")
        val source = from.withFile("a.txt", "keep")
        val events = Recorder()
        LocalTransfer(events = events, rename = { _, _ -> false }, openOutput = { throw java.io.IOException("ENOSPC") })
            .moveInto(listOf(source), to)
        assertEquals(listOf("started a.txt", "rolledBack a.txt"), events.log)
        assertEquals("keep", source.readText())
    }
}
