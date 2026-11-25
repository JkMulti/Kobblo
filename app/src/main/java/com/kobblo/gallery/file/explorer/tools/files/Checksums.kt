package com.kobblo.gallery.file.explorer.tools.files

import java.io.InputStream
import java.security.MessageDigest

/** File checksums. All of them are computed in one pass, so a big file is only read once. */
object Checksums {
    val Algorithms = listOf("MD5", "SHA-1", "SHA-256")

    /**
     * @param onProgress receives the bytes read so far.
     * @param checkCancelled is called between blocks and expected to throw to stop.
     * @return each algorithm's name and its digest in lowercase hex.
     */
    fun compute(
        input: InputStream,
        algorithms: List<String> = Algorithms,
        onProgress: (Long) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): Map<String, String> {
        val digests = algorithms.associateWith { MessageDigest.getInstance(it) }
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            digests.values.forEach { it.update(buffer, 0, read) }
            total += read
            onProgress(total)
        }
        return digests.mapValues { (_, digest) -> toHex(digest.digest()) }
    }

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val digits = "0123456789abcdef"
        for (b in bytes) {
            val value = b.toInt() and 0xFF
            append(digits[value shr 4]).append(digits[value and 0x0F])
        }
    }
}
