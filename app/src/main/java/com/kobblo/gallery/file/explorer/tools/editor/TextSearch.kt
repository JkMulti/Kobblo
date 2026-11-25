package com.kobblo.gallery.file.explorer.tools.editor

/** Finding and replacing in a text. Works on plain strings, so the rules can be tested off the device. */
object TextSearch {
    data class Options(val matchCase: Boolean = false, val wholeWord: Boolean = false, val regex: Boolean = false)

    /** A broken pattern while typing is expected, so it reports no matches rather than failing. */
    fun compile(query: String, options: Options): Regex? {
        if (query.isEmpty()) return null
        val pattern = buildString {
            if (options.wholeWord) append("\\b")
            append(if (options.regex) query else Regex.escape(query))
            if (options.wholeWord) append("\\b")
        }
        return try {
            if (options.matchCase) Regex(pattern) else Regex(pattern, setOf(RegexOption.IGNORE_CASE))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Every match as a range, up to [limit]. Zero-length matches, which a regex can make, are skipped.
     *
     * This keeps one Matcher rather than walking `Regex.find().next()`: each `next()` makes a new
     * Matcher, and on Android that copies the whole text, which took minutes at a megabyte.
     */
    fun findAll(text: CharSequence, regex: Regex, limit: Int = 100_000): List<IntRange> {
        val found = ArrayList<IntRange>()
        val matcher = regex.toPattern().matcher(text)
        while (found.size < limit && matcher.find()) {
            if (matcher.end() > matcher.start()) found += matcher.start() until matcher.end()
        }
        return found
    }

    /** The match to go to from [caret]: the next one after it, wrapping around, or the one before. */
    fun nearest(matches: List<IntRange>, caret: Int, forward: Boolean): Int {
        if (matches.isEmpty()) return -1
        return if (forward) {
            val index = matches.indexOfFirst { it.first >= caret }
            if (index >= 0) index else 0
        } else {
            val index = matches.indexOfLast { it.first < caret }
            if (index >= 0) index else matches.lastIndex
        }
    }

    /** What a match becomes. A regex replacement can refer to its groups as `$1`. */
    fun replacementFor(text: CharSequence, range: IntRange, regex: Regex, replacement: String, options: Options): String {
        if (!options.regex) return replacement
        val matcher = regex.toPattern().matcher(text)
        return if (matcher.find(range.first)) expand(matcher, replacement) else replacement
    }

    /** Every match and what it becomes, in order, for applying one at a time. */
    fun planReplacements(text: CharSequence, regex: Regex, replacement: String, options: Options): List<Pair<IntRange, String>> {
        val plan = ArrayList<Pair<IntRange, String>>()
        val matcher = regex.toPattern().matcher(text)
        while (matcher.find()) {
            if (matcher.end() > matcher.start()) {
                plan += (matcher.start() until matcher.end()) to if (options.regex) expand(matcher, replacement) else replacement
            }
        }
        return plan
    }

    /** Fills in `$1` style groups and `\\n` style escapes. */
    private fun expand(matcher: java.util.regex.Matcher, replacement: String): String = buildString {
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            if (c == '$' && i + 1 < replacement.length && replacement[i + 1].isDigit()) {
                val group = replacement[i + 1].digitToInt()
                if (group <= matcher.groupCount()) append(matcher.group(group).orEmpty())
                i += 2
            } else if (c == '\\' && i + 1 < replacement.length) {
                when (val next = replacement[i + 1]) {
                    'n' -> append('\n')
                    't' -> append('\t')
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
