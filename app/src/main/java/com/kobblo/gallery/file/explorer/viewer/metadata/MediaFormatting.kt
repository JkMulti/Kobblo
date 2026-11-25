package com.kobblo.gallery.file.explorer.viewer.metadata

import android.content.Context
import com.kobblo.gallery.file.explorer.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Turns raw tag values into what a person reads. Numbers from files are often absurd, so nothing trusts them. */
object MediaFormatting {
    fun aperture(value: Double?): String? = value?.takeIf { it > 0 }?.let { "f/" + trimmed(it, 1) }

    /** Exposures under a second read as fractions, the way a camera shows them. */
    fun exposure(seconds: Double?): String? {
        if (seconds == null || seconds <= 0) return null
        if (seconds >= 1) return trimmed(seconds, 1) + " s"
        return "1/" + (1 / seconds).roundToInt() + " s"
    }

    fun focalLength(millimeters: Double?): String? = millimeters?.takeIf { it > 0 }?.let { trimmed(it, 1) + " mm" }

    fun iso(value: Int?): String? = value?.takeIf { it > 0 }?.let { "ISO $it" }

    fun exposureBias(ev: Double?): String? {
        if (ev == null || ev == 0.0) return null
        val sign = if (ev > 0) "+" else ""
        return sign + trimmed(ev, 1) + " EV"
    }

    /** What to call a camera, from its make and model tags. */
    fun cameraName(make: String?, model: String?): String? {
        val brand = make?.trim().orEmpty()
        val name = model?.trim().orEmpty()
        return when {
            brand.isEmpty() -> name
            name.isEmpty() -> brand
            // Many cameras repeat their make in the model.
            name.startsWith(brand, ignoreCase = true) -> name
            else -> "$brand $name"
        }.ifEmpty { null }
    }

    /** A coordinate that tells at a glance which side of the world it's on. */
    fun coordinate(latitude: Double, longitude: Double): String =
        "%.4f° %s, %.4f° %s".format(
            Locale.US,
            abs(latitude), if (latitude >= 0) "N" else "S",
            abs(longitude), if (longitude >= 0) "E" else "W"
        )

    fun frameRate(value: Float?): String? = value?.takeIf { it > 0f }?.let { trimmed(it.toDouble(), 2) + " fps" }

    fun bitRate(bitsPerSecond: Long?): String? {
        if (bitsPerSecond == null || bitsPerSecond <= 0) return null
        return when {
            bitsPerSecond >= 1_000_000 -> trimmed(bitsPerSecond / 1_000_000.0, 1) + " Mbps"
            bitsPerSecond >= 1_000 -> (bitsPerSecond / 1_000.0).roundToLong().toString() + " kbps"
            else -> "$bitsPerSecond bps"
        }
    }

    fun channels(context: Context, count: Int?): String? {
        if (count == null || count <= 0) return null
        surroundLayout(count)?.let { return it }
        return when (count) {
            1 -> context.getString(R.string.media_detail_mono)
            2 -> context.getString(R.string.media_detail_stereo)
            else -> context.resources.getQuantityString(R.plurals.media_detail_channel_count, count, count)
        }
    }

    /** The surround layouts, which read the same in every language. Null for any other count. */
    fun surroundLayout(count: Int?): String? = when (count) {
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }

    fun sampleRate(hertz: Int?): String? = hertz?.takeIf { it > 0 }?.let { trimmed(it / 1000.0, 1) + " kHz" }

    fun duration(millis: Long?): String? {
        if (millis == null || millis <= 0) return null
        val total = (millis + 500) / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        else "%d:%02d".format(Locale.US, minutes, seconds)
    }

    /** Parses the ISO 6709 string a video keeps its location in, such as `+24.5000+067.2500/`. */
    fun parseIso6709(value: String?): Pair<Double, Double>? {
        if (value.isNullOrBlank()) return null
        val match = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(value.trim()) ?: return null
        val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }

    private fun trimmed(value: Double, decimals: Int): String {
        val text = "%.${decimals}f".format(Locale.US, value)
        return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
    }
}
