package com.kobblo.gallery.file.explorer.tools.documents

import com.kobblo.gallery.file.explorer.music.M3u
import java.nio.charset.Charset
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** One line of a playlist. [path] is null for something on a network, which a local player can't reach. */
internal data class PlaylistEntry(
    val title: String?,
    val artist: String?,
    val path: String?,
    val remote: String? = null,
    val startMs: Long? = null,
    val durationSec: Int? = null
)

internal data class PlaylistFile(val title: String?, val entries: List<PlaylistEntry>, val truncated: Boolean)

/** `.m3u`, `.m3u8`, `.pls` and `.cue`: all lists of what to play, written three different ways. */
internal object PlaylistReader {
    private const val MAX_ENTRIES = 20_000
    private val Scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]+://")

    fun read(extension: String, text: String, baseDir: String?): PlaylistFile = when (extension) {
        "pls" -> readPls(text, baseDir)
        "cue" -> readCue(text, baseDir)
        else -> readM3u(text, baseDir)
    }

    /** The music player's own parser drops what's on a network, but here those are worth showing too. */
    private fun readM3u(text: String, baseDir: String?): PlaylistFile {
        val out = ArrayList<PlaylistEntry>()
        var title: String? = null
        var artist: String? = null
        var duration: Int? = null
        var truncated = false
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                // The player's parser already reads these lines, and only needs a stand-in path to go with one.
                val info = M3u.parse("$line\n/x", null).firstOrNull()
                title = info?.title
                artist = info?.artist
                duration = info?.durationSec
                continue
            }
            if (line.startsWith("#")) continue
            if (out.size >= MAX_ENTRIES) {
                truncated = true
                break
            }
            val remote = isRemote(line)
            out.add(
                PlaylistEntry(
                    title = title,
                    artist = artist,
                    path = if (remote) null else M3u.parse(line, baseDir).firstOrNull()?.path,
                    remote = if (remote) line else null,
                    durationSec = duration
                )
            )
            title = null
            artist = null
            duration = null
        }
        return PlaylistFile(null, out, truncated)
    }

    private fun readPls(text: String, baseDir: String?): PlaylistFile {
        val files = java.util.TreeMap<Int, String>()
        val titles = HashMap<Int, String>()
        val lengths = HashMap<Int, Int>()
        var truncated = false
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            val equals = line.indexOf('=')
            if (equals <= 0) continue
            val key = line.substring(0, equals).lowercase(Locale.ROOT)
            val value = line.substring(equals + 1).trim()
            val number = key.dropWhile { !it.isDigit() }.toIntOrNull() ?: continue
            if (files.size >= MAX_ENTRIES) {
                truncated = true
                break
            }
            when {
                key.startsWith("file") -> files[number] = value
                key.startsWith("title") -> titles[number] = value
                key.startsWith("length") -> value.toIntOrNull()?.takeIf { it >= 0 }?.let { lengths[number] = it }
            }
        }
        val entries = files.map { (number, target) ->
            val remote = isRemote(target)
            PlaylistEntry(
                title = titles[number],
                artist = null,
                path = if (remote) null else M3u.parse(target, baseDir).firstOrNull()?.path,
                remote = if (remote) target else null,
                durationSec = lengths[number]
            )
        }
        return PlaylistFile(null, entries, truncated)
    }

    /** A cue sheet cuts one long recording into tracks, so each entry says where in the file it starts. */
    private fun readCue(text: String, baseDir: String?): PlaylistFile {
        val out = ArrayList<PlaylistEntry>()
        var albumTitle: String? = null
        var albumArtist: String? = null
        var file: String? = null
        var title: String? = null
        var artist: String? = null
        var inTrack = false
        var truncated = false
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            val word = line.substringBefore(' ').uppercase(Locale.ROOT)
            val rest = line.substringAfter(' ', "").trim()
            when (word) {
                "FILE" -> {
                    val name = quoted(rest) ?: rest.substringBeforeLast(' ')
                    file = M3u.parse(name, baseDir).firstOrNull()?.path
                    inTrack = false
                }
                "TRACK" -> {
                    inTrack = true
                    title = null
                    artist = null
                }
                "TITLE" -> if (inTrack) title = quoted(rest) ?: rest else albumTitle = quoted(rest) ?: rest
                "PERFORMER" -> if (inTrack) artist = quoted(rest) ?: rest else albumArtist = quoted(rest) ?: rest
                "INDEX" -> {
                    val parts = rest.split(' ').filter { it.isNotEmpty() }
                    if (inTrack && parts.size == 2 && parts[0].trimStart('0').let { it == "1" }) {
                        if (out.size >= MAX_ENTRIES) {
                            truncated = true
                            break
                        }
                        out.add(PlaylistEntry(title, artist ?: albumArtist, file, startMs = cueTime(parts[1])))
                    }
                }
            }
        }
        return PlaylistFile(albumTitle, out, truncated)
    }

    private fun quoted(text: String): String? {
        val open = text.indexOf('"')
        if (open < 0) return null
        val close = text.indexOf('"', open + 1)
        return if (close > open) text.substring(open + 1, close) else null
    }

    /** `MM:SS:FF`, with 75 frames to a second, as on a compact disc. */
    fun cueTime(text: String): Long? {
        val parts = text.split(':')
        if (parts.size != 3) return null
        val minutes = parts[0].toLongOrNull() ?: return null
        val seconds = parts[1].toLongOrNull() ?: return null
        val frames = parts[2].toLongOrNull() ?: return null
        if (minutes < 0 || seconds < 0 || frames < 0 || minutes > 100_000) return null
        return minutes * 60_000 + seconds * 1_000 + frames * 1_000 / 75
    }

    private fun isRemote(target: String) = Scheme.containsMatchIn(target) && !target.startsWith("file:", ignoreCase = true)
}

/** Bencoding, the format of `.torrent` files, read with a cap on nesting and on how much it keeps. */
internal object Bencode {
    private const val MAX_DEPTH = 32
    private const val MAX_ITEMS = 200_000

    private class Cursor(val data: ByteArray) {
        var at = 0
        var items = 0
    }

    /** A map, a list, a number, or bytes. Throws on anything malformed. */
    fun parse(data: ByteArray): Any = Cursor(data).let { value(it, 0) }

    private fun value(cursor: Cursor, depth: Int): Any {
        if (depth > MAX_DEPTH) throw UnreadableDocumentException("nested too deep")
        if (++cursor.items > MAX_ITEMS) throw UnreadableDocumentException("too many items")
        if (cursor.at >= cursor.data.size) throw UnreadableDocumentException("ended early")
        return when (val c = cursor.data[cursor.at].toInt().toChar()) {
            'i' -> {
                val end = indexOf(cursor, 'e')
                val text = String(cursor.data, cursor.at + 1, end - cursor.at - 1, Charsets.US_ASCII)
                cursor.at = end + 1
                text.toLongOrNull() ?: throw UnreadableDocumentException("bad number")
            }
            'l' -> {
                cursor.at++
                val list = ArrayList<Any>()
                while (peek(cursor) != 'e') list.add(value(cursor, depth + 1))
                cursor.at++
                list
            }
            'd' -> {
                cursor.at++
                val map = LinkedHashMap<String, Any>()
                while (peek(cursor) != 'e') {
                    val key = value(cursor, depth + 1) as? ByteArray ?: throw UnreadableDocumentException("bad key")
                    map[String(key, Charsets.UTF_8)] = value(cursor, depth + 1)
                }
                cursor.at++
                map
            }
            in '0'..'9' -> {
                val colon = indexOf(cursor, ':')
                val size = String(cursor.data, cursor.at, colon - cursor.at, Charsets.US_ASCII).toIntOrNull()
                    ?: throw UnreadableDocumentException("bad length")
                if (size < 0 || colon + 1L + size > cursor.data.size) throw UnreadableDocumentException("length past the end")
                cursor.at = colon + 1 + size
                cursor.data.copyOfRange(colon + 1, colon + 1 + size)
            }
            else -> throw UnreadableDocumentException("bad value $c")
        }
    }

    private fun peek(cursor: Cursor): Char {
        if (cursor.at >= cursor.data.size) throw UnreadableDocumentException("ended early")
        return cursor.data[cursor.at].toInt().toChar()
    }

    private fun indexOf(cursor: Cursor, char: Char): Int {
        var at = cursor.at
        val last = minOf(cursor.data.size, cursor.at + 32)
        while (at < last) {
            if (cursor.data[at].toInt() == char.code) return at
            at++
        }
        throw UnreadableDocumentException("unterminated value")
    }

    fun looksLikeTorrent(head: ByteArray): Boolean {
        val text = String(head, 0, minOf(head.size, 64), Charsets.ISO_8859_1)
        return text.startsWith("d") && (text.contains(":announce") || text.contains("4:info") || text.contains(":creation date") || text.contains(":comment"))
    }
}

/** What a torrent describes, without fetching any of it: a label for each of the things worth knowing. */
internal object TorrentReader {
    private const val MAX_FILES_SHOWN = 500

    fun read(data: ByteArray, sizeOf: (Long) -> String, labels: DocLabels = DocLabels()): ReadableDocument {
        val root = Bencode.parse(data) as? Map<*, *> ?: throw UnreadableDocumentException("not a torrent")
        val info = root["info"] as? Map<*, *> ?: throw UnreadableDocumentException("no info")
        val builder = DocBuilder()
        val name = text(info["name.utf-8"]) ?: text(info["name"]) ?: ""
        val files = ArrayList<Pair<String, Long>>()
        val listed = info["files"] as? List<*>
        var total = 0L
        if (listed != null) {
            for (item in listed) {
                val file = item as? Map<*, *> ?: continue
                val path = (file["path.utf-8"] ?: file["path"]) as? List<*> ?: continue
                val length = file["length"] as? Long ?: 0L
                total += length.coerceAtLeast(0)
                if (files.size < MAX_FILES_SHOWN) files.add(path.mapNotNull(::text).joinToString("/") to length)
                else builder.truncated = true
            }
        } else {
            total = (info["length"] as? Long ?: 0L).coerceAtLeast(0)
            files.add(name to total)
        }
        builder.heading(1, name)
        val rows = ArrayList<List<String>>()
        rows.add(listOf(labels.size, sizeOf(total)))
        rows.add(listOf(labels.files, (listed?.size ?: 1).toString()))
        text(root["comment"])?.let { rows.add(listOf(labels.comment, it)) }
        text(root["created by"])?.let { rows.add(listOf(labels.createdBy, it)) }
        (root["creation date"] as? Long)?.takeIf { it in 1 until 8_000_000_000L }?.let {
            rows.add(listOf(labels.created, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000))))
        }
        builder.table(rows, hasHeader = false)
        builder.heading(2, labels.files)
        files.forEach { (path, length) -> builder.bullet("$path (${sizeOf(length)})") }
        val trackers = LinkedHashSet<String>()
        text(root["announce"])?.let(trackers::add)
        (root["announce-list"] as? List<*>)?.forEach { tier -> (tier as? List<*>)?.forEach { url -> text(url)?.let(trackers::add) } }
        if (trackers.isNotEmpty()) {
            builder.heading(2, labels.trackers)
            trackers.take(100).forEach { builder.bullet(it) }
        }
        return builder.build()
    }

    private fun text(value: Any?): String? = (value as? ByteArray)?.let { String(it, Charsets.UTF_8).trim().ifEmpty { null } }
}

internal data class EmailMessage(
    val subject: String?,
    val from: String?,
    val to: String?,
    val date: String?,
    val attachments: List<String>
)

/**
 * A saved email as its headers, its readable body and the names of its attachments. An HTML body goes
 * through [HtmlReader], so the tracking pixels people worry about are never fetched.
 */
internal object EmailReader {
    private const val MAX_DEPTH = 8
    private const val MAX_PARTS = 256

    private class Part(val headers: Map<String, String>, val body: ByteArray)

    fun read(bytes: ByteArray, labels: DocLabels = DocLabels()): ReadableDocument {
        val top = splitPart(bytes, 0, bytes.size)
        if (top.headers.isEmpty()) throw UnreadableDocumentException("no headers")
        val texts = ArrayList<Pair<Boolean, String>>()
        val attachments = ArrayList<String>()
        collect(top, texts, attachments, 0, intArrayOf(0), labels)

        val builder = DocBuilder()
        val rows = ArrayList<List<String>>()
        top.headers["from"]?.let { rows.add(listOf(labels.from, decodeWords(it))) }
        top.headers["to"]?.let { rows.add(listOf(labels.to, decodeWords(it))) }
        top.headers["cc"]?.let { rows.add(listOf(labels.copied, decodeWords(it))) }
        top.headers["date"]?.let { rows.add(listOf(labels.date, it)) }
        top.headers["subject"]?.let { builder.heading(1, decodeWords(it)) }
        builder.table(rows, hasHeader = false)
        // A message usually carries the same words twice, and plain text is the one that needs no guessing.
        val body = texts.firstOrNull { !it.first } ?: texts.firstOrNull()
        when {
            body == null -> Unit
            body.first -> HtmlReader.read(java.io.StringReader(body.second), builder)
            else -> body.second.split(ParagraphBreak).forEach { builder.paragraph(it) }
        }
        if (attachments.isNotEmpty()) {
            builder.heading(2, labels.attachments)
            attachments.forEach { builder.bullet(it) }
        }
        return builder.build()
    }

    private fun collect(part: Part, texts: MutableList<Pair<Boolean, String>>, attachments: MutableList<String>, depth: Int, count: IntArray, labels: DocLabels) {
        if (depth > MAX_DEPTH || ++count[0] > MAX_PARTS) return
        val contentType = part.headers["content-type"].orEmpty()
        val mime = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mime.startsWith("multipart/")) {
            val boundary = parameter(contentType, "boundary") ?: return
            boundaries(part.body, boundary).forEach { (start, end) ->
                collect(splitPart(part.body, start, end), texts, attachments, depth + 1, count, labels)
            }
            return
        }
        val disposition = part.headers["content-disposition"].orEmpty()
        val fileName = parameter(disposition, "filename") ?: parameter(contentType, "name")
        val isAttachment = disposition.trim().startsWith("attachment", ignoreCase = true) || fileName != null
        if (isAttachment) {
            attachments.add(decodeWords(fileName ?: labels.unnamed))
            return
        }
        if (mime.isEmpty() || mime == "text/plain" || mime == "text/html") {
            val decoded = decodeBody(part.body, part.headers["content-transfer-encoding"])
            val charset = parameter(contentType, "charset")?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            texts.add((mime == "text/html") to String(decoded, charset))
        }
    }

    /** Headers run to the first blank line. A header's continuation lines start with white space. */
    private fun splitPart(data: ByteArray, from: Int, to: Int): Part {
        val headers = LinkedHashMap<String, String>()
        var at = from
        var key: String? = null
        val line = StringBuilder()
        while (at < to) {
            var end = at
            while (end < to && data[end].toInt() != '\n'.code) end++
            line.setLength(0)
            for (i in at until end) if (data[i].toInt() != '\r'.code) line.append((data[i].toInt() and 0xFF).toChar())
            at = (end + 1).coerceAtMost(to)
            if (line.isEmpty()) break
            val current = key
            if (line[0] == ' ' || line[0] == '\t') {
                if (current != null) headers[current] = (headers[current].orEmpty() + " " + line.trim()).take(8192)
                continue
            }
            val colon = line.indexOf(":")
            if (colon <= 0) {
                // Not a header at all, so this isn't a message: the caller decides what to do about that.
                key = null
                continue
            }
            key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            headers[key!!] = line.substring(colon + 1).trim()
        }
        return Part(headers, data.copyOfRange(at.coerceAtMost(to), to))
    }

    private fun boundaries(data: ByteArray, boundary: String): List<Pair<Int, Int>> {
        val mark = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val hits = ArrayList<Int>()
        var at = 0
        while (at <= data.size - mark.size && hits.size < MAX_PARTS) {
            if ((at == 0 || data[at - 1].toInt() == '\n'.code) && mark.indices.all { data[at + it] == mark[it] }) {
                hits.add(at)
                at += mark.size
            } else {
                at++
            }
        }
        val out = ArrayList<Pair<Int, Int>>()
        for (i in hits.indices) {
            val start = hits[i] + mark.size
            if (start + 1 < data.size && data[start].toInt() == '-'.code && data[start + 1].toInt() == '-'.code) break
            var bodyStart = start
            while (bodyStart < data.size && data[bodyStart].toInt() != '\n'.code) bodyStart++
            bodyStart = (bodyStart + 1).coerceAtMost(data.size)
            val end = if (i + 1 < hits.size) hits[i + 1] else data.size
            if (bodyStart < end) out.add(bodyStart to end)
        }
        return out
    }

    private fun decodeBody(body: ByteArray, encoding: String?): ByteArray = when (encoding?.trim()?.lowercase(Locale.ROOT)) {
        "base64" -> try {
            java.util.Base64.getMimeDecoder().decode(body)
        } catch (_: IllegalArgumentException) {
            body
        }
        "quoted-printable" -> decodeQuotedPrintable(body)
        else -> body
    }

    fun decodeQuotedPrintable(body: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(body.size)
        var at = 0
        while (at < body.size) {
            val c = body[at].toInt() and 0xFF
            if (c != '='.code) {
                out.write(c)
                at++
                continue
            }
            if (at + 1 < body.size && body[at + 1].toInt() == '\n'.code) {
                at += 2
                continue
            }
            if (at + 2 < body.size && body[at + 1].toInt() == '\r'.code && body[at + 2].toInt() == '\n'.code) {
                at += 3
                continue
            }
            val high = if (at + 1 < body.size) Character.digit(body[at + 1].toInt(), 16) else -1
            val low = if (at + 2 < body.size) Character.digit(body[at + 2].toInt(), 16) else -1
            if (high >= 0 && low >= 0) {
                out.write(high * 16 + low)
                at += 3
            } else {
                out.write(c)
                at++
            }
        }
        return out.toByteArray()
    }

    /** `=?UTF-8?B?...?=` is how a header says it isn't plain ASCII. */
    fun decodeWords(text: String): String = EncodedWord.replace(text.take(8192)) { match ->
        val charset = runCatching { Charset.forName(match.groupValues[1]) }.getOrNull() ?: return@replace match.value
        val data = match.groupValues[3]
        val bytes = try {
            if (match.groupValues[2].equals("B", ignoreCase = true)) {
                java.util.Base64.getDecoder().decode(data.padEnd((data.length + 3) / 4 * 4, '='))
            } else {
                decodeQuotedPrintable(data.replace('_', ' ').toByteArray(Charsets.ISO_8859_1))
            }
        } catch (_: IllegalArgumentException) {
            return@replace match.value
        }
        String(bytes, charset)
    }

    private fun parameter(header: String, name: String): String? {
        val match = Regex("""(?:^|;)\s*$name\*?\s*=\s*("([^"]*)"|[^;\s]+)""", RegexOption.IGNORE_CASE).find(header) ?: return null
        return match.groupValues[2].ifEmpty { match.groupValues[1].trim('"') }.takeIf { it.isNotEmpty() }
    }

    fun looksLikeEmail(sample: ByteArray): Boolean {
        val head = String(sample, 0, minOf(sample.size, 2048), Charsets.ISO_8859_1)
        val hasFrom = head.startsWith("From:", ignoreCase = true) || head.contains("\nFrom:", ignoreCase = true) || head.startsWith("From ")
        return hasFrom && (head.contains("\nSubject:", ignoreCase = true) || head.contains("\nDate:", ignoreCase = true) || head.contains("\nTo:", ignoreCase = true))
    }

    private val ParagraphBreak = Regex("\r?\n\\s*\r?\n")
    private val EncodedWord = Regex("""=\?([^?\s]+)\?([BbQq])\?([^?\s]*)\?=""")
}

/**
 * Apple's binary property list, which has no text form of its own to open. Offsets and references all
 * come from the file, so a visit limit stops any cycle, and shared objects simply show again.
 */
internal object BinaryPlistReader {
    private const val MAX_DEPTH = 32
    private const val MAX_VISITS = 50_000

    fun looksLikeBinaryPlist(head: ByteArray): Boolean = String(head, 0, minOf(head.size, 6), Charsets.ISO_8859_1) == "bplist"

    fun read(data: ByteArray, labels: DocLabels = DocLabels()): ReadableDocument {
        if (data.size < 40 || !looksLikeBinaryPlist(data)) throw UnreadableDocumentException("not a binary plist")
        val trailer = data.size - 32
        val offsetSize = data[trailer + 6].toInt() and 0xFF
        val refSize = data[trailer + 7].toInt() and 0xFF
        val count = number(data, trailer + 8, 8)
        val top = number(data, trailer + 16, 8)
        val table = number(data, trailer + 24, 8)
        if (offsetSize !in 1..8 || refSize !in 1..8 || count !in 1..data.size.toLong() || top !in 0 until count) throw UnreadableDocumentException("bad trailer")
        if (table < 8 || table + count * offsetSize > trailer) throw UnreadableDocumentException("bad table")
        val builder = DocBuilder()
        val reader = Objects(data, table.toInt(), offsetSize, refSize, count.toInt(), labels.value, labels.binaryData)
        val rows = ArrayList<List<String>>()
        reader.flatten(top.toInt(), "", rows, 0)
        if (reader.cut) builder.truncated = true
        builder.table(rows, hasHeader = false)
        return builder.build()
    }

    private class Objects(val data: ByteArray, val table: Int, val offsetSize: Int, val refSize: Int, val count: Int, val valueLabel: String, val dataLabel: String) {
        var visits = 0
        var cut = false

        fun flatten(ref: Int, path: String, rows: MutableList<List<String>>, depth: Int) {
            if (ref !in 0 until count || depth > MAX_DEPTH || visits++ > MAX_VISITS || rows.size > DocLimits.MAX_TABLE_ROWS) {
                cut = true
                return
            }
            val at = number(data, table + ref * offsetSize, offsetSize)
            if (at !in 8 until data.size.toLong()) {
                cut = true
                return
            }
            val offset = at.toInt()
            val marker = data[offset].toInt() and 0xFF
            val kind = marker shr 4
            var size = marker and 0x0F
            var payload = offset + 1
            if (kind in setOf(0x4, 0x5, 0x6, 0xA, 0xC, 0xD) && size == 0x0F) {
                if (payload >= data.size) return
                val intMarker = data[payload].toInt() and 0xFF
                if (intMarker shr 4 != 1) return
                val bytes = 1 shl (intMarker and 0x0F)
                if (bytes > 8 || payload + 1 + bytes > data.size) return
                size = number(data, payload + 1, bytes).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                payload += 1 + bytes
            }
            when (kind) {
                0xD -> {
                    if (payload.toLong() + size.toLong() * 2 * refSize > data.size) return
                    for (i in 0 until size) {
                        val keyRef = number(data, payload + i * refSize, refSize).toInt()
                        val valueRef = number(data, payload + (size + i) * refSize, refSize).toInt()
                        val key = scalar(keyRef) ?: "?"
                        flatten(valueRef, if (path.isEmpty()) key else "$path.$key", rows, depth + 1)
                    }
                }
                0xA, 0xC -> {
                    if (payload.toLong() + size.toLong() * refSize > data.size) return
                    for (i in 0 until size) {
                        flatten(number(data, payload + i * refSize, refSize).toInt(), "$path[$i]", rows, depth + 1)
                    }
                }
                else -> rows.add(listOf(path.ifEmpty { valueLabel }, scalar(ref) ?: ""))
            }
        }

        private fun scalar(ref: Int): String? {
            if (ref !in 0 until count) return null
            val at = number(data, table + ref * offsetSize, offsetSize)
            if (at !in 8 until data.size.toLong()) return null
            val offset = at.toInt()
            val marker = data[offset].toInt() and 0xFF
            val low = marker and 0x0F
            val payload = offset + 1
            return when (marker shr 4) {
                0x0 -> when (low) {
                    0x8 -> "false"
                    0x9 -> "true"
                    else -> ""
                }
                0x1 -> {
                    val bytes = 1 shl low
                    if (bytes > 8 || payload + bytes > data.size) null else number(data, payload, bytes).toString()
                }
                0x2 -> {
                    val bytes = 1 shl low
                    if (payload + bytes > data.size) return null
                    if (bytes == 8) CellFormat.number(java.lang.Double.longBitsToDouble(number(data, payload, 8)))
                    else if (bytes == 4) CellFormat.number(java.lang.Float.intBitsToFloat(number(data, payload, 4).toInt()).toDouble())
                    else null
                }
                0x3 -> {
                    if (payload + 8 > data.size) return null
                    val seconds = java.lang.Double.longBitsToDouble(number(data, payload, 8))
                    DateFormat.getDateTimeInstance().format(Date(((seconds + 978_307_200.0) * 1000).toLong()))
                }
                0x4 -> dataLabel
                0x5, 0x6 -> {
                    var size = low
                    var start = payload
                    if (size == 0x0F) {
                        if (start >= data.size) return null
                        val intMarker = data[start].toInt() and 0xFF
                        val bytes = 1 shl (intMarker and 0x0F)
                        if (bytes > 8 || start + 1 + bytes > data.size) return null
                        size = number(data, start + 1, bytes).coerceIn(0, 1_000_000).toInt()
                        start += 1 + bytes
                    }
                    if (marker shr 4 == 0x5) {
                        val take = minOf(size, data.size - start).coerceAtLeast(0)
                        String(data, start, take, Charsets.US_ASCII)
                    } else {
                        val take = minOf(size * 2, data.size - start).coerceAtLeast(0)
                        String(data, start, take, Charsets.UTF_16BE)
                    }
                }
                else -> null
            }
        }
    }

    private fun number(data: ByteArray, at: Int, size: Int): Long {
        var value = 0L
        for (i in 0 until size) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
        return value
    }
}
