package com.kobblo.gallery.file.explorer.viewer.decoder

import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A picture's size from its header, for the formats the platform can't even measure. */
object ImageHeaders {
    private const val HeadBytes = 4096

    fun dimensions(file: File): Pair<Int, Int>? = try {
        val head = ByteArray(HeadBytes)
        val read = file.inputStream().use { it.read(head) }
        if (read <= 0) null else dimensions(head.copyOf(read), FileKinds.extensionOf(file.name))
    } catch (_: Exception) {
        null
    }

    fun dimensions(head: ByteArray, extension: String): Pair<Int, Int>? = try {
        when {
            PsdDecoder.isPsd(head) && head.size >= 22 -> {
                val data = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
                data.getInt(18) to data.getInt(14)
            }
            extension == "tga" && TgaDecoder.looksLikeTga(head) -> {
                val data = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                (data.getShort(12).toInt() and 0xFFFF) to (data.getShort(14).toInt() and 0xFFFF)
            }
            PnmDecoder.isPnm(head) -> pnmSize(head)
            else -> null
        }?.takeIf { it.first > 0 && it.second > 0 }
    } catch (_: Exception) {
        null
    }

    private fun pnmSize(head: ByteArray): Pair<Int, Int>? {
        // Comments may come between the numbers, so they go first.
        val text = String(head, Charsets.ISO_8859_1).lineSequence()
            .joinToString(" ") { it.substringBefore('#') }
        val numbers = Regex("""\d+""").findAll(text.substring(2)).take(2).map { it.value.toIntOrNull() ?: 0 }.toList()
        return if (numbers.size == 2) numbers[0] to numbers[1] else null
    }
}
