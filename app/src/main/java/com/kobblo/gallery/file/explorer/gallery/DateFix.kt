package com.kobblo.gallery.file.explorer.gallery

import android.content.Context
import com.kobblo.gallery.file.explorer.FileItem
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * The date a phone or an app wrote into a file's name. After restoring a backup, every photo with no EXIF date claims
 * to be from the day of the restore, and these names are often the only record of when it really was.
 */
internal object DateFromName {
    /** `IMG_20230512_142301`, `PXL_20230512_142301123`, `VID-20230512-142301`. */
    private val Compact = Regex("(?<!\\d)((?:19|20)\\d{2})(\\d{2})(\\d{2})[_-]?(\\d{2})(\\d{2})(\\d{2})(?:\\d{0,3})(?!\\d)")

    /** `Screenshot_2023-05-12-14-23-01`, `Photo 2023-05-12 at 14.23.01`. */
    private val Spelled = Regex("(?<!\\d)((?:19|20)\\d{2})-(\\d{2})-(\\d{2})(?:[-_ ]|\\sat\\s)(\\d{2})[-._](\\d{2})[-._](\\d{2})(?!\\d)")

    /** `IMG-20230512-WA0012`, with no time of day. */
    private val DayOnly = Regex("(?<!\\d)((?:19|20)\\d{2})-?(\\d{2})-?(\\d{2})(?!\\d)")

    /** Milliseconds since 1970, which plenty of apps name things by. */
    private val Epoch = Regex("(?<!\\d)(1[2-9]\\d{11})(?!\\d)")

    /** When the name says this was made, or null if it doesn't say, or says something that can't be right. */
    fun parse(name: String, nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        val stem = name.substringBeforeLast('.')
        for (pattern in listOf(Compact, Spelled)) {
            val match = pattern.find(stem) ?: continue
            val (year, month, day, hour, minute, second) = match.destructured
            moment(year, month, day, hour, minute, second, nowMillis, zone)?.let { return it }
        }
        Epoch.find(stem)?.let { match ->
            match.groupValues[1].toLong().takeIf { it <= nowMillis }?.let { return it }
        }
        // Noon, so that no time zone can move it to another day.
        DayOnly.find(stem)?.let { match ->
            val (year, month, day) = match.destructured
            moment(year, month, day, "12", "00", "00", nowMillis, zone)?.let { return it }
        }
        return null
    }

    private fun moment(year: String, month: String, day: String, hour: String, minute: String, second: String, nowMillis: Long, zone: TimeZone): Long? {
        val calendar = Calendar.getInstance(zone, Locale.ROOT).apply {
            isLenient = false
            clear()
        }
        return try {
            calendar.set(year.toInt(), month.toInt() - 1, day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
            calendar.timeInMillis.takeIf { it <= nowMillis }
        } catch (_: IllegalArgumentException) {
            // A thirteenth month is just a long number in a name.
            null
        }
    }

    /**
     * Whether a file's date should move to what its name says: its name has a date, the file's date is more than a
     * day away from it, and the file doesn't hold a date of its own, which is better evidence than any name.
     */
    fun correction(file: FileItem, takenMillis: Long, nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (file.isLocked || takenMillis > 0L) return null
        val named = parse(file.name, nowMillis, zone) ?: return null
        return named.takeIf { abs(it - file.lastModified) > DayMillis }
    }

    private const val DayMillis = 24L * 60 * 60 * 1000
}

/** What a date fix changed, so it can be put back. Only the last one is kept, in the app's own folder. */
internal object DateFixRecord {
    private fun file(context: Context) = File(context.applicationContext.filesDir, "gallery/date_fix.txt")

    fun write(context: Context, changes: List<Pair<String, Long>>) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".tmp")
        temp.bufferedWriter().use { out ->
            for ((path, millis) in changes) out.appendLine("$millis\t$path")
        }
        temp.renameTo(target)
    }

    fun read(context: Context): List<Pair<String, Long>> = try {
        file(context).takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { line ->
            val tab = line.indexOf('\t')
            val millis = if (tab > 0) line.substring(0, tab).toLongOrNull() else null
            if (millis != null) line.substring(tab + 1) to millis else null
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun clear(context: Context) {
        file(context).delete()
    }
}
