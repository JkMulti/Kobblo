package com.kobblo.gallery.file.explorer.tools

import com.kobblo.gallery.file.explorer.tools.apps.AppQuery
import com.kobblo.gallery.file.explorer.tools.apps.AppSort
import com.kobblo.gallery.file.explorer.tools.apps.InstalledApp
import com.kobblo.gallery.file.explorer.tools.archive.ArchiveFormats
import com.kobblo.gallery.file.explorer.tools.archive.CompressedArchives
import com.kobblo.gallery.file.explorer.tools.archive.CompressedFormat
import com.kobblo.gallery.file.explorer.tools.picker.DocumentIds
import com.kobblo.gallery.file.explorer.tools.picker.MimeFilter
import com.kobblo.gallery.file.explorer.tools.rename.BatchRename
import com.kobblo.gallery.file.explorer.tools.rename.CaseChange
import com.kobblo.gallery.file.explorer.tools.rename.RenamePreview
import com.kobblo.gallery.file.explorer.tools.rename.RenameProblem
import com.kobblo.gallery.file.explorer.tools.rename.RenameRule
import com.kobblo.gallery.file.explorer.tools.rename.RenameSource
import com.kobblo.gallery.file.explorer.tools.storage.DirectorySize
import com.kobblo.gallery.file.explorer.tools.storage.DirectoryWalker
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

class BatchRenameTest {
    private val parent = "/storage/emulated/0/Pics"
    private fun source(name: String, modified: Long = 0L) = RenameSource(name, parent, modified)

    private fun name(file: String, rule: RenameRule, index: Int = 0) =
        BatchRename.newName(source(file, modified = 1_700_000_000_000L), index, rule, Locale.US)

    @Test fun `the default changes nothing`() {
        assertEquals("photo.jpg", name("photo.jpg", RenameRule()))
    }

    @Test fun `a counter counts from its start, padded if asked`() {
        assertEquals("trip 1.jpg", name("x.jpg", RenameRule(pattern = "trip {n}")))
        assertEquals("trip 3.jpg", name("x.jpg", RenameRule(pattern = "trip {n}"), index = 2))
        assertEquals("trip 012.jpg", name("x.jpg", RenameRule(pattern = "trip {n:3}", startNumber = 10), index = 2))
    }

    @Test fun `the old name and extension can be reused`() {
        assertEquals("a_copy.txt", name("a.txt", RenameRule(pattern = "{name}_copy")))
        assertEquals("a (txt).txt", name("a.txt", RenameRule(pattern = "{name} ({ext})")))
    }

    @Test fun `a file's folder can be used`() {
        assertEquals("Pics 1.txt", name("a.txt", RenameRule(pattern = "{parent} {n}")))
    }

    @Test fun `dates use the file's modified time in the chosen format`() {
        val modified = java.text.SimpleDateFormat("yyyy", Locale.US).format(java.util.Date(1_700_000_000_000L))
        assertEquals("$modified.jpg", name("x.jpg", RenameRule(pattern = "{date:yyyy}")))
        val default = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(1_700_000_000_000L))
        assertEquals("$default x.jpg", name("x.jpg", RenameRule(pattern = "{date} {name}")))
    }

    @Test fun `a bad date format falls back rather than failing`() {
        val default = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(1_700_000_000_000L))
        assertEquals("$default.jpg", name("x.jpg", RenameRule(pattern = "{date:not a pattern}")))
    }

    @Test fun `unknown braces stay as they are`() {
        assertEquals("{what}.txt", name("a.txt", RenameRule(pattern = "{what}")))
    }

    @Test fun `find and replace works plainly and with regex`() {
        assertEquals("IMG-001.jpg", name("IMG_001.jpg", RenameRule(find = "_", replace = "-")))
        assertEquals("x.y.jpg", name("x_y.jpg", RenameRule(find = "_", replace = ".")))
        assertEquals("001-IMG.jpg", name("IMG_001.jpg", RenameRule(find = "(\\w+)_(\\d+)", replace = "$2-$1", findIsRegex = true)))
        // A plain search takes a regex character literally, and a half-typed pattern does nothing.
        assertEquals("a.b.jpg", name("a.b.jpg", RenameRule(find = "(", replace = "x", findIsRegex = true)))
        assertEquals("axb.jpg", name("a.b.jpg", RenameRule(find = ".", replace = "x")))
    }

    @Test fun `case changes apply to the name and leave the extension`() {
        assertEquals("hello world.JPG", name("Hello World.JPG", RenameRule(caseChange = CaseChange.LOWER)))
        assertEquals("HELLO WORLD.jpg", name("Hello World.jpg", RenameRule(caseChange = CaseChange.UPPER)))
        assertEquals("Hello World.jpg", name("hello WORLD.jpg", RenameRule(caseChange = CaseChange.TITLE)))
    }

    @Test fun `files with no extension and dotfiles stay whole`() {
        assertEquals("README-1", name("README", RenameRule(pattern = "{name}-{n}")))
        assertEquals(".bashrc-1", name(".bashrc", RenameRule(pattern = "{name}-{n}")))
    }

    @Test fun `a folder's dots aren't an extension`() {
        val folder = RenameSource("my.folder", parent, 0, isDirectory = true)
        assertEquals("my.folder 1", BatchRename.newName(folder, 0, RenameRule(pattern = "{name} {n}"), Locale.US))
    }

    private fun preview(names: List<String>, rule: RenameRule, existing: Set<String> = names.toSet(), caseInsensitive: Boolean = true) =
        BatchRename.preview(names.map { source(it) }, rule, { existing }, caseInsensitive, Locale.US)

    @Test fun `spots two files getting one name`() {
        val result = preview(listOf("a.jpg", "b.jpg"), RenameRule(pattern = "same"))
        assertTrue(result.all { it.problem == RenameProblem.DUPLICATE_IN_BATCH })
    }

    @Test fun `spots a name already taken by a file outside the batch`() {
        val result = preview(listOf("a.jpg"), RenameRule(pattern = "b"), existing = setOf("a.jpg", "b.jpg"))
        assertEquals(RenameProblem.EXISTS, result.single().problem)
    }

    @Test fun `a name being vacated in the same batch isn't a conflict`() {
        val result = preview(listOf("1.jpg", "2.jpg"), RenameRule(pattern = "{n}", startNumber = 2))
        assertTrue(result.all { it.problem == null })
        assertEquals(listOf("2.jpg", "3.jpg"), result.map { it.newName })
    }

    @Test fun `case-only conflicts depend on the file system`() {
        val existing = setOf("a.jpg", "B.jpg")
        assertEquals(RenameProblem.EXISTS, preview(listOf("a.jpg"), RenameRule(pattern = "b"), existing, caseInsensitive = true).single().problem)
        assertNull(preview(listOf("a.jpg"), RenameRule(pattern = "b"), existing, caseInsensitive = false).single().problem)
    }

    @Test fun `changing only a file's case is allowed`() {
        val result = preview(listOf("Photo.jpg"), RenameRule(caseChange = CaseChange.LOWER))
        assertNull(result.single().problem)
        assertTrue(result.single().changes)
    }

    @Test fun `rejects names the file system can't hold`() {
        assertEquals(RenameProblem.EMPTY_NAME, preview(listOf("a"), RenameRule(pattern = "")).single().problem)
        assertEquals(RenameProblem.EMPTY_NAME, preview(listOf("a"), RenameRule(pattern = ".")).single().problem)
        assertEquals(RenameProblem.INVALID_CHARACTER, preview(listOf("a"), RenameRule(pattern = "x/y")).single().problem)
        assertEquals(RenameProblem.INVALID_CHARACTER, preview(listOf("a"), RenameRule(pattern = "x:y")).single().problem)
        assertEquals(RenameProblem.TOO_LONG, preview(listOf("a"), RenameRule(pattern = "x".repeat(300))).single().problem)
    }

    /** Runs moves against a set of names the way a file system would, refusing to overwrite. */
    private fun run(moves: List<BatchRename.Move>, start: Set<String>): Set<String> {
        val names = start.toMutableSet()
        moves.forEach { move ->
            assertTrue("missing ${move.fromName}", names.remove(move.fromName))
            assertTrue("would overwrite ${move.toName}", names.add(move.toName))
        }
        return names
    }

    @Test fun `a swap goes through temporary names`() {
        val previews = listOf(
            RenamePreview(source("a"), "b", null),
            RenamePreview(source("b"), "a", null)
        )
        assertEquals(setOf("a", "b"), run(BatchRename.plan(previews), setOf("a", "b")))
        assertTrue(BatchRename.plan(previews).size > 2)
    }

    @Test fun `shifted numbering can't collide with itself`() {
        val start = setOf("1.jpg", "2.jpg", "3.jpg")
        val previews = preview(start.toList(), RenameRule(pattern = "{n}", startNumber = 2))
        assertEquals(setOf("2.jpg", "3.jpg", "4.jpg"), run(BatchRename.plan(previews), start))
    }

    @Test fun `a case-only rename goes through a temporary name`() {
        val previews = listOf(RenamePreview(source("Photo.jpg"), "photo.jpg", null))
        assertEquals(2, BatchRename.plan(previews).size)
    }

    @Test fun `files with nothing to do are left alone`() {
        val previews = listOf(RenamePreview(source("a"), "a", null))
        assertTrue(BatchRename.plan(previews).isEmpty())
    }

    @Test fun `files with problems are left out of the plan`() {
        val previews = listOf(RenamePreview(source("a"), "bad/name", RenameProblem.INVALID_CHARACTER))
        assertTrue(BatchRename.plan(previews).isEmpty())
    }

    @Test fun `undo puts everything back`() {
        val start = setOf("a.jpg", "b.jpg")
        val previews = preview(start.toList(), RenameRule(pattern = "{name}_{n}"))
        val renamed = run(BatchRename.plan(previews), start)
        assertEquals(setOf("a_1.jpg", "b_2.jpg"), renamed)
        assertEquals(start, run(BatchRename.undoPlan(previews), renamed))
    }

    @Test fun `undo of a swap also works`() {
        val previews = listOf(RenamePreview(source("a"), "b", null), RenamePreview(source("b"), "a", null))
        assertEquals(setOf("a", "b"), run(BatchRename.undoPlan(previews), setOf("a", "b")))
    }
}

class AppQueryTest {
    private fun app(label: String, pkg: String = "com.$label", system: Boolean = false, size: Long = 0, updated: Long = 0, split: Boolean = false, version: String = "1.0") =
        InstalledApp(pkg, label, version, 1, system, "/data/app/base.apk", if (split) listOf("/data/app/split.apk") else emptyList(), size, 0, updated, 34)

    private val apps = listOf(
        app("banana", size = 10, updated = 300),
        app("Apple", size = 30, updated = 100),
        app("cherry", size = 20, updated = 200),
        app("Settings", pkg = "com.android.settings", system = true)
    )

    @Test fun `splits user and system apps`() {
        assertEquals(3, AppQuery.apply(apps, system = false, "", AppSort.NAME).size)
        assertEquals(listOf("Settings"), AppQuery.apply(apps, system = true, "", AppSort.NAME).map { it.label })
    }

    @Test fun `sorts by name ignoring case`() {
        assertEquals(listOf("Apple", "banana", "cherry"), AppQuery.apply(apps, false, "", AppSort.NAME, Locale.US).map { it.label })
    }

    @Test fun `sorts by size and date, biggest and newest first`() {
        assertEquals(listOf("Apple", "cherry", "banana"), AppQuery.apply(apps, false, "", AppSort.SIZE, Locale.US).map { it.label })
        assertEquals(listOf("banana", "cherry", "Apple"), AppQuery.apply(apps, false, "", AppSort.DATE, Locale.US).map { it.label })
    }

    @Test fun `search matches a label or a package`() {
        assertEquals(listOf("Apple"), AppQuery.apply(apps, false, "APP", AppSort.NAME).map { it.label })
        assertEquals(listOf("Settings"), AppQuery.apply(apps, true, "android.sett", AppSort.NAME).map { it.label })
        assertTrue(AppQuery.apply(apps, false, "zzz", AppSort.NAME).isEmpty())
    }

    @Test fun `backup names are safe and say the version`() {
        assertEquals("My App_1.2.apk", AppQuery.backupFileName(app("My App", version = "1.2")))
        assertEquals("A_B_ C_1_0.apk", AppQuery.backupFileName(app("A/B: C", version = "1/0")))
        assertEquals("Split_1.0.apks", AppQuery.backupFileName(app("Split", split = true)))
        assertEquals("NoVersion.apk", AppQuery.backupFileName(app("NoVersion", version = "")))
        assertEquals("com.pkg_1.0.apk", AppQuery.backupFileName(app("...", pkg = "com.pkg")))
    }
}

class MimeFilterTest {
    @Test fun `no type or a catch-all accepts everything`() {
        assertTrue(MimeFilter.from(null, null, null).acceptsAll)
        assertTrue(MimeFilter.from("*/*", null, null).acceptsAll)
        assertTrue(MimeFilter.from("*/*", null, null).accepts("anything/at-all"))
    }

    @Test fun `wildcards match a whole family`() {
        val filter = MimeFilter.from("image/*", null, null)
        assertTrue(filter.accepts("image/png"))
        assertTrue(filter.accepts("IMAGE/JPEG"))
        assertFalse(filter.accepts("video/mp4"))
        assertFalse(filter.accepts("imagery/png"))
        assertFalse(filter.accepts(null))
    }

    @Test fun `exact types match only themselves`() {
        val filter = MimeFilter.from("application/pdf", null, null)
        assertTrue(filter.accepts("application/pdf"))
        assertFalse(filter.accepts("application/json"))
    }

    @Test fun `the extra list wins over a broad type`() {
        val filter = MimeFilter.from("*/*", listOf("image/png", "application/pdf"), null)
        assertTrue(filter.accepts("application/pdf"))
        assertFalse(filter.accepts("image/jpeg"))
        assertFalse(filter.acceptsAll)
    }

    @Test fun `a pick request names its media by collection`() {
        assertTrue(MimeFilter.from(null, null, "content://media/external/images/media").accepts("image/png"))
        assertFalse(MimeFilter.from(null, null, "content://media/external/images/media").accepts("video/mp4"))
        assertTrue(MimeFilter.from(null, null, "content://media/external/audio/media").accepts("audio/mpeg"))
        assertTrue(MimeFilter.from("vnd.android.cursor.dir/video", null, null).accepts("video/mp4"))
    }

    @Test fun `a grid suits photos and videos only`() {
        assertTrue(MimeFilter.from("image/*", null, null).isVisualOnly)
        assertTrue(MimeFilter.from(null, listOf("image/png", "video/mp4"), null).isVisualOnly)
        assertFalse(MimeFilter.from("*/*", null, null).isVisualOnly)
        assertFalse(MimeFilter.from("text/plain", null, null).isVisualOnly)
    }
}

class DocumentIdsTest {
    private val volumes = mapOf("primary" to "/storage/emulated/0", "ABCD-1234" to "/storage/ABCD-1234")

    @Test fun `ids map to files on their volume`() {
        assertEquals(File("/storage/emulated/0"), DocumentIds.resolve("primary:", volumes))
        assertEquals(File("/storage/emulated/0/Download/a.txt"), DocumentIds.resolve("primary:Download/a.txt", volumes))
        assertEquals(File("/storage/ABCD-1234/x"), DocumentIds.resolve("ABCD-1234:x", volumes))
    }

    @Test fun `an unmounted volume resolves to nothing`() {
        assertNull(DocumentIds.resolve("gone:x", volumes))
    }

    @Test fun `ids can't climb out of a volume`() {
        assertNull(DocumentIds.resolve("primary:../../data/secret", volumes))
        assertNull(DocumentIds.resolve("primary:a/../../b", volumes))
        assertNull(DocumentIds.resolve("primary:./a", volumes))
    }

    @Test fun `the trash stays private`() {
        assertNull(DocumentIds.resolve("primary:.kobblo_trash", volumes))
        assertNull(DocumentIds.resolve("primary:.kobblo_trash/abc", volumes))
        assertFalse(DocumentIds.isChild("primary:", "primary:.kobblo_trash/abc"))
    }

    @Test fun `children and parents build on each other`() {
        assertEquals("primary:Download", DocumentIds.child("primary:", "Download"))
        assertEquals("primary:Download/a", DocumentIds.child("primary:Download", "a"))
        assertEquals("primary:Download", DocumentIds.parent("primary:Download/a"))
        assertEquals("primary:", DocumentIds.parent("primary:Download"))
        assertNull(DocumentIds.parent("primary:"))
    }

    @Test fun `child checks don't fall for shared prefixes or other volumes`() {
        assertTrue(DocumentIds.isChild("primary:Download", "primary:Download/a"))
        assertTrue(DocumentIds.isChild("primary:", "primary:Download"))
        assertFalse(DocumentIds.isChild("primary:Down", "primary:Download/a"))
        assertFalse(DocumentIds.isChild("primary:", "ABCD-1234:Download"))
        assertFalse(DocumentIds.isChild("primary:", "primary:"))
        assertFalse(DocumentIds.isChild("primary:a", "primary:a/../../b"))
    }
}

class DirectoryWalkerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `adds up everything below a folder`() {
        folder.newFile("a").writeBytes(ByteArray(10))
        val sub = folder.newFolder("sub")
        File(sub, "b").writeBytes(ByteArray(20))
        val deep = File(sub, "deep").apply { mkdir() }
        File(deep, "c").writeBytes(ByteArray(30))
        assertEquals(DirectorySize(60, 3, 2), DirectoryWalker.measure(folder.root))
    }

    @Test fun `reports each folder's own total, deepest first`() {
        val sub = folder.newFolder("sub")
        File(sub, "b").writeBytes(ByteArray(20))
        folder.newFile("a").writeBytes(ByteArray(10))
        val seen = LinkedHashMap<String, Long>()
        DirectoryWalker.measure(folder.root, onDirectory = { dir, size -> seen[dir.name] = size.bytes })
        assertEquals(listOf("sub", folder.root.name), seen.keys.toList())
        assertEquals(20L, seen["sub"])
        assertEquals(30L, seen[folder.root.name])
    }

    @Test fun `an empty or missing folder is zero`() {
        assertEquals(DirectorySize.Zero, DirectoryWalker.measure(folder.newFolder("empty")))
        assertEquals(DirectorySize.Zero, DirectoryWalker.measure(File(folder.root, "missing")))
    }

    @Test fun `a link pointing back up doesn't loop or count twice`() {
        val sub = folder.newFolder("sub")
        File(sub, "b").writeBytes(ByteArray(5))
        java.nio.file.Files.createSymbolicLink(File(sub, "loop").toPath(), folder.root.toPath())
        assertEquals(5L, DirectoryWalker.measure(folder.root).bytes)
    }

    @Test(expected = IllegalStateException::class)
    fun `cancelling stops the walk`() {
        folder.newFolder("sub")
        var calls = 0
        DirectoryWalker.measure(folder.root, checkCancelled = {
            calls++
            if (calls > 1) throw IllegalStateException()
        })
    }
}

class ArchiveFormatsTest {
    @Test fun `knows every name it can open`() {
        listOf("a.zip", "a.RAR", "a.tar", "a.tar.gz", "a.tgz", "a.tar.bz2", "a.tbz2", "a.tar.xz", "a.txz", "a.7z", "a.gz", "a.bz2", "a.xz")
            .forEach { assertTrue(it, ArchiveFormats.isArchiveName(it)) }
        assertFalse(ArchiveFormats.isArchiveName("a.txt"))
        assertFalse(ArchiveFormats.isArchiveName("tar"))
    }

    @Test fun `a compressed tar is told apart from a lone compressed file`() {
        assertEquals(CompressedFormat.TAR_GZ, ArchiveFormats.fromName("a.tar.gz"))
        assertEquals(CompressedFormat.TAR_GZ, ArchiveFormats.fromName("a.tgz"))
        assertEquals(CompressedFormat.GZ, ArchiveFormats.fromName("a.gz"))
        assertEquals(CompressedFormat.XZ, ArchiveFormats.fromName("a.log.xz"))
    }

    @Test fun `the folder name drops the whole extension`() {
        assertEquals("backup", ArchiveFormats.stem("backup.tar.gz"))
        assertEquals("backup", ArchiveFormats.stem("backup.tgz"))
        assertEquals("notes.txt", ArchiveFormats.stem("notes.txt.gz"))
        assertEquals("photos", ArchiveFormats.stem("photos.zip"))
    }

    @Test fun `first bytes beat a wrong name`() {
        val sevenZ = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 4)
        assertEquals(CompressedFormat.SEVEN_Z, ArchiveFormats.fromMagic(sevenZ, "misnamed.dat"))
        val gzip = byteArrayOf(0x1F, 0x8B.toByte(), 8)
        assertEquals(CompressedFormat.GZ, ArchiveFormats.fromMagic(gzip, "misnamed.dat"))
        assertEquals(CompressedFormat.TAR_GZ, ArchiveFormats.fromMagic(gzip, "a.tgz"))
        val tar = ByteArray(300).also { "ustar".toByteArray().copyInto(it, 257) }
        assertEquals(CompressedFormat.TAR, ArchiveFormats.fromMagic(tar, "a.dat"))
        assertNull(ArchiveFormats.fromMagic("plain text".toByteArray(), "a.txt"))
    }
}

class CompressedArchivesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun writeTar(target: File, wrap: (OutputStream) -> OutputStream, vararg entries: Pair<String, String>) {
        TarArchiveOutputStream(wrap(target.outputStream())).use { tar ->
            entries.forEach { (name, body) ->
                val bytes = body.toByteArray()
                val entry = TarArchiveEntry(name)
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }

    private fun extractAndCheck(archive: File, format: CompressedFormat) {
        val out = folder.newFolder()
        CompressedArchives.extract(archive, format, out)
        assertEquals("hello", File(out, "a.txt").readText())
        assertEquals("deep", File(out, "dir/b.txt").readText())
    }

    private val sample = arrayOf("a.txt" to "hello", "dir/b.txt" to "deep")

    @Test fun `a plain tar lists and extracts`() {
        val archive = folder.newFile("t.tar")
        writeTar(archive, { it }, *sample)
        assertEquals(listOf("a.txt", "dir/b.txt"), CompressedArchives.list(archive, CompressedFormat.TAR).map { it.name })
        assertEquals(5L, CompressedArchives.list(archive, CompressedFormat.TAR).first().sizeBytes)
        extractAndCheck(archive, CompressedFormat.TAR)
    }

    @Test fun `gzip, bzip2 and xz tars extract`() {
        val gz = folder.newFile("t.tar.gz")
        writeTar(gz, { GzipCompressorOutputStream(it) }, *sample)
        extractAndCheck(gz, CompressedFormat.TAR_GZ)

        val bz = folder.newFile("t.tar.bz2")
        writeTar(bz, { BZip2CompressorOutputStream(it) }, *sample)
        extractAndCheck(bz, CompressedFormat.TAR_BZ2)

        val xz = folder.newFile("t.tar.xz")
        writeTar(xz, { XZCompressorOutputStream(it) }, *sample)
        extractAndCheck(xz, CompressedFormat.TAR_XZ)
    }

    @Test fun `a lone compressed file comes out under its own name`() {
        val archive = folder.newFile("notes.txt.gz")
        GzipCompressorOutputStream(archive.outputStream()).use { it.write("words".toByteArray()) }
        assertEquals("notes.txt", CompressedArchives.list(archive, CompressedFormat.GZ).single().name)
        val out = folder.newFolder()
        CompressedArchives.extract(archive, CompressedFormat.GZ, out)
        assertEquals("words", File(out, "notes.txt").readText())
    }

    @Test fun `7z with its default compression extracts, including empty files`() {
        val archive = folder.newFile("t.7z")
        SevenZOutputFile(archive).use { sevenZ ->
            listOf("a.txt" to "hello", "dir/b.txt" to "deep", "empty.txt" to "").forEach { (name, body) ->
                val entry = sevenZ.createArchiveEntry(File(folder.root, "none"), name)
                sevenZ.putArchiveEntry(entry)
                sevenZ.write(body.toByteArray())
                sevenZ.closeArchiveEntry()
            }
        }
        assertEquals(setOf("a.txt", "dir/b.txt", "empty.txt"), CompressedArchives.list(archive, CompressedFormat.SEVEN_Z).map { it.name }.toSet())
        val out = folder.newFolder()
        CompressedArchives.extract(archive, CompressedFormat.SEVEN_Z, out)
        assertEquals("hello", File(out, "a.txt").readText())
        assertEquals("deep", File(out, "dir/b.txt").readText())
        assertEquals(0L, File(out, "empty.txt").length())
    }

    @Test fun `an entry trying to escape the folder is refused`() {
        val archive = folder.newFile("evil.tar")
        writeTar(archive, { it }, "../outside.txt" to "gotcha")
        val out = folder.newFolder("safe")
        try {
            CompressedArchives.extract(archive, CompressedFormat.TAR, out)
            fail("expected the entry to be refused")
        } catch (_: IOException) {
        }
        assertFalse(File(folder.root, "outside.txt").exists())
    }

    @Test fun `the escape check isn't fooled by a shared prefix`() {
        val root = folder.newFolder("safe")
        folder.newFolder("safe-not")
        try {
            CompressedArchives.safeTarget(root, "../safe-not/x")
            fail("expected the path to be refused")
        } catch (_: IOException) {
        }
        assertEquals(File(root, "ok/x").canonicalPath, CompressedArchives.safeTarget(root, "ok/x").path)
    }

    @Test fun `links are skipped rather than followed`() {
        val archive = folder.newFile("links.tar")
        TarArchiveOutputStream(archive.outputStream()).use { tar ->
            val link = TarArchiveEntry("link", org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK)
            link.linkName = "/etc/passwd"
            tar.putArchiveEntry(link)
            tar.closeArchiveEntry()
            val entry = TarArchiveEntry("real.txt")
            entry.size = 2
            tar.putArchiveEntry(entry)
            tar.write("ok".toByteArray())
            tar.closeArchiveEntry()
        }
        assertEquals(listOf("real.txt"), CompressedArchives.list(archive, CompressedFormat.TAR).map { it.name })
        val out = folder.newFolder()
        CompressedArchives.extract(archive, CompressedFormat.TAR, out)
        assertFalse(File(out, "link").exists())
        assertTrue(File(out, "real.txt").exists())
    }

    @Test fun `a progress callback can stop the extraction`() {
        val archive = folder.newFile("t.tar")
        writeTar(archive, { it }, *sample)
        try {
            CompressedArchives.extract(archive, CompressedFormat.TAR, folder.newFolder()) { _, _ -> throw IllegalStateException() }
            fail("expected the callback's exception")
        } catch (_: IllegalStateException) {
        }
    }

    @Test(expected = Exception::class)
    fun `a damaged archive fails rather than hanging`() {
        val archive = folder.newFile("broken.tar.gz").apply { writeBytes(byteArrayOf(0x1F, 0x8B.toByte(), 8, 0, 1, 2, 3)) }
        CompressedArchives.list(archive, CompressedFormat.TAR_GZ)
    }
}
