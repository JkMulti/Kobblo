package com.kobblo.gallery.file.explorer.ui.root

/** How numbers are written into the names of files renamed together. [word] is what the rename is told, so it's never translated. */
internal enum class Numbering(val word: String) {
    None("None"),
    InBrackets("(1)"),
    Digits("0001")
}

/** What files renamed together are to be called: all alike but for a number that goes up by one each time. */
internal data class BatchNames(
    val base: String,
    val extension: String,
    val numbering: Numbering = Numbering.None,
    /** Whether the number comes before the name rather than after it. */
    val numberFirst: Boolean = false,
    val start: Int = 1,
    /** How many digits a number is filled out to with zeros. */
    val digits: Int = 0
) {
    /** The name of the file this far down the list, counting from 0. */
    fun nameAt(index: Int): String {
        val numbered = when (numbering) {
            Numbering.None -> base
            Numbering.InBrackets -> joined("(${numberAt(index)})", " ")
            Numbering.Digits -> joined(numberAt(index), "_")
        }
        val ending = if (extension.isEmpty()) "" else "." + extension.removePrefix(".")
        return (numbered + ending).trim()
    }

    private fun numberAt(index: Int): String = (start + index).toString().padStart(digits.coerceAtLeast(0), '0')

    private fun joined(number: String, gap: String): String =
        if (numberFirst) number + gap + base else base + gap + number
}

/** A file's name as the rename sheet starts with it: without its extension, which has a field of its own. */
internal fun baseNameOf(name: String): String = name.substringBeforeLast(".")

/** A file's extension as the rename sheet starts with it, or nothing. */
internal fun extensionFieldOf(name: String): String = name.substringAfterLast(".", missingDelimiterValue = "")
