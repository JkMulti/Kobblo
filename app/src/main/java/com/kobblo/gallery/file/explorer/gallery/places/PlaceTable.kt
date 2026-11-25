package com.kobblo.gallery.file.explorer.gallery.places

import android.content.Context
import java.io.DataInputStream
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

/**
 * Every city of 15,000 people or more, for telling where a photo was taken with no network. A place is its row
 * here, and rows are sorted by latitude, so a lookup only looks at the band of the world it could be in. Built by
 * `scripts/places/build_places.py` from GeoNames (CC BY 4.0).
 *
 * This is deliberately "near what town" rather than "in what town": a coordinate this close to a border may name the
 * town over the line, and nothing here knows where the line is.
 */
class PlaceTable private constructor(
    private val latitudes: ShortArray,
    private val longitudes: ShortArray,
    private val reach: ByteArray,
    private val countries: ByteArray,
    private val nameOffsets: IntArray,
    private val names: ByteArray
) {
    val size: Int get() = latitudes.size

    /** The place a coordinate belongs to, or -1 out at sea or far from anywhere. */
    fun nearest(latitude: Double, longitude: Double): Int {
        val lowest = firstAtOrAbove(((latitude - MaxDistanceKm / KmPerDegree) * 100).toInt())
        val highest = (latitude + MaxDistanceKm / KmPerDegree) * 100
        val lonScale = cos(Math.toRadians(latitude))
        var best = -1
        var bestScore = Double.MAX_VALUE
        var row = lowest
        while (row < size && latitudes[row] <= highest) {
            val dLat = latitudes[row] / 100.0 - latitude
            var dLon = abs(longitudes[row] / 100.0 - longitude)
            if (dLon > 180) dLon = 360 - dLon
            val distance = KmPerDegree * Math.sqrt(dLat * dLat + (dLon * lonScale) * (dLon * lonScale))
            if (distance <= MaxDistanceKm) {
                // A city claims what's within its reach, so the middle of a big town isn't given to a suburb.
                val score = distance - (reach[row].toInt() and 0xFF) / 2.0
                if (score < bestScore) {
                    bestScore = score
                    best = row
                }
            }
            row++
        }
        return best
    }

    fun name(place: Int): String =
        String(names, nameOffsets[place], nameOffsets[place + 1] - nameOffsets[place], Charsets.UTF_8)

    fun countryCode(place: Int): String =
        String(countries, place * 2, 2, Charsets.US_ASCII)

    private fun firstAtOrAbove(target: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (latitudes[mid] < target) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        /** Stored places are row numbers, so a new table has to throw the stored ones away. */
        const val Version = 1

        /** Further than this from any town of note, a name would mislead more than it helps. */
        private const val MaxDistanceKm = 120.0
        private const val KmPerDegree = 111.2

        fun read(input: InputStream): PlaceTable {
            val data = DataInputStream(input.buffered(64 * 1024))
            val magic = ByteArray(4)
            data.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == "PLC1") { "Not a place table" }
            val count = data.readInt()
            val latitudes = ShortArray(count) { data.readShort() }
            val longitudes = ShortArray(count) { data.readShort() }
            val reach = ByteArray(count).also(data::readFully)
            val countries = ByteArray(count * 2).also(data::readFully)
            val rest = data.readBytes()
            val offsets = IntArray(count + 1)
            val names = ByteArray(rest.size)
            var at = 0
            var written = 0
            for (i in 0 until count) {
                val length = rest[at++].toInt() and 0xFF
                System.arraycopy(rest, at, names, written, length)
                at += length
                written += length
                offsets[i + 1] = written
            }
            return PlaceTable(latitudes, longitudes, reach, countries, offsets, names)
        }

        @Volatile private var loaded: PlaceTable? = null

        /** Read once, and only by something that needs it. Call off the main thread. */
        fun get(context: Context): PlaceTable =
            loaded ?: synchronized(this) {
                loaded ?: context.applicationContext.assets.open("places.bin").use { read(it) }.also { loaded = it }
            }
    }
}

/** A country's name in the person's own language, from the two letters the table holds. */
fun countryName(code: String, locale: Locale = Locale.getDefault()): String =
    Locale("", code).getDisplayCountry(locale).ifEmpty { code }
