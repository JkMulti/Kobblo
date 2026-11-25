package com.kobblo.gallery.file.explorer.tools.documents

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * The container behind Office's older files, which works like a small file system: a table of sectors
 * and a directory of named streams.
 *
 * Every number in it comes from the file, so each one is checked before it's used. A sector chain can
 * loop forever or point past the end, and a stream can claim any size. Only the streams at the top
 * level are visible, since embedded objects hold their own copies under the same names.
 */
internal class CompoundFile private constructor(private val source: RandomAccessFile) : Closeable {
    private val length = source.length()
    private var sectorSize = 512
    private var miniSectorSize = 64
    private var miniCutoff = 4096L
    private lateinit var fat: IntArray
    private var miniFat = IntArray(0)
    private var miniStream = ByteArray(0)
    private val streams = HashMap<String, DirectoryEntry>()

    private class DirectoryEntry(val name: String, val type: Int, val left: Int, val right: Int, val child: Int, val start: Int, val size: Long)

    val streamNames: Set<String> get() = streams.keys

    fun has(name: String): Boolean = name in streams

    /** A stream's bytes, or null if there's no such stream. A claimed size past [maxBytes] is cut there. */
    fun read(name: String, maxBytes: Int = MAX_STREAM): ByteArray? {
        val entry = streams[name] ?: return null
        val size = entry.size.coerceIn(0L, maxBytes.toLong()).toInt()
        return if (entry.size < miniCutoff) readMini(entry.start, size) else readChain(entry.start, size)
    }

    override fun close() = source.close()

    private fun load() {
        val header = ByteArray(512)
        readAt(0, header, 512)
        if (!hasSignature(header)) throw UnreadableDocumentException("not a compound file")
        val shift = u16(header, 30)
        if (shift != 9 && shift != 12) throw UnreadableDocumentException("bad sector size")
        sectorSize = 1 shl shift
        val miniShift = u16(header, 32)
        if (miniShift !in 1..shift) throw UnreadableDocumentException("bad mini sector size")
        miniSectorSize = 1 shl miniShift
        val fatSectors = i32(header, 44)
        val firstDirectory = i32(header, 48)
        miniCutoff = u32(header, 56)
        val firstMiniFat = i32(header, 60)
        val miniFatSectors = i32(header, 64)
        val firstDifat = i32(header, 68)
        val difatSectors = i32(header, 72)
        val available = (length / sectorSize).toInt().coerceAtLeast(1)
        if (fatSectors < 0 || fatSectors > available) throw UnreadableDocumentException("bad table size")

        fat = readFat(header, fatSectors, firstDifat, difatSectors, available)
        readDirectory(firstDirectory)
        if (miniFatSectors in 1..available && firstMiniFat >= 0) {
            val raw = readChain(firstMiniFat, miniFatSectors * sectorSize)
            miniFat = IntArray(raw.size / 4) { i32(raw, it * 4) }
        }
    }

    private fun readFat(header: ByteArray, fatSectors: Int, firstDifat: Int, difatSectors: Int, available: Int): IntArray {
        val perSector = sectorSize / 4
        val sectors = ArrayList<Int>(fatSectors)
        for (i in 0 until 109) {
            if (sectors.size >= fatSectors) break
            val sector = i32(header, 76 + i * 4)
            if (sector >= 0) sectors.add(sector)
        }
        var next = firstDifat
        var walked = 0
        val page = ByteArray(sectorSize)
        while (next >= 0 && walked < difatSectors.coerceAtMost(available) && sectors.size < fatSectors) {
            readSector(next, page)
            for (i in 0 until perSector - 1) {
                if (sectors.size >= fatSectors) break
                val sector = i32(page, i * 4)
                if (sector >= 0) sectors.add(sector)
            }
            next = i32(page, sectorSize - 4)
            walked++
        }
        val table = IntArray(sectors.size * perSector)
        sectors.forEachIndexed { index, sector ->
            readSector(sector, page)
            for (i in 0 until perSector) table[index * perSector + i] = i32(page, i * 4)
        }
        return table
    }

    private fun readDirectory(first: Int) {
        val raw = readChain(first, MAX_DIRECTORY_BYTES, wholeChain = true)
        val count = raw.size / DIRECTORY_ENTRY
        if (count == 0) throw UnreadableDocumentException("no directory")
        val entries = Array(count) { index ->
            val at = index * DIRECTORY_ENTRY
            val nameBytes = u16(raw, at + 64).coerceIn(0, 64)
            val name = if (nameBytes >= 2) String(raw, at, nameBytes - 2, Charsets.UTF_16LE) else ""
            DirectoryEntry(
                name = name,
                type = raw[at + 66].toInt() and 0xFF,
                left = i32(raw, at + 68),
                right = i32(raw, at + 72),
                child = i32(raw, at + 76),
                start = i32(raw, at + 116),
                size = u32(raw, at + 120)
            )
        }
        val root = entries[0]
        // The directory is a tree of siblings under each storage, and a bad one can point anywhere.
        val pending = ArrayDeque<Int>()
        val visited = HashSet<Int>()
        if (root.child in entries.indices) pending.addLast(root.child)
        while (pending.isNotEmpty()) {
            val index = pending.removeLast()
            if (!visited.add(index)) continue
            val entry = entries[index]
            if (entry.type == TYPE_STREAM) streams.putIfAbsent(entry.name, entry)
            if (entry.left in entries.indices) pending.addLast(entry.left)
            if (entry.right in entries.indices) pending.addLast(entry.right)
        }
        if (root.start >= 0 && root.size > 0) {
            miniStream = readChain(root.start, root.size.coerceAtMost(MAX_STREAM.toLong()).toInt())
        }
    }

    private fun readChain(first: Int, size: Int, wholeChain: Boolean = false): ByteArray {
        val out = ByteArray(if (wholeChain) size.coerceAtMost((length).coerceAtMost(size.toLong()).toInt()) else size)
        var sector = first
        var written = 0
        var steps = 0
        val page = ByteArray(sectorSize)
        val limit = fat.size
        while (sector >= 0 && written < out.size) {
            if (sector >= limit || steps > limit) throw UnreadableDocumentException("bad sector chain")
            readSector(sector, page)
            val take = minOf(sectorSize, out.size - written)
            System.arraycopy(page, 0, out, written, take)
            written += take
            sector = fat[sector]
            steps++
        }
        return if (written == out.size) out else out.copyOf(written)
    }

    private fun readMini(first: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        var sector = first
        var written = 0
        var steps = 0
        while (sector >= 0 && written < size) {
            if (sector >= miniFat.size || steps > miniFat.size) throw UnreadableDocumentException("bad mini chain")
            val at = sector.toLong() * miniSectorSize
            if (at + miniSectorSize > miniStream.size) throw UnreadableDocumentException("mini chain past the end")
            val take = minOf(miniSectorSize, size - written)
            System.arraycopy(miniStream, at.toInt(), out, written, take)
            written += take
            sector = miniFat[sector]
            steps++
        }
        return if (written == size) out else out.copyOf(written)
    }

    private fun readSector(sector: Int, into: ByteArray) {
        val at = (sector.toLong() + 1) * sectorSize
        if (at + sectorSize > length) throw UnreadableDocumentException("sector past the end")
        readAt(at, into, sectorSize)
    }

    private fun readAt(at: Long, into: ByteArray, size: Int) {
        source.seek(at)
        source.readFully(into, 0, size)
    }

    companion object {
        private const val MAX_STREAM = 64 * 1024 * 1024
        private const val MAX_DIRECTORY_BYTES = 4 * 1024 * 1024
        private const val DIRECTORY_ENTRY = 128
        private const val TYPE_STREAM = 2
        private val Signature = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

        fun hasSignature(head: ByteArray): Boolean {
            if (head.size < Signature.size) return false
            return Signature.indices.all { head[it] == Signature[it] }
        }

        fun open(file: File): CompoundFile {
            val source = RandomAccessFile(file, "r")
            try {
                return CompoundFile(source).apply { load() }
            } catch (e: Throwable) {
                source.close()
                throw e
            }
        }

        fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

        fun i32(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

        fun u32(bytes: ByteArray, at: Int): Long = i32(bytes, at).toLong() and 0xFFFFFFFFL
    }
}
