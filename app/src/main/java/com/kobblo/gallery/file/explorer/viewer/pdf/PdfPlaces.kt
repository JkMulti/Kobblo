package com.kobblo.gallery.file.explorer.viewer.pdf

import android.content.Context
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Where someone left off in a document. */
internal data class PdfPlace(
    val page: Int,
    val fraction: Float,
    val zoom: Float,
    val xFraction: Float,
    val rotation: Int,
    val bookmarks: List<Int> = emptyList()
)

/**
 * What makes a place worth keeping and how it's written down. It's keyed by what's in the file rather
 * than where it is, so moving a file keeps its place and a different file with the same name doesn't
 * inherit one. Nothing here says what the file is called, which keeps it private.
 */
internal object PdfPlaceCodec {
    const val SampleBytes = 64 * 1024

    fun fingerprint(length: Long, head: ByteArray, tail: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(8).putLong(length).array())
        digest.update(head)
        digest.update(tail)
        return digest.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    fun encode(place: PdfPlace, savedAtMs: Long): String = buildString {
        append(place.page).append(',')
        append(place.fraction).append(',')
        append(place.zoom).append(',')
        append(place.xFraction).append(',')
        append(place.rotation).append(',')
        append(savedAtMs).append(',')
        append(place.bookmarks.joinToString(";"))
    }

    fun decode(value: String?): PdfPlace? {
        val parts = value?.split(',') ?: return null
        if (parts.size < 6) return null
        val page = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val fraction = parts[1].toFloatOrNull()?.takeIf { it in 0f..1f } ?: return null
        val zoom = parts[2].toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: return null
        val xFraction = parts[3].toFloatOrNull()?.takeIf { it in 0f..1f } ?: return null
        val rotation = parts[4].toIntOrNull()?.takeIf { it % 90 == 0 } ?: return null
        val bookmarks = parts.getOrNull(6)?.split(';')?.mapNotNull { it.toIntOrNull() }.orEmpty()
        return PdfPlace(page, fraction, zoom, xFraction, rotation, bookmarks)
    }

    fun decodeSavedAt(value: String?): Long = value?.split(',')?.getOrNull(5)?.toLongOrNull() ?: 0L

    /** The start of a document isn't a place anyone left off, so there's no reason to store it. */
    fun isWorthKeeping(place: PdfPlace): Boolean =
        place.page > 0 || place.fraction > 0.02f || place.zoom > 1.01f || place.rotation != 0 || place.bookmarks.isNotEmpty()
}

internal class PdfPlaceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("viewer_pdf_places", Context.MODE_PRIVATE)

    fun get(key: String): PdfPlace? = PdfPlaceCodec.decode(prefs.getString(key, null))

    fun put(key: String, place: PdfPlace) {
        if (!PdfPlaceCodec.isWorthKeeping(place)) {
            if (prefs.contains(key)) prefs.edit().remove(key).apply()
            return
        }
        prefs.edit().putString(key, PdfPlaceCodec.encode(place, System.currentTimeMillis())).apply()
        val all = prefs.all
        if (all.size > Capacity) {
            val oldest = all.entries.sortedBy { PdfPlaceCodec.decodeSavedAt(it.value as? String) }.take(all.size - Capacity)
            prefs.edit().apply { oldest.forEach { remove(it.key) } }.apply()
        }
    }

    private companion object {
        const val Capacity = 300
    }
}

/** Reads the two ends of a file, at no cost to where anything else is reading it from. */
internal fun fingerprintOf(channel: FileChannel, length: Long): String {
    val headSize = minOf(length, PdfPlaceCodec.SampleBytes.toLong()).toInt()
    val tailSize = minOf(length - headSize, PdfPlaceCodec.SampleBytes.toLong()).toInt()
    return PdfPlaceCodec.fingerprint(
        length,
        readAt(channel, 0L, headSize),
        readAt(channel, length - tailSize, tailSize)
    )
}

private fun readAt(channel: FileChannel, position: Long, size: Int): ByteArray {
    val buffer = ByteBuffer.allocate(size)
    var at = position
    while (buffer.hasRemaining()) {
        val read = channel.read(buffer, at)
        if (read <= 0) break
        at += read
    }
    return buffer.array().copyOf(buffer.position())
}
