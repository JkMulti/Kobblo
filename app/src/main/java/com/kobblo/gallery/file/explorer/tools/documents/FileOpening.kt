package com.kobblo.gallery.file.explorer.tools.documents

import com.kobblo.gallery.file.explorer.tools.editor.TextCodec
import com.kobblo.gallery.file.explorer.tools.files.FileSniffer
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File
import java.util.Locale

/** The screen a file should open on. */
internal enum class OpenAs {
    TEXT, MARKDOWN, HTML, RTF,
    WORD_ZIP, WORD_BINARY, ODT, SLIDES_ZIP, SLIDES_BINARY, ODP,
    SHEET_ZIP, SHEET_BINARY, ODS, CSV,
    EPUB, SQLITE, FONT, CONTACTS, CALENDAR, PLAYLIST, TORRENT, EMAIL, BINARY_PLIST,
    IMAGE, VIDEO, AUDIO, ARCHIVE,
    /** Locked by a password, in a way that shows before reading anything. */
    LOCKED,
    /** Nothing here can show it, so its details and next steps are what's left. */
    INSPECT
}

/**
 * What a file really is, by its bytes first and its name second. A name is what someone typed, so a PNG
 * called `.txt` opens as a picture and a Word file saved as `.dat` opens as Word. The name still
 * chooses among text formats, where the bytes can't tell a `.csv` from a `.log`.
 */
internal object FileOpening {
    /** Enough for the marks these formats carry, and for telling text from binary. */
    const val SAMPLE_SIZE = FileSniffer.SAMPLE_SIZE

    private val CsvNames = setOf("csv", "tsv")
    private val MarkdownNames = setOf("md", "markdown", "mdown", "mkd")
    private val HtmlNames = setOf("html", "htm", "xhtml")
    private val PlaylistNames = setOf("m3u", "m3u8", "pls", "cue")
    private val OfficeNames = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub", "sqlite", "db", "sqlite3", "db3", "torrent")
    private val BundleNames = setOf("apk", "apks", "xapk", "apkm", "aab")

    fun decide(file: File): OpenAs {
        val sample = readSample(file) ?: return OpenAs.INSPECT
        val decided = decide(file.name, sample)
        return when (decided) {
            OpenAs.WORD_BINARY -> compoundKind(file)
            OpenAs.WORD_ZIP -> zipKind(file)
            else -> decided
        }
    }

    /** Everything that can be told from the first bytes. Containers then need a look inside: see [decide]. */
    fun decide(name: String, sample: ByteArray): OpenAs {
        val ext = FileKinds.extensionOf(name)
        if (sample.isEmpty()) return if (ext in OfficeNames || ext in BundleNames) OpenAs.INSPECT else OpenAs.TEXT
        if (CompoundFile.hasSignature(sample)) return OpenAs.WORD_BINARY
        if (isZip(sample)) return OpenAs.WORD_ZIP
        if (RtfReader.looksLikeRtf(sample)) return OpenAs.RTF
        if (BinaryPlistReader.looksLikeBinaryPlist(sample)) return OpenAs.BINARY_PLIST
        if (Bencode.looksLikeTorrent(sample)) return OpenAs.TORRENT

        val kind = FileSniffer.sniff(sample)
        if (kind != null && !kind.isText) {
            return when (kind.label) {
                "PNG image", "JPEG image", "GIF image", "BMP image", "TIFF image", "WebP image", "HEIF image", "AVIF image", "Photoshop document" -> OpenAs.IMAGE
                "MPEG-4 video", "Matroska or WebM video", "AVI video" -> OpenAs.VIDEO
                "MP3 audio", "FLAC audio", "Ogg media", "WAV audio", "MPEG-4 audio" -> OpenAs.AUDIO
                "SQLite database" -> OpenAs.SQLITE
                // The archive handlers go by these same marks, so the name doesn't have to agree.
                "RAR archive", "7-Zip archive", "Gzip archive", "Bzip2 archive", "XZ archive", "Tar archive" -> OpenAs.ARCHIVE
                "TrueType font", "OpenType font", "Font collection", "Web font (WOFF)" -> OpenAs.FONT
                else -> OpenAs.INSPECT
            }
        }
        if (kind == null) return promisedByName(name) ?: OpenAs.INSPECT
        return when (ext) {
            in MarkdownNames -> OpenAs.MARKDOWN
            in HtmlNames -> OpenAs.HTML
            in CsvNames -> OpenAs.CSV
            "vcf", "vcard" -> OpenAs.CONTACTS
            "ics", "ical" -> OpenAs.CALENDAR
            in PlaylistNames -> OpenAs.PLAYLIST
            "eml" -> if (EmailReader.looksLikeEmail(sample)) OpenAs.EMAIL else OpenAs.TEXT
            // Old exports often wrote a web page and just named it for Word or Excel.
            "doc", "xls" -> if (looksLikeHtml(sample)) OpenAs.HTML else OpenAs.TEXT
            else -> OpenAs.TEXT
        }
    }

    /** Which Office program a compound file belongs to. The directory says, and the name is no help. */
    fun compoundKind(file: File): OpenAs = try {
        CompoundFile.open(file).use { compound ->
            when {
                compound.has("EncryptedPackage") || compound.has("EncryptionInfo") -> OpenAs.LOCKED
                compound.has("WordDocument") -> OpenAs.WORD_BINARY
                compound.has("Workbook") || compound.has("Book") -> OpenAs.SHEET_BINARY
                compound.has("PowerPoint Document") -> OpenAs.SLIDES_BINARY
                else -> OpenAs.INSPECT
            }
        }
    } catch (_: Exception) {
        // A damaged one still says what it was meant to be through its name, and that screen explains.
        when (FileKinds.extensionOf(file.name)) {
            "doc", "dot" -> OpenAs.WORD_BINARY
            "xls", "xlt" -> OpenAs.SHEET_BINARY
            "ppt", "pps", "pot" -> OpenAs.SLIDES_BINARY
            else -> OpenAs.INSPECT
        }
    }

    /** A ZIP says what it is through its parts, so a renamed Word file is still a Word file. */
    fun zipKind(file: File): OpenAs = try {
        SafeZip(file).use { zip ->
            val mime = OpenDocumentReader.mimeType(zip).orEmpty()
            when {
                zip.has("word/document.xml") -> OpenAs.WORD_ZIP
                zip.has("xl/workbook.xml") -> OpenAs.SHEET_ZIP
                zip.has("ppt/presentation.xml") -> OpenAs.SLIDES_ZIP
                mime == "application/epub+zip" -> OpenAs.EPUB
                mime.startsWith("application/vnd.oasis.opendocument.text") -> OpenAs.ODT
                mime.startsWith("application/vnd.oasis.opendocument.spreadsheet") -> OpenAs.ODS
                mime.startsWith("application/vnd.oasis.opendocument.presentation") -> OpenAs.ODP
                zip.has("META-INF/container.xml") && FileKinds.extensionOf(file.name) == "epub" -> OpenAs.EPUB
                zip.has("content.xml") && FileKinds.extensionOf(file.name) == "odt" -> OpenAs.ODT
                zip.has("content.xml") && FileKinds.extensionOf(file.name) == "ods" -> OpenAs.ODS
                zip.has("content.xml") && FileKinds.extensionOf(file.name) == "odp" -> OpenAs.ODP
                FileKinds.extensionOf(file.name) in BundleNames -> OpenAs.INSPECT
                zip.has("AndroidManifest.xml") -> OpenAs.INSPECT
                else -> OpenAs.ARCHIVE
            }
        }
    } catch (_: Exception) {
        damagedByName(file.name)
    }

    /** A file named for a format but too damaged to look inside. Its screen says it couldn't be read. */
    private fun damagedByName(name: String): OpenAs = when (FileKinds.extensionOf(name)) {
        "docx", "docm", "dotx" -> OpenAs.WORD_ZIP
        "xlsx", "xlsm", "xltx" -> OpenAs.SHEET_ZIP
        "pptx", "pptm", "potx", "ppsx" -> OpenAs.SLIDES_ZIP
        "odt" -> OpenAs.ODT
        "ods" -> OpenAs.ODS
        "odp" -> OpenAs.ODP
        "epub" -> OpenAs.EPUB
        else -> OpenAs.INSPECT
    }

    /** For a name that promises a format while its bytes say nothing at all. */
    fun promisedByName(name: String): OpenAs? = when (FileKinds.extensionOf(name)) {
        "docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "pptx", "pptm", "potx", "ppsx", "odt", "ods", "odp", "epub" -> damagedByName(name)
        "doc", "dot" -> OpenAs.WORD_BINARY
        "xls", "xlt" -> OpenAs.SHEET_BINARY
        "ppt", "pps", "pot" -> OpenAs.SLIDES_BINARY
        "sqlite", "sqlite3", "db3" -> OpenAs.SQLITE
        "torrent" -> OpenAs.TORRENT
        else -> null
    }

    private fun isZip(sample: ByteArray) =
        sample.size >= 4 && sample[0] == 0x50.toByte() && sample[1] == 0x4B.toByte() &&
            ((sample[2] == 0x03.toByte() && sample[3] == 0x04.toByte()) || (sample[2] == 0x05.toByte() && sample[3] == 0x06.toByte()))

    private fun looksLikeHtml(sample: ByteArray): Boolean {
        val head = String(sample, 0, minOf(sample.size, 1024), Charsets.ISO_8859_1).trimStart().lowercase(Locale.ROOT)
        return head.startsWith("<!doctype html") || head.startsWith("<html") || "<html" in head
    }

    private fun readSample(file: File): ByteArray? = try {
        file.inputStream().use { input ->
            val buffer = ByteArray(SAMPLE_SIZE)
            var got = 0
            while (got < buffer.size) {
                val read = input.read(buffer, got, buffer.size - got)
                if (read <= 0) break
                got += read
            }
            buffer.copyOf(got)
        }
    } catch (_: Exception) {
        null
    }
}

/** What a preview came to. A bad file ends up as one of these, and never as a crash. */
internal sealed interface DocLoad {
    data class Ready(val document: ReadableDocument) : DocLoad
    data object Locked : DocLoad
    data object Failed : DocLoad
}

internal object DocumentLoader {
    /** The most of a plain-text format read before it's parsed. */
    private val MaxText = DocLimits.MAX_TEXT_FILE_BYTES.toInt()

    fun load(file: File, kind: OpenAs, sizeOf: (Long) -> String, labels: DocLabels): DocLoad = guarded {
        when (kind) {
            OpenAs.WORD_ZIP -> DocxReader.read(file)
            OpenAs.WORD_BINARY -> CompoundFile.open(file).use(WordBinaryReader::read)
            OpenAs.ODT -> OpenDocumentReader.readText(file)
            OpenAs.SLIDES_ZIP -> PptxReader.read(file)
            OpenAs.SLIDES_BINARY -> CompoundFile.open(file).use(PowerPointBinaryReader::read)
            OpenAs.ODP -> OpenDocumentReader.readPresentation(file)
            OpenAs.RTF -> RtfReader.read(readCapped(file))
            OpenAs.HTML -> HtmlReader.readBytes(readCapped(file))
            OpenAs.MARKDOWN -> MarkdownReader.read(TextCodec.decode(readCapped(file)).text)
            OpenAs.EMAIL -> EmailReader.read(readCapped(file), labels)
            OpenAs.TORRENT -> TorrentReader.read(readCapped(file), sizeOf, labels)
            OpenAs.BINARY_PLIST -> BinaryPlistReader.read(readCapped(file), labels)
            else -> throw UnreadableDocumentException("not a readable page")
        }
    }

    /** A document's words alone, for what shows them as plain pages. Null if it can't be read. */
    fun plainText(file: File, labels: DocLabels = DocLabels()): String? {
        val kind = FileOpening.decide(file)
        val load = load(file, kind, { it.toString() }, labels)
        val document = (load as? DocLoad.Ready)?.document ?: return null
        return document.sections.flatMap { it.blocks }.joinToString("\n\n") { block ->
            when (block) {
                is DocBlock.Heading -> block.text
                is DocBlock.Paragraph -> block.text
                is DocBlock.Bullet -> "${block.marker} ${block.text}"
                is DocBlock.Quote -> block.text
                is DocBlock.Code -> block.text
                is DocBlock.Table -> block.rows.joinToString("\n") { it.joinToString("  |  ") }
                DocBlock.Rule -> ""
            }
        }
    }

    /** Every failure comes back as a value, including running out of memory on something unforeseen. */
    inline fun guarded(block: () -> ReadableDocument): DocLoad = try {
        DocLoad.Ready(block())
    } catch (_: ProtectedDocumentException) {
        DocLoad.Locked
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: java.io.InterruptedIOException) {
        // Nobody's waiting for this one any more, and the caller is already gone.
        throw e
    } catch (_: Exception) {
        DocLoad.Failed
    } catch (_: OutOfMemoryError) {
        DocLoad.Failed
    } catch (_: StackOverflowError) {
        DocLoad.Failed
    }

    private fun readCapped(file: File): ByteArray =
        file.inputStream().use { input ->
            LimitedInputStream(input, MaxText.toLong()).readBytes()
        }
}
