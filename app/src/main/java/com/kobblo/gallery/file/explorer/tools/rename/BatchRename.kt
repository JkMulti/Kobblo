package com.kobblo.gallery.file.explorer.tools.rename

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CaseChange { KEEP, LOWER, UPPER, TITLE }

/**
 * A rename applied to many files at once.
 *
 * @param pattern the new name without its extension. `{name}` is the old name, `{ext}` its extension,
 * `{n}` a counter (`{n:3}` pads it to three digits), `{parent}` its folder, and `{date}` the day it
 * was modified (`{date:yyyy-MM-dd}` chooses the format). Anything else is taken literally.
 */
data class RenameRule(
    val pattern: String = "{name}",
    val find: String = "",
    val replace: String = "",
    val findIsRegex: Boolean = false,
    val matchCase: Boolean = false,
    val caseChange: CaseChange = CaseChange.KEEP,
    val startNumber: Int = 1,
    val keepExtension: Boolean = true
)

/** A file going into a rename. */
data class RenameSource(val name: String, val parent: String, val modified: Long, val isDirectory: Boolean = false)

enum class RenameProblem { EMPTY_NAME, INVALID_CHARACTER, DUPLICATE_IN_BATCH, EXISTS, TOO_LONG }

/** What would happen to one file. [newName] is its current name when nothing would change. */
data class RenamePreview(val source: RenameSource, val newName: String, val problem: RenameProblem?) {
    val changes: Boolean get() = newName != source.name
}

object BatchRename {
    private val token = Regex("\\{(name|ext|n|date|parent)(?::([^}]*))?\\}")
    private const val MAX_NAME_BYTES = 255

    /** The new name for one file, the [index]th of the batch. */
    fun newName(source: RenameSource, index: Int, rule: RenameRule, locale: Locale = Locale.getDefault()): String {
        val stem = stemOf(source)
        val extension = extensionOf(source)
        val filled = token.replace(rule.pattern) { match ->
            val argument = match.groups[2]?.value
            when (match.groupValues[1]) {
                "name" -> stem
                "ext" -> extension
                "parent" -> File(source.parent).name
                "n" -> {
                    val width = argument?.toIntOrNull()?.coerceIn(1, 12) ?: 1
                    (rule.startNumber + index).toString().padStart(width, '0')
                }
                "date" -> formatDate(source.modified, argument, locale)
                else -> match.value
            }
        }
        val replaced = applyFind(filled, rule)
        val cased = when (rule.caseChange) {
            CaseChange.KEEP -> replaced
            CaseChange.LOWER -> replaced.lowercase(locale)
            CaseChange.UPPER -> replaced.uppercase(locale)
            CaseChange.TITLE -> titleCase(replaced, locale)
        }
        val trimmed = cased.trim()
        return if (rule.keepExtension && extension.isNotEmpty() && !source.isDirectory) "$trimmed.$extension" else trimmed
    }

    /**
     * Works out the whole batch and everything wrong with it.
     *
     * @param existingNames every name already in the folder. A name about to be vacated by another
     * file in this batch doesn't count as taken, which is what lets `a, b` become `b, a`.
     * @param caseInsensitive true on file systems where `A.txt` and `a.txt` are the same file.
     */
    fun preview(
        sources: List<RenameSource>,
        rule: RenameRule,
        existingNames: (parent: String) -> Set<String>,
        caseInsensitive: Boolean = true,
        locale: Locale = Locale.getDefault()
    ): List<RenamePreview> {
        fun key(parent: String, name: String) = parent + "/" + if (caseInsensitive) name.lowercase(Locale.ROOT) else name
        val newNames = sources.mapIndexed { index, source -> newName(source, index, rule, locale) }
        val counts = HashMap<String, Int>()
        sources.forEachIndexed { index, source ->
            val k = key(source.parent, newNames[index])
            counts[k] = (counts[k] ?: 0) + 1
        }
        val batchKeys = sources.map { key(it.parent, it.name) }.toSet()
        val existing = HashMap<String, Set<String>>()
        return sources.mapIndexed { index, source ->
            val name = newNames[index]
            val problem = when {
                name.isEmpty() || name == "." || name == ".." -> RenameProblem.EMPTY_NAME
                name.any { it == '/' || it == '\u0000' || it in "\\:*?\"<>|" } -> RenameProblem.INVALID_CHARACTER
                name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> RenameProblem.TOO_LONG
                counts.getValue(key(source.parent, name)) > 1 -> RenameProblem.DUPLICATE_IN_BATCH
                name != source.name && key(source.parent, name) != key(source.parent, source.name) -> {
                    val inFolder = existing.getOrPut(source.parent) {
                        existingNames(source.parent).map { if (caseInsensitive) it.lowercase(Locale.ROOT) else it }.toSet()
                    }
                    val candidate = if (caseInsensitive) name.lowercase(Locale.ROOT) else name
                    if (candidate in inFolder && key(source.parent, name) !in batchKeys) RenameProblem.EXISTS else null
                }
                else -> null
            }
            RenamePreview(source, name, problem)
        }
    }

    /** One step of carrying out a batch. */
    data class Move(val fromName: String, val toName: String, val parent: String)

    /**
     * The renames in an order that always works. A name someone else in the batch is about to take
     * goes through a temporary name first, so swaps and shifted numbering can't collide. Renames
     * that only change case go through one too, since a case-insensitive file system sees those as
     * the same file.
     */
    fun plan(previews: List<RenamePreview>, tempName: (Int) -> String = { "._kobblo_rename_$it" }): List<Move> {
        val changing = previews.filter { it.changes && it.problem == null }
        val current = changing.map { it.source.parent to it.source.name.lowercase(Locale.ROOT) }.toSet()
        val direct = ArrayList<Move>()
        val first = ArrayList<Move>()
        val second = ArrayList<Move>()
        changing.forEachIndexed { index, preview ->
            val parent = preview.source.parent
            val collides = (parent to preview.newName.lowercase(Locale.ROOT)) in current
            if (collides) {
                val temp = tempName(index)
                first += Move(preview.source.name, temp, parent)
                second += Move(temp, preview.newName, parent)
            } else {
                direct += Move(preview.source.name, preview.newName, parent)
            }
        }
        return first + direct + second
    }

    /** The moves that put [done] back as it was, in the right order. */
    fun undoPlan(done: List<RenamePreview>, tempName: (Int) -> String = { "._kobblo_rename_$it" }): List<Move> =
        plan(
            done.map { preview ->
                RenamePreview(
                    source = preview.source.copy(name = preview.newName),
                    newName = preview.source.name,
                    problem = null
                )
            },
            tempName
        )

    private fun applyFind(text: String, rule: RenameRule): String {
        if (rule.find.isEmpty()) return text
        val options = if (rule.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return try {
            if (rule.findIsRegex) {
                Regex(rule.find, options).replace(text, rule.replace)
            } else {
                Regex(Regex.escape(rule.find), options).replace(text, Regex.escapeReplacement(rule.replace))
            }
        } catch (_: Exception) {
            // A half-typed pattern changes nothing until it makes sense.
            text
        }
    }

    private fun stemOf(source: RenameSource): String {
        if (source.isDirectory) return source.name
        val dot = source.name.lastIndexOf('.')
        return if (dot > 0) source.name.substring(0, dot) else source.name
    }

    private fun extensionOf(source: RenameSource): String {
        if (source.isDirectory) return ""
        val dot = source.name.lastIndexOf('.')
        return if (dot > 0) source.name.substring(dot + 1) else ""
    }

    private fun formatDate(millis: Long, pattern: String?, locale: Locale): String = try {
        SimpleDateFormat(pattern?.takeIf { it.isNotEmpty() } ?: "yyyy-MM-dd", locale).format(Date(millis))
    } catch (_: IllegalArgumentException) {
        SimpleDateFormat("yyyy-MM-dd", locale).format(Date(millis))
    }

    private fun titleCase(text: String, locale: Locale): String {
        val out = StringBuilder(text.length)
        var atStart = true
        for (c in text) {
            out.append(if (atStart) c.titlecase(locale) else c.lowercase(locale))
            atStart = !c.isLetterOrDigit() && c != '\''
        }
        return out.toString()
    }
}
