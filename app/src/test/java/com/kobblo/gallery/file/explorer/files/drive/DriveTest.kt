package com.kobblo.gallery.file.explorer.files.drive

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import com.kobblo.gallery.file.explorer.fileops.FailReason
import com.kobblo.gallery.file.explorer.fileops.FileOpException
import com.kobblo.gallery.file.explorer.files.DriveLocation
import com.kobblo.gallery.file.explorer.files.ElevatedShell
import com.kobblo.gallery.file.explorer.files.TestShell
import com.kobblo.gallery.file.explorer.files.TransferTarget
import com.kobblo.gallery.file.explorer.files.drive.FakeDrive.Answer
import com.kobblo.gallery.file.explorer.files.marks
import com.kobblo.gallery.file.explorer.files.with
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Date

class DriveTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val top = DriveLocation(account = null, folderId = null)
    private val folder = DriveLocation(account = null, folderId = "F1")

    // Formats

    @Test
    fun `Drive's own word for a file beats its name`() {
        assertEquals("folder", DriveFormats.typeOf("application/vnd.google-apps.folder", "holiday.jpg"))
        assertEquals("image", DriveFormats.typeOf("image/heic", "no extension"))
        assertEquals("video", DriveFormats.typeOf("video/mp4", "clip.bin"))
        assertEquals("audio", DriveFormats.typeOf("audio/mpeg", "song"))
        assertEquals("doc", DriveFormats.typeOf("application/pdf", "a"))
        assertEquals("doc", DriveFormats.typeOf("application/vnd.google-apps.document", "a"))
        assertEquals("doc", DriveFormats.typeOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "a"))
        assertEquals("archive", DriveFormats.typeOf("application/octet-stream", "a.zip"))
        assertEquals("file", DriveFormats.typeOf(null, "a"))
    }

    @Test
    fun `a Google document is saved as a PDF, and a drawing as a picture`() {
        assertEquals("application/pdf", DriveFormats.exportOf("application/vnd.google-apps.document"))
        assertEquals("application/pdf", DriveFormats.exportOf("application/vnd.google-apps.spreadsheet"))
        assertEquals("image/png", DriveFormats.exportOf("application/vnd.google-apps.drawing"))
        assertNull(DriveFormats.exportOf("image/jpeg"))
        assertNull(DriveFormats.exportOf(null))
        assertEquals("plan.pdf", DriveFormats.exportName("plan", "application/pdf"))
        assertEquals("plan.pdf", DriveFormats.exportName("plan.gdoc", "application/pdf"))
        assertEquals("plan.PDF", DriveFormats.exportName("plan.PDF", "application/pdf"))
        assertEquals("plan.pdf", DriveFormats.exportName("plan.png", "application/pdf"))
        assertEquals("logo.png", DriveFormats.exportName("logo", "image/png"))
        assertEquals("logo.Png", DriveFormats.exportName("logo.Png", "image/png"))
        assertEquals("logo.pdf", DriveFormats.exportName("logo.pdf", "image/png"))
    }

    // Folders

    private val someFiles = """{"files": [
        {"id": "1", "name": "Trips", "mimeType": "application/vnd.google-apps.folder", "modifiedTime": "2024-03-01T10:00:00.000Z"},
        {"id": "2", "name": "photo.jpg", "mimeType": "image/jpeg", "size": "2048", "modifiedTime": "2024-03-01T10:00:00.000Z",
         "owners": [{"displayName": "Ada"}], "thumbnailLink": "https://thumb"},
        {"id": "3", "mimeType": "application/vnd.google-apps.document"}
    ]}"""

    private fun folders() = DriveFolders { "Unknown" }

    private fun names(items: List<FileItem>) = items.map { it.name }

    @Test
    fun `a folder's rows`() {
        val fake = FakeDrive { Answer(someFiles) }
        val (trips, photo, document) = folders().list(fake.drive, folder, marks(favorites = setOf("drive_id:2"), locks = setOf("drive_id:1")))
        assertEquals("trashed = false and 'F1' in parents", fake.requests.single().argument("q"))
        assertEquals("true", fake.requests.single().argument("includeItemsFromAllDrives"))
        assertEquals(listOf(1000, 1001, 1002), listOf(trips.id, photo.id, document.id))

        assertEquals("drive_id:1", trips.path)
        assertEquals("folder", trips.type)
        assertEquals("folder", trips.kind)
        assertEquals("--", trips.size)
        assertTrue(trips.isEmptyFolder)
        assertTrue(trips.isLocked)
        assertEquals("Me", trips.owner)

        assertEquals("2.0 kB", photo.size)
        assertEquals(2048L, photo.sizeBytes)
        assertEquals(1_709_287_200_000L, photo.lastModified)
        assertEquals(DisplayDates.date().format(Date(1_709_287_200_000L)), photo.changed)
        assertEquals("Ada", photo.owner)
        assertEquals("https://thumb", photo.thumbnailLink)
        assertEquals("media", photo.kind)
        assertTrue(photo.isPinned)
        assertFalse(photo.isEmptyFolder)

        assertEquals("Unknown", document.name)
        assertEquals("Unknown", document.size)
        assertEquals("Unknown", document.changed)
        assertEquals("doc", document.type)
        assertEquals(0L, document.lastModified)
    }

    @Test
    fun `the top is the root folder`() {
        val fake = FakeDrive { Answer(someFiles) }
        folders().list(fake.drive, top, marks())
        assertEquals("trashed = false and 'root' in parents", fake.requests.single().argument("q"))
    }

    @Test
    fun `an empty top shows everything there is`() {
        val fake = FakeDrive { if ("root" in it.argument("q")!!) Answer("""{"files": []}""") else Answer(someFiles) }
        assertEquals(3, folders().list(fake.drive, top, marks()).size)
        val nothing = FakeDrive { if ("root" in it.argument("q")!!) Answer("{}") else FakeDrive.refused }
        assertEquals(0, folders().list(nothing.drive, top, marks()).size)
    }

    @Test
    fun `a top that can't be listed shows everything there is, and fails only with that`() {
        val fake = FakeDrive { if ("root" in it.argument("q")!!) FakeDrive.refused else Answer(someFiles) }
        assertEquals(3, folders().list(fake.drive, top, marks()).size)
        val closed = FakeDrive { FakeDrive.refused }
        assertThrows(Exception::class.java) { folders().list(closed.drive, top, marks()) }
    }

    @Test
    fun `an empty folder is empty, and one that can't be listed is an error`() {
        val fake = FakeDrive { Answer("{}") }
        assertEquals(0, folders().list(fake.drive, folder, marks()).size)
        assertEquals(1, fake.requests.size)
        val closed = FakeDrive { FakeDrive.refused }
        assertThrows(Exception::class.java) { folders().list(closed.drive, folder, marks()) }
        assertEquals(1, closed.requests.size)
    }

    @Test
    fun `picked files are all the top shows`() {
        val fake = FakeDrive { Answer(someFiles) }
        val folders = folders().apply { pickedIds = setOf("2", "elsewhere") }
        assertEquals(listOf("photo.jpg"), names(folders.list(fake.drive, top, marks())))
        assertEquals("A folder shows all of its own", 3, folders.list(fake.drive, folder, marks()).size)
        folders.pickedIds = emptySet()
        assertEquals(3, folders.list(fake.drive, top, marks()).size)
    }

    // One file

    private fun files(fake: FakeDrive?) = DriveFiles({ fake?.drive }, { temp.root })

    @Test
    fun `a new folder goes where it's asked for`() {
        val fake = FakeDrive { Answer() }
        assertTrue(files(fake).createFolder(folder, "Trips"))
        assertTrue(files(fake).createFolder(top, "Trips"))
        assertTrue(files(fake).createFile(folder, "note.txt"))
        val (inFolder, atTop, file) = fake.requests.map { it.body }
        assertTrue("\"parents\":[\"F1\"]" in inFolder && "\"name\":\"Trips\"" in inFolder)
        assertTrue("application/vnd.google-apps.folder" in inFolder)
        assertFalse("parents" in atTop)
        assertTrue("\"name\":\"note.txt\"" in file)
        assertFalse("mimeType" in file)
        assertFalse(files(FakeDrive { FakeDrive.refused }).createFolder(folder, "Trips"))
        assertFalse(files(null).createFile(folder, "note.txt"))
    }

    @Test
    fun `the account a location names is the one used`() {
        val asked = ArrayList<String?>()
        val files = DriveFiles({ asked += it; null }, { temp.root })
        files.createFolder(DriveLocation.of("drive:a@b.com")!!, "x")
        files.createFolder(DriveLocation.of("drive_id:1")!!, "x")
        assertEquals(listOf("a@b.com", null), asked)
    }

    @Test
    fun `renaming says whether it worked`() {
        val fake = FakeDrive { Answer() }
        assertTrue(files(fake).rename("1", "new"))
        assertEquals(listOf("PATCH /drive/v3/files/1"), fake.asked)
        assertFalse(files(FakeDrive { FakeDrive.refused }).rename("1", "new"))
        assertFalse(files(null).rename("1", "new"))
    }

    @Test
    fun `deleting puts it in Drive's trash`() {
        val fake = FakeDrive { Answer() }
        assertNull(files(fake).delete("1"))
        assertEquals(listOf("PATCH /drive/v3/files/1"), fake.asked)
        assertTrue("\"trashed\":true" in fake.requests.single().body)
    }

    @Test
    fun `what can't go in the trash is deleted, and an error is said`() {
        val fake = FakeDrive { if (it.method == "PATCH") FakeDrive.refused else Answer(status = 204) }
        assertNull(files(fake).delete("1"))
        assertEquals(listOf("PATCH /drive/v3/files/1", "DELETE /drive/v3/files/1"), fake.asked)
        assertTrue("Not allowed" in files(FakeDrive { FakeDrive.refused }).delete("1")!!)
        assertEquals("Drive service not initialized", files(null).delete("1"))
    }

    @Test
    fun `a file is downloaded as it is`() {
        val fake = FakeDrive { if (it.wantsBytes) Answer("bytes") else Answer("""{"mimeType": "text/plain"}""") }
        val target = temp.root.resolve("a.txt")
        assertEquals(target, files(fake).download("1", target, null).getOrThrow())
        assertEquals("bytes", target.readText())
    }

    @Test
    fun `a Google document is downloaded in a format that has bytes`() {
        val fake = FakeDrive {
            if (it.path.endsWith("/export")) Answer("pdf") else Answer("""{"mimeType": "application/vnd.google-apps.document"}""")
        }
        val target = temp.root.resolve("a.pdf")
        files(fake).download("1", target, null).getOrThrow()
        assertEquals("pdf", target.readText())
        assertEquals("application/pdf", fake.requests.last().argument("mimeType"))
    }

    @Test
    fun `a folder isn't downloaded, and nothing is without Drive`() {
        val fake = FakeDrive { Answer("""{"mimeType": "application/vnd.google-apps.folder"}""") }
        val target = temp.root.resolve("a")
        assertEquals("Cannot download folder as file", files(fake).download("1", target, null).exceptionOrNull()?.message)
        assertFalse(target.exists())
        assertEquals("Drive not connected", files(null).download("1", target, null).exceptionOrNull()?.message)
        assertEquals("Drive not connected", files(null).upload(target, "drive", null).exceptionOrNull()?.message)
    }

    @Test
    fun `a file to open lands in the cache`() {
        val fake = FakeDrive { if (it.wantsBytes) Answer("bytes") else Answer("""{"mimeType": "image/jpeg"}""") }
        val cached = files(fake).downloadToCache("1", "photo.jpg", null)
        assertEquals(temp.root.resolve("cloud_cache/photo.jpg"), cached)
        assertEquals("bytes", cached?.readText())
    }

    @Test
    fun `a document to open gets the extension it's exported with`() {
        val fake = FakeDrive {
            if (it.path.endsWith("/export")) Answer("pdf") else Answer("""{"mimeType": "application/vnd.google-apps.presentation"}""")
        }
        assertEquals(temp.root.resolve("cloud_cache/talk.pdf"), files(fake).downloadToCache("1", "talk.pptx", null))
        assertEquals(temp.root.resolve("cloud_cache/talk.pdf"), files(fake).downloadToCache("1", "talk.pdf", null))
        assertEquals(listOf("talk.pdf"), temp.root.resolve("cloud_cache").list()!!.toList())
    }

    @Test
    fun `nothing to open comes of a folder, an error, or no Drive`() {
        assertNull(files(FakeDrive { Answer("""{"mimeType": "application/vnd.google-apps.folder"}""") }).downloadToCache("1", "a", null))
        assertNull(files(FakeDrive { FakeDrive.refused }).downloadToCache("1", "a", null))
        assertNull(files(null).downloadToCache("1", "a", null))
    }

    @Test
    fun `an upload goes into the folder its location names`() {
        val source = temp.root.with("a.txt", "bytes")
        fun parentFor(location: String): String {
            val fake = uploadTarget(size = 5)
            files(fake).upload(source, location, null).getOrThrow()
            assertEquals("PUT", fake.requests.last().method)
            assertEquals("bytes", fake.requests.last().body)
            return Regex("\"parents\":\\[\"(.*?)\"]").find(fake.requests.first().body)!!.groupValues[1]
        }
        assertEquals("root", parentFor("drive"))
        assertEquals("root", parentFor("drive:a@b.com"))
        assertEquals("F1", parentFor("drive_id:F1"))
        assertEquals("F1", parentFor("F1"))
    }

    @Test
    fun `how full it is`() {
        val fake = FakeDrive { Answer("""{"storageQuota": {"limit": "100", "usage": "40"}}""") }
        assertEquals(40L to 100L, files(fake).quota(null))
        assertEquals(7L to 0L, files(FakeDrive { Answer("""{"storageQuota": {"usage": "7"}}""") }).quota(null))
        assertNull(files(FakeDrive { Answer() }).quota(null))
        assertNull(files(FakeDrive { FakeDrive.refused }).quota(null))
        assertNull(files(null).quota(null))
    }

    // Copies and moves

    private fun transfer(fake: FakeDrive?, shell: ElevatedShell = TestShell(isReady = false)) =
        DriveTransfer({ fake?.drive }, shell) { temp.root.resolve("cache").also { it.mkdirs() } }

    private fun onPhone(name: String = "to") = TransferTarget.OnPhone(temp.root.resolve(name))

    private fun remote(name: String = "a.txt", bytes: String = "bytes", says: String = "\"size\": \"${bytes.length}\"") = FakeDrive {
        when {
            it.method == "DELETE" -> Answer(status = 204)
            it.wantsBytes -> Answer(bytes)
            else -> Answer("""{"name": "$name", "mimeType": "text/plain", $says}""")
        }
    }

    private fun uploadTarget(size: Int) = FakeDrive {
        if (FakeDrive.isUploadStart(it)) Answer(location = FakeDrive.UploadTo) else Answer("""{"id": "new", "size": "$size"}""")
    }

    private fun assertFails(reason: FailReason, work: () -> Unit) =
        assertEquals(reason, assertThrows(FileOpException::class.java) { work() }.reason)

    @Test
    fun `a download arrives whole, and a copy stays on Drive`() {
        val fake = remote()
        val report = transfer(fake).carry("drive_id:1", onPhone(), move = false)
        val arrived = temp.root.resolve("to/a.txt")
        assertEquals("bytes", arrived.readText())
        assertEquals(listOf(arrived.path), report.outputs)
        assertEquals(listOf("a.txt"), temp.root.resolve("to").list()!!.toList())
        assertTrue(fake.requests.none { it.method == "DELETE" })
    }

    @Test
    fun `a move takes it off Drive, after it arrived`() {
        val fake = remote()
        transfer(fake).carry("drive_id:1", onPhone(), move = true)
        assertEquals("DELETE /drive/v3/files/1", fake.asked.last())
        assertEquals("bytes", temp.root.resolve("to/a.txt").readText())
    }

    @Test
    fun `a download doesn't overwrite`() {
        temp.root.with("to/a.txt", "precious")
        val report = transfer(remote()).carry("drive_id:1", onPhone(), move = false)
        assertEquals("precious", temp.root.resolve("to/a.txt").readText())
        assertEquals("bytes", temp.root.resolve("to/a (1).txt").readText())
        assertEquals(1, report.renamed)
    }

    @Test
    fun `half a download is thrown away, and Drive keeps the file`() {
        val fake = remote(says = "\"size\": \"500\"")
        assertFails(FailReason.CHECK_FAILED) { transfer(fake).carry("drive_id:1", onPhone(), move = true) }
        assertEquals(emptyList<String>(), temp.root.resolve("to").list()!!.toList())
        assertTrue(fake.requests.none { it.method == "DELETE" })
    }

    @Test
    fun `a size that isn't known proves nothing`() {
        val fake = remote(says = "\"id\": \"1\"")
        assertFails(FailReason.CHECK_FAILED) { transfer(fake).carry("drive_id:1", onPhone(), move = true) }
        assertEquals(emptyList<String>(), temp.root.resolve("to").list()!!.toList())
    }

    @Test
    fun `an empty file needs no download`() {
        val fake = remote(bytes = "")
        transfer(fake).carry("drive_id:1", onPhone(), move = false)
        assertEquals(0L, temp.root.resolve("to/a.txt").length())
        assertTrue(fake.requests.none { it.wantsBytes })
    }

    @Test
    fun `a Google document is never moved as an empty file`() {
        val fake = FakeDrive { Answer("""{"name": "Plan", "mimeType": "application/vnd.google-apps.document"}""") }
        assertFails(FailReason.NOT_POSSIBLE) { transfer(fake).carry("drive_id:1", onPhone(), move = true) }
        assertFalse(temp.root.resolve("to").exists())
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `the shell puts it where only it can`() {
        val shell = TestShell()
        val target = onPhone("Android/data/com.app")
        target.folder.mkdirs()
        target.folder.with("a.txt", "precious")
        val report = transfer(remote(), shell).carry("drive_id:1", target, move = false)
        assertEquals("precious", target.folder.resolve("a.txt").readText())
        assertEquals("bytes", target.folder.resolve("a (1).txt").readText())
        assertEquals(1, report.renamed)
        assertEquals("Nothing is left in the cache", emptyList<String>(), temp.root.resolve("cache").list()!!.toList())
    }

    @Test
    fun `what the shell can't place stays on Drive`() {
        val shell = TestShell().apply { refuses = { "cp -r" in it } }
        val fake = remote()
        val target = onPhone("Android/data/com.app")
        assertFails(FailReason.DENIED) { transfer(fake, shell).carry("drive_id:1", target, move = true) }
        assertTrue(fake.requests.none { it.method == "DELETE" })
        assertEquals(emptyList<String>(), temp.root.resolve("cache").list()!!.toList())
    }

    private val onDrive = TransferTarget.OnDrive("F1")

    @Test
    fun `an upload leaves the original of a copy`() {
        val source = temp.root.with("a.txt", "bytes")
        val fake = uploadTarget(size = 5)
        val report = transfer(fake).carry(source.path, onDrive, move = false)
        assertTrue(source.exists())
        assertEquals(emptyList<String>(), report.movedFrom)
        assertTrue("\"parents\":[\"F1\"]" in fake.requests.first().body)
        assertEquals("bytes", fake.requests.last().body)
    }

    @Test
    fun `an upload takes the original of a move, once Drive has all of it`() {
        val source = temp.root.with("a.txt", "bytes")
        val report = transfer(uploadTarget(size = 5)).carry(source.path, onDrive, move = true)
        assertFalse(source.exists())
        assertEquals(listOf(source.path), report.movedFrom)
    }

    @Test
    fun `an upload that came up short keeps the original`() {
        val source = temp.root.with("a.txt", "bytes")
        assertFails(FailReason.CHECK_FAILED) { transfer(uploadTarget(size = 3)).carry(source.path, onDrive, move = true) }
        assertEquals("bytes", source.readText())
    }

    @Test
    fun `only a file can be uploaded`() {
        val folder: File = temp.newFolder("folder").also { it.with("a.txt") }
        assertFails(FailReason.NOT_POSSIBLE) { transfer(uploadTarget(size = 5)).carry(folder.path, onDrive, move = true) }
        assertFails(FailReason.MISSING) { transfer(uploadTarget(size = 5)).carry("/missing", onDrive, move = true) }
        assertTrue(folder.resolve("a.txt").exists())
    }

    @Test
    fun `within Drive a move changes the parent and a copy makes another`() {
        val fake = FakeDrive { Answer("""{"parents": ["old", "other"]}""") }
        transfer(fake).carry("drive_id:1", onDrive, move = true)
        assertEquals(listOf("GET /drive/v3/files/1", "PATCH /drive/v3/files/1"), fake.asked)
        assertEquals("F1", fake.requests.last().argument("addParents"))
        assertEquals("old,other", fake.requests.last().argument("removeParents"))
        val copied = FakeDrive { Answer() }
        val report = transfer(copied).carry("drive_id:1", onDrive, move = false)
        assertEquals(listOf("POST /drive/v3/files/1/copy"), copied.asked)
        assertEquals(1, report.done)
        assertEquals(emptyList<String>(), report.outputs)
    }

    @Test
    fun `nothing happens without Drive`() {
        assertFails(FailReason.NOT_POSSIBLE) { transfer(null).carry("drive_id:1", onPhone(), move = true) }
    }
}
