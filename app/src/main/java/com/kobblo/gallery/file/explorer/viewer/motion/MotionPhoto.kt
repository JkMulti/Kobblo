package com.kobblo.gallery.file.explorer.viewer.motion

import com.kobblo.gallery.file.explorer.viewer.metadata.ByteSource
import com.kobblo.gallery.file.explorer.viewer.metadata.JpegSegments
import com.kobblo.gallery.file.explorer.viewer.metadata.Xmp

/** Where a still photo's video clip sits inside its file. */
data class MotionClip(val offset: Long, val length: Long)

/**
 * Finds the video inside a motion photo. They are all a JPEG with an MP4 stuck on the end, and differ
 * in how they say so: Google's write an offset in XMP, in the older MicroVideo tags or the newer
 * Container directory, and Samsung's a trailer. Failing those, an MP4 past the end of the JPEG
 * speaks for itself.
 */
object MotionPhoto {
    private val SamsungTag = "MotionPhoto_Data".toByteArray(Charsets.ISO_8859_1)
    private val Ftyp = "ftyp".toByteArray(Charsets.ISO_8859_1)

    fun locate(source: ByteSource): MotionClip? {
        if (!JpegSegments.isJpeg(source)) return null
        val xmp = JpegSegments.xmp(source)
        fromXmp(source, xmp)?.let { return it }
        fromSamsungTrailer(source)?.let { return it }
        return fromTrailingMp4(source)
    }

    /** True for a photo with XMP saying it's a panorama, which an aspect ratio alone can't confirm. */
    fun isPanoramaXmp(xmp: String): Boolean {
        val usesViewer = Xmp.value(xmp, "UsePanoramaViewer")?.equals("true", ignoreCase = true) == true
        val projection = Xmp.value(xmp, "ProjectionType")
        return usesViewer || projection != null
    }

    private fun fromXmp(source: ByteSource, xmp: String?): MotionClip? {
        if (xmp == null) return null
        // The older tag counts back from the end of the file.
        Xmp.value(xmp, "MicroVideoOffset")?.toLongOrNull()?.let { fromEnd ->
            validated(source, source.size - fromEnd, fromEnd)?.let { return it }
        }
        // The newer form lists the file's parts in order, with lengths for everything but the first.
        val motionItem = Regex("""<[A-Za-z]*:?Item\b[^>]*Semantic="MotionPhoto"[^>]*>""").find(xmp)?.value
            ?: Regex("""Item:Semantic="MotionPhoto"[^/]*?/>""").find(xmp)?.value
        if (motionItem != null) {
            Regex("""Length="(\d+)"""").find(motionItem)?.groupValues?.get(1)?.toLongOrNull()?.let { length ->
                validated(source, source.size - length, length)?.let { return it }
            }
        }
        return null
    }

    /** Samsung tags the clip's start, and ends it with its SEF trailer. */
    private fun fromSamsungTrailer(source: ByteSource): MotionClip? {
        val tailSize = minOf(source.size, TailBytes).toInt()
        val tail = ByteArray(tailSize)
        val tailStart = source.size - tailSize
        if (source.read(tailStart, tail, 0, tailSize) != tailSize) return null
        val tagAt = indexOf(tail, SamsungTag, 0)
        if (tagAt < 0) return null
        val clipStart = tailStart + tagAt + SamsungTag.size
        // The tag can also come with the clip ahead of the trailer, in which case the bytes tell.
        val trailer = indexOf(tail, "SEFH".toByteArray(Charsets.ISO_8859_1), tagAt)
        val clipEnd = if (trailer >= 0) tailStart + trailer else source.size
        return validated(source, clipStart, clipEnd - clipStart)
    }

    private fun fromTrailingMp4(source: ByteSource): MotionClip? {
        // A photo with nothing after it ends in the JPEG end marker, which rules most out at once.
        val last = ByteArray(2)
        if (source.read(source.size - 2, last, 0, 2) == 2 && last[0] == 0xFF.toByte() && last[1] == 0xD9.toByte()) return null
        var at = 0L
        val chunk = ByteArray(ScanChunk)
        while (at < source.size) {
            val read = source.read(at, chunk, 0, chunk.size)
            if (read <= Ftyp.size) return null
            var found = indexOf(chunk, Ftyp, 0, read)
            while (found >= 0) {
                // The box's four-byte size comes just before its name.
                val start = at + found - 4
                if (start > 0) validated(source, start, source.size - start)?.let { return it }
                found = indexOf(chunk, Ftyp, found + 1, read)
            }
            at += read - (Ftyp.size - 1)
        }
        return null
    }

    private fun validated(source: ByteSource, offset: Long, length: Long): MotionClip? {
        if (offset <= 0 || length <= 8 || offset + length > source.size) return null
        val head = ByteArray(8)
        if (source.read(offset, head, 0, 8) != 8) return null
        return if (indexOf(head, Ftyp, 4) == 4) MotionClip(offset, length) else null
    }

    private fun indexOf(data: ByteArray, what: ByteArray, from: Int, end: Int = data.size): Int {
        var i = from.coerceAtLeast(0)
        val last = end - what.size
        outer@ while (i <= last) {
            for (k in what.indices) {
                if (data[i + k] != what[k]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    private const val TailBytes = 256L * 1024
    private const val ScanChunk = 256 * 1024
}
