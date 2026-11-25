package com.kobblo.gallery.file.explorer.tools.documents

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.Charset

internal data class EpubChapter(val part: String, val title: String?)

internal data class EpubBook(val title: String?, val author: String?, val chapters: List<EpubChapter>)

/**
 * A book's table of contents and reading order, then one chapter at a time. The whole book never has
 * to be in memory, and a path in the book only ever names a part of its own ZIP.
 */
internal object EpubReader {
    private const val MAX_CHAPTERS = 2_000
    private const val MAX_META_BYTES = 8L * 1024 * 1024
    private const val MAX_CHAPTER_BYTES = 16L * 1024 * 1024

    fun readBook(file: File): EpubBook = SafeZip(file).use { zip ->
        if (isLocked(zip)) throw ProtectedDocumentException()
        val opfPath = findPackage(zip) ?: throw UnreadableDocumentException("no package file")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val manifest = HashMap<String, Triple<String, String, String>>()
        val spine = ArrayList<String>()
        var title: String? = null
        var author: String? = null
        var ncxId: String? = null
        zip.scan(opfPath, maxBytes = MAX_META_BYTES) { scan ->
            var into = 0
            val value = StringBuilder()
            while (true) {
                when (scan.next()) {
                    XmlScan.Event.EOF -> break
                    XmlScan.Event.START -> when (scan.fullName.lowercase()) {
                        "dc:title" -> if (title == null) {
                            into = 1
                            value.setLength(0)
                        }
                        "dc:creator" -> if (author == null) {
                            into = 2
                            value.setLength(0)
                        }
                        else -> when (scan.name) {
                            "item" -> {
                                val id = scan.attribute("id")
                                val href = scan.attribute("href")
                                if (id != null && href != null && manifest.size < 20_000) {
                                    manifest[id] = Triple(
                                        SafeZip.resolve(opfDir, decodePath(href)),
                                        scan.attribute("media-type").orEmpty(),
                                        scan.attribute("properties").orEmpty()
                                    )
                                }
                            }
                            "itemref" -> scan.attribute("idref")?.let { if (spine.size < MAX_CHAPTERS) spine.add(it) }
                            "spine" -> ncxId = scan.attribute("toc")
                        }
                    }
                    XmlScan.Event.TEXT -> if (into != 0 && value.length < 512) value.append(scan.text)
                    XmlScan.Event.END -> if (into != 0) {
                        val done = value.toString().trim().ifEmpty { null }
                        if (into == 1) title = done else author = done
                        into = 0
                    }
                }
            }
        } ?: throw UnreadableDocumentException("no package file")

        val labels = HashMap<String, String>()
        val nav = manifest.values.firstOrNull { "nav" in it.third.split(' ') }?.first
        if (nav != null) readNavLabels(zip, nav, labels)
        val ncx = ncxId?.let(manifest::get)?.first
            ?: manifest.values.firstOrNull { it.second == "application/x-dtbncx+xml" }?.first
        if (labels.isEmpty() && ncx != null) readNcxLabels(zip, ncx, labels)

        val chapters = spine.mapNotNull { id ->
            val item = manifest[id] ?: return@mapNotNull null
            // A picture or a font in the spine has no words to read.
            val readable = item.second.isEmpty() || "html" in item.second || "xml" in item.second
            if (!readable || !zip.has(item.first)) null else EpubChapter(item.first, labels[item.first])
        }
        if (chapters.isEmpty()) throw UnreadableDocumentException("no chapters")
        EpubBook(title, author, chapters)
    }

    fun readChapter(file: File, chapter: EpubChapter): ReadableDocument = SafeZip(file).use { zip ->
        val bytes = zip.readBytes(chapter.part, MAX_CHAPTER_BYTES) ?: throw UnreadableDocumentException("missing chapter")
        val builder = DocBuilder()
        val charset = xmlCharset(bytes)
        HtmlReader.read(InputStreamReader(ByteArrayInputStream(bytes), charset), builder)
        if (zip.hitLimit) builder.truncated = true
        builder.build()
    }

    /** The part a link in [from] leads to, ignoring where in that part it points. */
    fun resolveLink(from: EpubChapter, href: String): String =
        SafeZip.resolve(from.part.substringBeforeLast('/', ""), decodePath(href))

    private fun xmlCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        return Charsets.UTF_8
    }

    /** Fonts are often scrambled by design and the words left plain, so only encrypted text counts as locked. */
    private fun isLocked(zip: SafeZip): Boolean {
        val raw = zip.readBytes("META-INF/encryption.xml", MAX_META_BYTES) ?: return false
        val text = String(raw, Charsets.UTF_8)
        val references = Regex("""URI\s*=\s*["']([^"']+)["']""").findAll(text).map { it.groupValues[1].lowercase() }.toList()
        return references.any { it.endsWith(".html") || it.endsWith(".xhtml") || it.endsWith(".htm") }
    }

    private fun findPackage(zip: SafeZip): String? {
        val fromContainer = zip.scan("META-INF/container.xml", maxBytes = MAX_META_BYTES) { scan ->
            var found: String? = null
            while (found == null) {
                when (scan.next()) {
                    XmlScan.Event.EOF -> break
                    XmlScan.Event.START -> if (scan.name == "rootfile") found = scan.attribute("full-path")
                    else -> Unit
                }
            }
            found
        }
        val resolved = fromContainer?.let { SafeZip.resolve("", it) }
        if (resolved != null && zip.has(resolved)) return resolved
        return zip.names().firstOrNull { it.endsWith(".opf", ignoreCase = true) }
    }

    private fun readNavLabels(zip: SafeZip, part: String, labels: MutableMap<String, String>) {
        val dir = part.substringBeforeLast('/', "")
        zip.scan(part, html = true, maxBytes = MAX_META_BYTES) { scan ->
            var href: String? = null
            val text = StringBuilder()
            while (labels.size < MAX_CHAPTERS) {
                when (scan.next()) {
                    XmlScan.Event.EOF -> break
                    XmlScan.Event.START -> if (scan.name == "a") {
                        href = scan.attribute("href")
                        text.setLength(0)
                    }
                    XmlScan.Event.TEXT -> if (href != null && text.length < 256) text.append(scan.text)
                    XmlScan.Event.END -> if (scan.name == "a") {
                        val link = href
                        val label = text.toString().trim()
                        if (link != null && label.isNotEmpty()) labels.putIfAbsent(SafeZip.resolve(dir, decodePath(link)), label)
                        href = null
                    }
                }
            }
        }
    }

    private fun readNcxLabels(zip: SafeZip, part: String, labels: MutableMap<String, String>) {
        val dir = part.substringBeforeLast('/', "")
        zip.scan(part, maxBytes = MAX_META_BYTES) { scan ->
            val text = StringBuilder()
            var inLabel = false
            var pending: String? = null
            while (labels.size < MAX_CHAPTERS) {
                when (scan.next()) {
                    XmlScan.Event.EOF -> break
                    XmlScan.Event.START -> when (scan.name) {
                        "navLabel" -> {
                            inLabel = true
                            text.setLength(0)
                        }
                        "content" -> {
                            val src = scan.attribute("src")
                            val label = pending
                            if (src != null && label != null) labels.putIfAbsent(SafeZip.resolve(dir, decodePath(src)), label)
                        }
                    }
                    XmlScan.Event.TEXT -> if (inLabel && text.length < 256) text.append(scan.text)
                    XmlScan.Event.END -> if (scan.name == "navLabel") {
                        inLabel = false
                        pending = text.toString().trim().ifEmpty { null }
                    }
                }
            }
        }
    }

    private fun decodePath(path: String): String = try {
        java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        path
    }
}
