package com.kobblo.gallery.file.explorer

import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import java.util.Date
import java.util.TimeZone

/**
 * A date's text, made once per day. A library has far fewer days than files, and building the text
 * for every file is both slow and a string each.
 */
internal class DayLabels {
    private val format = DisplayDates.date()
    private val zone = TimeZone.getDefault()
    private val labels = HashMap<Long, String>()

    fun labelFor(millis: Long): String =
        labels.getOrPut(Math.floorDiv(millis + zone.getOffset(millis), DayMillis)) { format.format(Date(millis)) }

    private companion object {
        const val DayMillis = 86_400_000L
    }
}
