package com.kobblo.gallery.file.explorer.gallery

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** What was found in one file, and the size it had then. A different size means it's worth reading again. */
internal class IndexEntry(val sizeBytes: Long, val flags: Int, val details: PhotoDetails?)

/**
 * What the background read has found so far, kept between runs so nothing is read twice. It holds nothing that can't be
 * worked out again from the photos, so a damaged file only costs a slow pass. It's written whole and then renamed.
 */
internal object IndexCache {
    private const val FormatVersion = 2

    /** Stored text is cut to this, because a hostile or broken photo can say anything, and one string can't be written past 64 KB. */
    const val MaxTextLength = 120

    /** @param placesVersion stored places are rows of a table, which mean something else in another version of it. */
    fun read(file: File, placesVersion: Int): HashMap<Int, IndexEntry> {
        val cache = HashMap<Int, IndexEntry>()
        if (!file.exists()) return cache
        try {
            DataInputStream(file.inputStream().buffered(64 * 1024)).use { input ->
                if (input.readInt() != FormatVersion || input.readInt() != placesVersion) return cache
                val table = Array(input.readInt()) {
                    val camera = input.readUTF().ifEmpty { null }
                    val place = input.readInt()
                    val tags = List(input.readUnsignedByte()) { input.readUTF() }
                    PhotoDetails(camera, place, tags)
                }
                repeat(input.readInt()) {
                    val id = input.readInt()
                    val size = input.readLong()
                    val flags = input.readByte().toInt()
                    val detailsAt = input.readInt()
                    cache[id] = IndexEntry(size, flags, table.getOrNull(detailsAt))
                }
            }
        } catch (_: Exception) {
            cache.clear()
        } catch (_: OutOfMemoryError) {
            // A damaged count asked for more than there's room for.
            cache.clear()
        }
        return cache
    }

    fun write(file: File, placesVersion: Int, cache: Map<Int, IndexEntry>) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        val numbers = HashMap<PhotoDetails, Int>()
        val table = ArrayList<PhotoDetails>()
        for (entry in cache.values) {
            entry.details?.let { numbers.getOrPut(it) { table.add(it); table.lastIndex } }
        }
        DataOutputStream(temp.outputStream().buffered(64 * 1024)).use { output ->
            output.writeInt(FormatVersion)
            output.writeInt(placesVersion)
            output.writeInt(table.size)
            for (details in table) {
                output.writeUTF(details.camera.orEmpty().take(MaxTextLength))
                output.writeInt(details.place)
                val tags = details.tags.take(255)
                output.writeByte(tags.size)
                tags.forEach { output.writeUTF(it.take(MaxTextLength)) }
            }
            output.writeInt(cache.size)
            for ((id, entry) in cache) {
                output.writeInt(id)
                output.writeLong(entry.sizeBytes)
                output.writeByte(entry.flags)
                output.writeInt(entry.details?.let { numbers[it] } ?: -1)
            }
        }
        temp.renameTo(file)
    }
}
