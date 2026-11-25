package com.kobblo.gallery.file.explorer.ui

import androidx.annotation.PluralsRes
import com.kobblo.gallery.file.explorer.R

/**
 * The rules for how long Recently Deleted keeps a file. The length is saved as a number and a unit,
 * and the unit as an English word such as "Days", which is what older installs hold.
 */
internal object BinRetention {
    /** The length a bin keeps files for when nobody has said, and what a blank answer falls back to. */
    const val DefaultValue = 7

    private val plurals = mapOf(
        "second" to R.plurals.view_retention_seconds,
        "minute" to R.plurals.view_retention_minutes,
        "hour" to R.plurals.view_retention_hours,
        "day" to R.plurals.view_retention_days,
        "week" to R.plurals.view_retention_weeks,
        "month" to R.plurals.view_retention_months,
        "year" to R.plurals.view_retention_years
    )

    /**
     * The plural that words a length in [unit], so "Days" can be shown in the person's language. The
     * unit may be saved in any case and with or without its "s". Null for a word that isn't a unit.
     */
    @PluralsRes
    fun pluralFor(unit: String): Int? = plurals[unit.lowercase().removeSuffix("s")]

    /** How a length in a unit with no plural is shown: as it was saved, in lower case. */
    fun plainText(value: Int, unit: String): String = "$value ${unit.lowercase()}"

    /** Whether [text] can stand in the field for the length: digits, or nothing yet. */
    fun canBeTyped(text: String): Boolean = text.all { it.isDigit() }

    /** The length [typed] asks for. Never less than 1, and [DefaultValue] when it's blank or too big to be a number. */
    fun valueOf(typed: String): Int = typed.toIntOrNull()?.coerceAtLeast(1) ?: DefaultValue
}
