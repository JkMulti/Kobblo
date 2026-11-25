package com.kobblo.gallery.file.explorer.data.core

import android.content.Context
import android.text.format.Formatter
import androidx.collection.MutableLongObjectMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The one way a size becomes text, so a file reads the same in every list, panel and total. It's
 * the system's short format: decimal units and two or three digits, as the system's own Files app
 * shows them.
 *
 * Formatting takes long enough to matter once per row at 100,000 rows, so labels are remembered by
 * [labelKey] and a row costs a lookup.
 */
object FileSizes {
    private const val MaxRemembered = 20_000

    @Volatile
    private var appContext: Context? = null

    private val remembered = MutableLongObjectMap<String>()
    private var rememberedLocale: Locale? = null

    /** The app, for text built where no context is at hand. Null before [init], as in unit tests. */
    val context: Context? get() = appContext

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun label(bytes: Long): String {
        val size = bytes.coerceAtLeast(0L)
        val key = labelKey(size)
        val locale = Locale.getDefault()
        synchronized(remembered) {
            if (locale != rememberedLocale) {
                remembered.clear()
                rememberedLocale = locale
            }
            remembered[key]?.let { return it }
        }
        val text = format(size)
        synchronized(remembered) {
            // Well past what a library needs, and it only costs recomputing a few labels.
            if (remembered.size >= MaxRemembered) remembered.clear()
            remembered[key] = text
        }
        return text
    }

    private fun format(bytes: Long): String {
        val context = appContext ?: return plainLabel(bytes)
        return Formatter.formatShortFileSize(context, bytes)
    }

    /**
     * Which sizes share a label. The label shows at most three digits and rounds on the fourth, so
     * sizes agreeing to four digits always read the same.
     */
    internal fun labelKey(bytes: Long): Long {
        var divisor = 1L
        while (bytes / divisor >= 10_000L) divisor *= 10L
        return bytes / divisor * divisor
    }

    /**
     * The same rule as the system's formatter, for when there's no context to ask it with. It steps
     * to the next unit past 900, and shows two digits up to 100 and three after.
     */
    internal fun plainLabel(bytes: Long): String {
        val units = arrayOf("B", "kB", "MB", "GB", "TB", "PB")
        var value = abs(bytes).toFloat()
        var unit = 0
        while (value > 900f && unit < units.lastIndex) {
            value /= 1000f
            unit++
        }
        val digits = when {
            unit == 0 || value >= 10f -> 0
            value < 1f -> 2
            else -> 1
        }
        val scale = when (digits) {
            0 -> 1f
            1 -> 10f
            else -> 100f
        }
        val rounded = (value * scale).roundToLong()
        val whole = rounded / scale.toLong()
        val text = when (digits) {
            0 -> "$whole"
            1 -> "$whole.${rounded % 10}"
            else -> "$whole.${(rounded % 100).toString().padStart(2, '0')}"
        }
        return "$text ${units[unit]}"
    }
}
