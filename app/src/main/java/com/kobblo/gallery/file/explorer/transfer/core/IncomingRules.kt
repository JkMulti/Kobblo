package com.kobblo.gallery.file.explorer.transfer.core

import java.io.File
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.abs

/** Decisions about what's offered, kept apart from the server so they can be tested without a network. */
object IncomingRules {
    /** Two filesystems round a time differently, and FAT rounds to two seconds. */
    private const val SAME_TIME_MS = 2_000L

    /** What the protocol calls a message: one text file, with its whole content in the preview. */
    fun isNote(fileCount: Int, fileType: String?, preview: String?): Boolean =
        fileCount == 1 && preview != null && fileType.orEmpty().startsWith("text/", ignoreCase = true)

    fun parseModified(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .getOrNull()
    }

    /**
     * True when the offered file is already at its place from an earlier try, so it isn't saved a second
     * time as `photo (2).jpg`. It takes a matching length and time: with no time to compare, it can't
     * tell, and a copy is better than quietly not saving something new.
     */
    fun alreadyHave(existing: File, size: Long, modifiedMs: Long?): Boolean {
        if (modifiedMs == null) return false
        if (!existing.isFile || existing.length() != size) return false
        return abs(existing.lastModified() - modifiedMs) <= SAME_TIME_MS
    }

    private val urlPattern = Regex("""\bhttps?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    /**
     * The link in a note, to offer opening when someone taps for it. Only web addresses qualify:
     * `intent:`, `javascript:` and `content:` links can do far more than show a page.
     */
    fun firstLink(text: String): String? {
        val found = urlPattern.find(text)?.value?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}') ?: return null
        val host = runCatching { URI(found).host }.getOrNull()
        return found.takeIf { !host.isNullOrBlank() }
    }

    /** The host, for saying plainly where a link leads. */
    fun linkHost(link: String): String = runCatching { URI(link).host }.getOrNull().orEmpty()
}

/**
 * Remembers what was just accepted from whom, so that picking up after a dropped connection doesn't
 * make someone come back to their phone and accept the same files again. It only ever covers files
 * already accepted, from the same device, for a little while, and only until that transfer is over.
 */
class ResumeGrants(private val windowMs: Long = 30 * 60 * 1000L, private val maxEntries: Int = 8) {
    private class Grant(val who: String, val files: Set<String>, val timeMs: Long)

    private val grants = ArrayList<Grant>()

    @Synchronized
    fun remember(who: String, files: Collection<Pair<String, Long>>, nowMs: Long) {
        if (grants.size >= maxEntries) grants.removeAt(0)
        grants.add(Grant(who, files.mapTo(HashSet()) { key(it.first, it.second) }, nowMs))
    }

    /** It all arrived, or this phone stopped it. Sending the same thing again is a new question. */
    @Synchronized
    fun forget(who: String) {
        grants.removeAll { it.who == who }
    }

    @Synchronized
    fun covers(who: String, files: Collection<Pair<String, Long>>, nowMs: Long): Boolean {
        grants.removeAll { nowMs - it.timeMs > windowMs }
        if (files.isEmpty()) return false
        return grants.any { grant -> grant.who == who && files.all { key(it.first, it.second) in grant.files } }
    }

    private fun key(name: String, size: Long) = "$size:$name"
}

/** Says a sender is sending too often, after being told no. Keeps a stranger from bombing the screen with prompts. */
class PromptCooldown(private val cooldownMs: Long = 10_000L, private val maxEntries: Int = 64) {
    private val until = LinkedHashMap<String, Long>()

    @Synchronized
    fun declined(address: String, nowMs: Long) {
        if (until.size >= maxEntries) until.remove(until.keys.first())
        until[address] = nowMs + cooldownMs
    }

    @Synchronized
    fun isCooling(address: String, nowMs: Long): Boolean {
        val end = until[address] ?: return false
        if (nowMs >= end) {
            until.remove(address)
            return false
        }
        return true
    }
}
