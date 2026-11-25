package com.kobblo.gallery.file.explorer.tools.files

import java.io.File
import java.io.RandomAccessFile

/** What a font file says about itself, read from its own tables. */
data class FontInfo(
    val format: String,
    val family: String?,
    val style: String?,
    val fullName: String?,
    val version: String?,
    val copyright: String?,
    val glyphCount: Int?,
    val fontsInCollection: Int
) {
    companion object {
        private const val SFNT_TRUETYPE = 0x00010000
        private const val SFNT_OPENTYPE = 0x4F54544F // OTTO
        private const val SFNT_APPLE = 0x74727565 // true
        private const val SFNT_COLLECTION = 0x74746366 // ttcf

        /** Reads a TTF, OTF or TTC. Returns null when the file isn't one, or is damaged. */
        fun parse(file: File): FontInfo? = try {
            RandomAccessFile(file, "r").use { parse(it) }
        } catch (_: Exception) {
            null
        }

        private fun parse(source: RandomAccessFile): FontInfo? {
            source.seek(0)
            var tag = source.readInt()
            var collectionSize = 1
            var directoryAt = 0L
            if (tag == SFNT_COLLECTION) {
                source.skipBytes(4)
                collectionSize = source.readInt()
                directoryAt = source.readInt().toLong() and 0xFFFFFFFFL
                source.seek(directoryAt)
                tag = source.readInt()
            }
            val format = when {
                collectionSize > 1 -> "Font Collection"
                tag == SFNT_OPENTYPE -> "OpenType"
                tag == SFNT_TRUETYPE || tag == SFNT_APPLE -> "TrueType"
                else -> return null
            }

            val tableCount = source.readUnsignedShort()
            if (tableCount > 512) return null
            source.seek(directoryAt + 12)
            val tables = HashMap<String, Long>(tableCount)
            repeat(tableCount) {
                val name = ByteArray(4).also(source::readFully).toString(Charsets.ISO_8859_1)
                source.skipBytes(4)
                val offset = source.readInt().toLong() and 0xFFFFFFFFL
                source.skipBytes(4)
                tables[name] = offset
            }

            val names = tables["name"]?.let { readNames(source, it) }.orEmpty()
            val glyphs = tables["maxp"]?.let {
                source.seek(it + 4)
                source.readUnsignedShort()
            }
            return FontInfo(
                format = format,
                family = names[ID_TYPOGRAPHIC_FAMILY] ?: names[ID_FAMILY],
                style = names[ID_TYPOGRAPHIC_STYLE] ?: names[ID_STYLE],
                fullName = names[ID_FULL_NAME],
                version = names[ID_VERSION],
                copyright = names[ID_COPYRIGHT],
                glyphCount = glyphs,
                fontsInCollection = collectionSize
            )
        }

        private const val ID_COPYRIGHT = 0
        private const val ID_FAMILY = 1
        private const val ID_STYLE = 2
        private const val ID_FULL_NAME = 4
        private const val ID_VERSION = 5
        private const val ID_TYPOGRAPHIC_FAMILY = 16
        private const val ID_TYPOGRAPHIC_STYLE = 17

        private const val PLATFORM_UNICODE = 0
        private const val PLATFORM_MAC = 1
        private const val PLATFORM_WINDOWS = 3
        private const val LANG_WINDOWS_ENGLISH = 0x0409

        /** The best string for each name id: English Windows names first, as they're the most complete. */
        private fun readNames(source: RandomAccessFile, tableAt: Long): Map<Int, String> {
            source.seek(tableAt + 2)
            val count = source.readUnsignedShort()
            val stringsAt = tableAt + source.readUnsignedShort()
            class Record(val platform: Int, val language: Int, val id: Int, val length: Int, val offset: Int)
            val records = List(count) {
                val platform = source.readUnsignedShort()
                source.skipBytes(2)
                val language = source.readUnsignedShort()
                val id = source.readUnsignedShort()
                val length = source.readUnsignedShort()
                val offset = source.readUnsignedShort()
                Record(platform, language, id, length, offset)
            }
            val wanted = setOf(ID_COPYRIGHT, ID_FAMILY, ID_STYLE, ID_FULL_NAME, ID_VERSION, ID_TYPOGRAPHIC_FAMILY, ID_TYPOGRAPHIC_STYLE)
            val best = HashMap<Int, Pair<Int, String>>()
            for (record in records) {
                if (record.id !in wanted || record.length > 8192) continue
                val rank = when {
                    record.platform == PLATFORM_WINDOWS && record.language == LANG_WINDOWS_ENGLISH -> 3
                    record.platform == PLATFORM_WINDOWS -> 2
                    record.platform == PLATFORM_UNICODE -> 2
                    record.platform == PLATFORM_MAC && record.language == 0 -> 1
                    else -> continue
                }
                if ((best[record.id]?.first ?: 0) >= rank) continue
                source.seek(stringsAt + record.offset)
                val bytes = ByteArray(record.length).also(source::readFully)
                val text = if (record.platform == PLATFORM_MAC) String(bytes, Charsets.ISO_8859_1) else String(bytes, Charsets.UTF_16BE)
                best[record.id] = rank to text.trim()
            }
            return best.mapValues { it.value.second }
        }
    }
}
