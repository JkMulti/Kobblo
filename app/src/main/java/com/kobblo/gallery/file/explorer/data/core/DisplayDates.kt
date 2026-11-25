package com.kobblo.gallery.file.explorer.data.core

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dates and times written the way the app's language writes them: "Nov 6, 2026" and "8:33 PM" in English,
 * "06.11.2026" and "20:33" in German. A fixed English pattern put the English order and clock into every language.
 * Display only: nothing reads these back.
 */
object DisplayDates {
    /** A day, medium length. */
    fun date(locale: Locale = Locale.getDefault()): SimpleDateFormat =
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale) as? SimpleDateFormat ?: SimpleDateFormat("MMM d, yyyy", locale)

    /** A time of day, with the language's own clock. */
    fun time(locale: Locale = Locale.getDefault()): SimpleDateFormat =
        DateFormat.getTimeInstance(DateFormat.SHORT, locale) as? SimpleDateFormat ?: SimpleDateFormat("h:mm a", locale)

    /** A day and its time, joined as the language joins them. */
    fun dateTime(locale: Locale = Locale.getDefault()): SimpleDateFormat =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) as? SimpleDateFormat
            ?: SimpleDateFormat("MMM d, yyyy h:mm a", locale)

    /** A day and its time with a dot between, for a row's second line. */
    fun dateDotTime(millis: Long, locale: Locale = Locale.getDefault()): String {
        val moment = Date(millis)
        return "${date(locale).format(moment)} · ${time(locale).format(moment)}"
    }
}
