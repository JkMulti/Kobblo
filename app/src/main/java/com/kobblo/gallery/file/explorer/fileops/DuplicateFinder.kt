package com.kobblo.gallery.file.explorer.fileops

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Decides which files really are the same, because the answer gets files deleted. Two files of one size
 * with the same start can still differ: videos from one camera, disk images, databases, padded archives. So
 * a quick look at each end only narrows down what's worth reading, and nothing is called a duplicate until
 * every byte has been compared.
 *
 * It costs little for the usual case. Files that differ almost always differ at one end and are told apart
 * after 32 KB each, so whole files are only read when they're very likely the same, and a mismatch then stops
 * the comparison at the first block that differs.
 */
object DuplicateFinder {
    /** How much is read from each end to narrow things down. */
    const val QuickBytes = 16 * 1024
    private const val Buffer = 256 * 1024

    /**
     * Groups of two or more paths whose contents are identical, from [paths] that are all said to be [size]
     * bytes. A file that can't be read, or isn't that size any more, is in no group: nothing unproven is ever
     * a duplicate. [check] runs between reads and stops the work by throwing.
     */
    fun provenGroups(paths: List<String>, size: Long, check: () -> Unit = {}): List<List<String>> {
        val byQuickLook = HashMap<ByteBuffer, MutableList<String>>()
        for (path in paths) {
            check()
            val key = try {
                quickLook(File(path), size)
            } catch (_: IOException) {
                null
            } ?: continue
            byQuickLook.getOrPut(key) { ArrayList(2) }.add(path)
        }
        val groups = ArrayList<List<String>>()
        for (candidates in byQuickLook.values) {
            if (candidates.size >= 2) groups += confirm(candidates, check)
        }
        return groups
    }

    /** The first and last [QuickBytes], digested. Null if the file isn't [size] bytes any more. */
    private fun quickLook(file: File, size: Long): ByteBuffer? {
        if (file.length() != size) return null
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { handle ->
            val buffer = ByteArray(QuickBytes)
            digest.update(buffer, 0, readFully(handle, buffer, minOf(QuickBytes.toLong(), size).toInt()))
            if (size > QuickBytes) {
                val tailStart = maxOf(QuickBytes.toLong(), size - QuickBytes)
                handle.seek(tailStart)
                digest.update(buffer, 0, readFully(handle, buffer, (size - tailStart).toInt()))
            }
        }
        return ByteBuffer.wrap(digest.digest())
    }

    private fun readFully(handle: RandomAccessFile, buffer: ByteArray, wanted: Int): Int {
        var total = 0
        while (total < wanted) {
            val read = handle.read(buffer, total, wanted - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    /** Sorts files that look alike into sets that are alike, comparing each with one member of each set. */
    private fun confirm(candidates: List<String>, check: () -> Unit): List<List<String>> {
        val sets = ArrayList<MutableList<String>>()
        for (path in candidates) {
            check()
            val home = sets.firstOrNull { set ->
                try {
                    sameBytes(File(set.first()), File(path), check)
                } catch (_: IOException) {
                    false
                }
            }
            if (home != null) home.add(path) else sets.add(arrayListOf(path))
        }
        return sets.filter { it.size > 1 }
    }

    /** Every byte of the two, stopping at the first block that differs. */
    fun sameBytes(a: File, b: File, check: () -> Unit = {}): Boolean {
        if (a.length() != b.length()) return false
        FileInputStream(a).use { left ->
            FileInputStream(b).use { right ->
                val leftBuffer = ByteArray(Buffer)
                val rightBuffer = ByteArray(Buffer)
                while (true) {
                    check()
                    val count = fill(left, leftBuffer)
                    if (count != fill(right, rightBuffer)) return false
                    if (count == 0) return true
                    for (i in 0 until count) if (leftBuffer[i] != rightBuffer[i]) return false
                }
            }
        }
    }

    /** Fills [buffer] as far as the file allows, since a single read may stop short anywhere. */
    private fun fill(input: FileInputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    /** The whole file's SHA-256 in hex, or null if it can't be read. */
    fun checksum(file: File, check: () -> Unit = {}): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(Buffer)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: IOException) {
        null
    }
}
