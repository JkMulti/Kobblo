package com.kobblo.gallery.file.explorer.transfer.core

import java.io.File
import java.io.IOException

/**
 * Turns a name chosen by someone else into a place on this phone. Everything that arrives over the
 * network goes through here, so a sender can never pick where a file lands.
 */
object ReceivePaths {
    private const val MAX_SEGMENT_BYTES = 240
    private const val MAX_DEPTH = 16
    private const val FALLBACK_NAME = "file"
    private const val NO_MEDIA = ".nomedia"
    private val illegal = Regex("[:*?\"<>|]")
    private val reserved = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /**
     * The safe relative path for [raw], always with at least a file name as its last segment.
     * Folders are kept, because that is how a whole folder travels, but `..`, absolute paths and
     * backslashes come out as plain folders inside the target.
     */
    fun sanitizeRelative(raw: String): List<String> {
        val segments = raw.replace('\\', '/').split('/')
            .mapNotNull { part -> sanitizeSegment(part)?.let { clean -> part to clean } }
        if (segments.isEmpty()) return listOf(FALLBACK_NAME)
        return segments.takeLast(MAX_DEPTH).mapIndexed { depth, (part, clean) ->
            if (depth > 0) restoreLeadingDot(part, clean) else clean
        }
    }

    /**
     * Inside a folder a dotfile keeps its dot, or `.env` and `.git/config` would stop working once the
     * folder arrived. The first segment never gets here, so what the person accepted stays visible.
     */
    private fun restoreLeadingDot(part: String, clean: String): String {
        val dotted = ".$clean"
        if (!part.trim().startsWith('.') || dotted.equals(NO_MEDIA, ignoreCase = true)) return clean
        return truncateKeepingExtension(dotted)
    }

    /** One clean path segment, or null if nothing usable is left of it. */
    fun sanitizeSegment(raw: String): String? {
        val cleaned = raw
            .filterNot(::isInvisibleOrControl)
            .replace(illegal, "_")
            .trim()
            // A leading dot hides a file from the person who just accepted it, and a stray .nomedia
            // would blank the whole Received folder out of the gallery.
            .trimStart('.', ' ')
            .trimEnd('.', ' ')
        if (cleaned.isEmpty()) return null
        val stem = cleaned.substringBefore('.').uppercase()
        val safe = if (stem in reserved) "_$cleaned" else cleaned
        return truncateKeepingExtension(safe)
    }

    /** The name [name] takes for the [n]th clash: `photo.jpg` becomes `photo (2).jpg`. */
    fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }

    /** Where [relative] lands under [root]. Fails if a symlink already there would lead outside it. */
    @Throws(IOException::class)
    fun resolveInside(root: File, relative: List<String>): File {
        val target = File(root, relative.joinToString(File.separator))
        val rootPath = root.canonicalPath
        val targetPath = target.canonicalPath
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
            throw IOException("Path escapes the receive folder")
        }
        return target
    }

    /**
     * Gives [part] its real name in [dir], the first one nothing else has, so nothing that already exists
     * is ever replaced. Only one at a time gets to look for a name, so two uploads of the same name can't
     * both win. Creating an empty file first to hold the name is safer against other apps, but on shared
     * storage each create and rename is a trip through the system's media database, and in a folder of
     * thousands of photos that was a quarter of all the time spent.
     */
    @Throws(IOException::class)
    fun moveToFreeName(part: File, dir: File, name: String): File = synchronized(nameLock) {
        var attempt = 1
        while (attempt < 100_000) {
            val candidate = File(dir, if (attempt == 1) name else numbered(name, attempt))
            // Not exists(), which says no for a link pointing nowhere, and renaming over that would follow it.
            if (!isPresent(candidate)) {
                if (!part.renameTo(candidate)) throw IOException("Couldn't save $name")
                return candidate
            }
            attempt++
        }
        throw IOException("No free name for $name")
    }

    private val nameLock = Any()

    private fun isPresent(file: File): Boolean = java.nio.file.Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)

    // Format characters include the right-to-left override, which can dress `exe.jpg` up as `gpj.exe`.
    private fun isInvisibleOrControl(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> false
    }

    private fun truncateKeepingExtension(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_SEGMENT_BYTES) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        val stem = if (extension.isEmpty()) name else name.substring(0, dot)
        val room = MAX_SEGMENT_BYTES - extension.toByteArray(Charsets.UTF_8).size
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < stem.length) {
            val cp = stem.codePointAt(i)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + size > room) break
            out.appendCodePoint(cp)
            used += size
            i += Character.charCount(cp)
        }
        return out.toString().trimEnd('.', ' ') + extension
    }
}
