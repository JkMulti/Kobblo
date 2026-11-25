package com.kobblo.gallery.file.explorer.tools.files

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A table read from a CSV-like file. [truncated] says more rows existed than were kept. */
data class TextTable(val rows: List<List<String>>, val truncated: Boolean) {
    val columnCount: Int = rows.maxOfOrNull { it.size } ?: 0
}

object Csv {
    /** The separator a file uses, judged from its first line, outside any quotes. */
    fun detectDelimiter(text: String): Char {
        val counts = mutableMapOf(',' to 0, ';' to 0, '\t' to 0, '|' to 0)
        var quoted = false
        for (c in text) {
            if (c == '"') quoted = !quoted
            if (!quoted && (c == '\n' || c == '\r')) break
            if (!quoted && c in counts) counts[c] = counts.getValue(c) + 1
        }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    /** RFC 4180: quotes wrap a field, double quotes escape, and a quoted field may span lines. */
    fun parse(text: String, delimiter: Char = detectDelimiter(text), maxRows: Int = 20_000): TextTable {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = if (text.startsWith('﻿')) 1 else 0
        var rowHasContent = false
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    c == '"' -> quoted = false
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> {
                        quoted = true
                        rowHasContent = true
                    }
                    delimiter -> {
                        row.add(field.toString())
                        field.clear()
                        rowHasContent = true
                    }
                    '\r', '\n' -> {
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        if (rowHasContent || field.isNotEmpty()) {
                            row.add(field.toString())
                            rows.add(row)
                            if (rows.size >= maxRows) return TextTable(rows, truncated = i + 1 < text.length)
                        }
                        row = ArrayList()
                        field.clear()
                        rowHasContent = false
                    }
                    else -> {
                        field.append(c)
                        rowHasContent = true
                    }
                }
            }
            i++
        }
        if (rowHasContent || field.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return TextTable(rows, truncated = false)
    }
}

/** One line of a vCard or iCalendar file: `NAME;PARAM=VALUE:content`. */
internal data class ContentLine(val name: String, val params: Map<String, String>, val value: String)

internal object ContentLines {
    /** Long lines continue on the next line with a leading space, so they're joined back first. */
    fun parse(text: String): List<ContentLine> {
        val unfolded = ArrayList<StringBuilder>()
        text.split("\r\n", "\n", "\r").forEach { line ->
            if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) {
                unfolded.last().append(line.substring(1))
            } else if (line.isNotEmpty()) {
                unfolded.add(StringBuilder(line))
            }
        }
        return unfolded.mapNotNull { parseLine(it.toString()) }
    }

    private fun parseLine(line: String): ContentLine? {
        var quoted = false
        var colon = -1
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) {
                    colon = i
                    break
                }
            }
            if (colon >= 0) break
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon).split(';')
        // vCard allows a group prefix such as `item1.TEL`.
        val name = head.first().substringAfterLast('.').uppercase(Locale.ROOT)
        val params = head.drop(1).associate { param ->
            val key = param.substringBefore('=').uppercase(Locale.ROOT)
            key to param.substringAfter('=', "").trim('"')
        }
        return ContentLine(name, params, line.substring(colon + 1))
    }

    fun unescape(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> append('\n')
                    else -> append(next)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}

data class ContactCard(
    val name: String,
    val organization: String?,
    val title: String?,
    val phones: List<String>,
    val emails: List<String>,
    val addresses: List<String>,
    val websites: List<String>,
    val birthday: String?,
    val note: String?
)

object VCard {
    fun parse(text: String): List<ContactCard> {
        val cards = ArrayList<ContactCard>()
        var fields: MutableList<ContentLine>? = null
        for (line in ContentLines.parse(text)) {
            when {
                line.name == "BEGIN" && line.value.equals("VCARD", ignoreCase = true) -> fields = ArrayList()
                line.name == "END" && line.value.equals("VCARD", ignoreCase = true) -> {
                    fields?.let { cards.add(toCard(it)) }
                    fields = null
                }
                else -> fields?.add(line)
            }
        }
        return cards
    }

    private fun toCard(fields: List<ContentLine>): ContactCard {
        fun all(name: String) = fields.filter { it.name == name }.map { ContentLines.unescape(it.value).trim() }.filter { it.isNotEmpty() }
        fun first(name: String) = all(name).firstOrNull()
        val structured = fields.firstOrNull { it.name == "N" }?.value?.split(';').orEmpty().map { ContentLines.unescape(it).trim() }
        val fromParts = listOf(structured.getOrNull(3), structured.getOrNull(1), structured.getOrNull(2), structured.getOrNull(0))
            .filterNot { it.isNullOrEmpty() }
            .joinToString(" ")
        val addresses = fields.filter { it.name == "ADR" }.map { adr ->
            adr.value.split(';').map { ContentLines.unescape(it).trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        }.filter { it.isNotEmpty() }
        return ContactCard(
            name = first("FN") ?: fromParts.ifEmpty { first("ORG")?.substringBefore(';') ?: "" },
            organization = first("ORG")?.replace(";", ", ")?.trimEnd(',', ' '),
            title = first("TITLE"),
            phones = all("TEL").map { it.removePrefix("tel:") },
            emails = all("EMAIL"),
            addresses = addresses,
            websites = all("URL"),
            birthday = first("BDAY"),
            note = first("NOTE")
        )
    }
}

data class CalendarEvent(
    val title: String,
    val start: Date?,
    val end: Date?,
    val allDay: Boolean,
    val location: String?,
    val description: String?
)

object ICalendar {
    fun parse(text: String): List<CalendarEvent> {
        val events = ArrayList<CalendarEvent>()
        var fields: MutableList<ContentLine>? = null
        for (line in ContentLines.parse(text)) {
            when {
                line.name == "BEGIN" && line.value.equals("VEVENT", ignoreCase = true) -> fields = ArrayList()
                line.name == "END" && line.value.equals("VEVENT", ignoreCase = true) -> {
                    fields?.let { events.add(toEvent(it)) }
                    fields = null
                }
                else -> fields?.add(line)
            }
        }
        return events.sortedBy { it.start ?: Date(Long.MAX_VALUE) }
    }

    private fun toEvent(fields: List<ContentLine>): CalendarEvent {
        fun text(name: String) = fields.firstOrNull { it.name == name }?.let { ContentLines.unescape(it.value).trim() }?.takeIf { it.isNotEmpty() }
        val start = fields.firstOrNull { it.name == "DTSTART" }
        val end = fields.firstOrNull { it.name == "DTEND" }
        return CalendarEvent(
            title = text("SUMMARY") ?: "",
            start = start?.let(::parseDate),
            end = end?.let(::parseDate),
            allDay = start?.let { it.params["VALUE"] == "DATE" || it.value.length == 8 } == true,
            location = text("LOCATION"),
            description = text("DESCRIPTION")
        )
    }

    /** A UTC time ends in Z, a TZID names a zone, and anything else is local to whoever reads it. */
    internal fun parseDate(line: ContentLine): Date? {
        val value = line.value.trim()
        val zone = line.params["TZID"]?.let { TimeZone.getTimeZone(it) }
        return try {
            when {
                value.length == 8 -> format("yyyyMMdd", null).parse(value)
                value.endsWith("Z") -> format("yyyyMMdd'T'HHmmss'Z'", TimeZone.getTimeZone("UTC")).parse(value)
                else -> format("yyyyMMdd'T'HHmmss", zone).parse(value)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun format(pattern: String, zone: TimeZone?) = SimpleDateFormat(pattern, Locale.ROOT).apply {
        if (zone != null) timeZone = zone
    }

    fun describeTime(event: CalendarEvent, locale: Locale = Locale.getDefault()): String {
        val start = event.start ?: return ""
        val day = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
        if (event.allDay) return day.format(start)
        val dayTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        val end = event.end ?: return dayTime.format(start)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
        return if (day.format(start) == day.format(end)) "${dayTime.format(start)} – ${time.format(end)}"
        else "${dayTime.format(start)} – ${dayTime.format(end)}"
    }
}
