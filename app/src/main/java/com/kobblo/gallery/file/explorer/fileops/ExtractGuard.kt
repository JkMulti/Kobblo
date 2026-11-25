package com.kobblo.gallery.file.explorer.fileops

import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.R
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Keeps an archive from filling the disk. A few kilobytes can unpack to more than any phone holds, on
 * purpose or not, and a full phone misbehaves in every other app too. So an archive whose stated size won't
 * fit is refused before anything is written, and since formats like `.gz` state nothing and a ZIP can lie,
 * writing also stops once the free space runs low.
 */
object ExtractGuard {
    /** What's left for everything else on the phone. Below this, extraction stops. */
    const val ReserveBytes = 100L * 1024L * 1024L

    /** How often a stream looks at the free space. Asking is cheap, but not per block. */
    private const val CheckEveryBytes = 8L * 1024L * 1024L

    /** The failure, with text fit to show the person. */
    class NotEnoughSpace(message: String) : IOException(message)

    /** The text in the app's language, or null with no app to ask (unit tests), where the English stands. */
    private fun say(id: Int, vararg args: Any): String? = FileSizes.context?.getString(id, *args)

    fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "$bytes B" else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }

    /** Throws if [neededBytes] won't fit in [folder]. Zero free means the system doesn't know, so it goes ahead. */
    fun requireRoom(folder: File, neededBytes: Long, freeSpace: (File) -> Long = { it.usableSpace }) {
        val free = freeSpace(folder)
        if (free > 0L && neededBytes > free - ReserveBytes) {
            throw NotEnoughSpace(
                say(R.string.app_extract_needs_space, FileSizes.label(neededBytes), FileSizes.label(free))
                    ?: "This needs ${formatBytes(neededBytes)} of space, and ${formatBytes(free)} is free"
            )
        }
    }

    /** Whether the free space has run low, for a library writing on a thread of its own. */
    fun isLow(folder: File, freeSpace: (File) -> Long = { it.usableSpace }): Boolean {
        val free = freeSpace(folder)
        return free in 1 until ReserveBytes
    }

    /** Wraps [output] so writing stops before the disk fills. */
    fun guarded(output: OutputStream, folder: File, freeSpace: (File) -> Long = { it.usableSpace }): OutputStream =
        object : OutputStream() {
            private var sinceCheck = 0L

            override fun write(b: Int) {
                note(1)
                output.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                note(len.toLong())
                output.write(b, off, len)
            }

            private fun note(count: Long) {
                sinceCheck += count
                if (sinceCheck >= CheckEveryBytes) {
                    sinceCheck = 0L
                    if (isLow(folder, freeSpace)) {
                        throw NotEnoughSpace(
                            say(R.string.app_extract_storage_full)
                                ?: "Stopped because storage is nearly full. This archive unpacks to more than there's room for"
                        )
                    }
                }
            }

            override fun flush() = output.flush()

            override fun close() = output.close()
        }
}
