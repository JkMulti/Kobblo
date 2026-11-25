package com.kobblo.gallery.file.explorer.transfer

import com.kobblo.gallery.file.explorer.transfer.core.TransferDirection
import com.kobblo.gallery.file.explorer.transfer.core.TransferHistoryStore
import com.kobblo.gallery.file.explorer.transfer.core.TransferRecord
import com.kobblo.gallery.file.explorer.transfer.core.TransferRecordFile
import com.kobblo.gallery.file.explorer.transfer.core.TransferResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TransferHistoryStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun record(id: String, time: Long = 1L, result: TransferResult = TransferResult.COMPLETED, files: List<TransferRecordFile> = listOf(TransferRecordFile("a.jpg", 10, "/x/a.jpg"))) =
        TransferRecord(id, TransferDirection.SENT, "Pixel", "1.2.3.4:5", "FP", "nearby", time, result, null, files)

    private fun store(name: String = "h.json", maxRecords: Int = 500, maxFiles: Int = 2000) =
        TransferHistoryStore(File(temp.root, name), maxRecords, maxFiles)

    @Test fun startsEmpty() {
        assertTrue(store().all().isEmpty())
    }

    @Test fun survivesRestart() {
        store().add(record("1"))
        val again = store().all().single()
        assertEquals("1", again.id)
        assertEquals("Pixel", again.peerAlias)
        assertEquals(TransferDirection.SENT, again.direction)
        assertEquals(TransferResult.COMPLETED, again.result)
        assertEquals(10L, again.totalBytes)
        assertEquals("/x/a.jpg", again.files.single().path)
    }

    @Test fun newestFirst() {
        val s = store()
        s.add(record("1"))
        s.add(record("2"))
        assertEquals(listOf("2", "1"), s.all().map { it.id })
    }

    @Test fun addingTheSameIdReplacesIt() {
        val s = store()
        s.add(record("1", result = TransferResult.FAILED))
        s.add(record("1", result = TransferResult.COMPLETED))
        assertEquals(TransferResult.COMPLETED, s.all().single().result)
    }

    @Test fun capsRecords() {
        val s = store(maxRecords = 3)
        (1..5).forEach { s.add(record("$it")) }
        assertEquals(listOf("5", "4", "3"), s.all().map { it.id })
        assertEquals(3, store(maxRecords = 3).all().size)
    }

    @Test fun capsFilesButKeepsTheCount() {
        val many = (1..10).map { TransferRecordFile("f$it", 1) }
        val saved = store(maxFiles = 4).add(record("1", files = many)).single()
        assertEquals(4, saved.files.size)
        assertEquals(10, saved.fileCount)
        assertEquals(10L, saved.totalBytes)
    }

    @Test fun mergeFoldsIntoOneLine() {
        val s = store()
        s.merge(record("web", time = 1))
        s.add(record("other", time = 2))
        s.merge(record("web", time = 3, files = listOf(TransferRecordFile("b.jpg", 5))))
        val web = s.all().first { it.id == "web" }
        assertEquals(2, web.files.size)
        assertEquals(15L, web.totalBytes)
        assertEquals(3L, web.timeMs)
        assertEquals(2, s.all().size)
    }

    @Test fun mergeKeepsAFailureVisible() {
        val s = store()
        s.merge(record("web", result = TransferResult.FAILED))
        s.merge(record("web", result = TransferResult.COMPLETED))
        assertEquals(TransferResult.FAILED, s.all().single().result)
    }

    @Test fun removeAndClear() {
        val s = store()
        s.add(record("1"))
        s.add(record("2"))
        s.remove("1")
        assertEquals(listOf("2"), s.all().map { it.id })
        s.clear()
        assertTrue(s.all().isEmpty())
        assertTrue(store().all().isEmpty())
    }

    @Test fun corruptFileIsSetAsideNotFatal() {
        val file = File(temp.root, "bad.json").apply { writeText("{ not json ]]") }
        val s = TransferHistoryStore(file)
        assertTrue(s.all().isEmpty())
        assertTrue(File(temp.root, "bad.json.bad").exists())
        s.add(record("1"))
        assertEquals("1", TransferHistoryStore(file).all().single().id)
    }

    @Test fun recordsMissingRequiredFieldsAreDropped() {
        val file = File(temp.root, "old.json").apply {
            writeText("""[{"id":"ok","direction":"SENT","peerAlias":"A","timeMs":1,"result":"FAILED","files":[]},{"id":"bad"}]""")
        }
        assertEquals(listOf("ok"), TransferHistoryStore(file).all().map { it.id })
    }

    @Test fun noTemporaryFileLeftBehind() {
        store().add(record("1"))
        assertFalse(File(temp.root, "h.json.tmp").exists())
    }
}
