package com.kobblo.gallery.file.explorer.transfer.core

/** How much of what a sender says about itself can be believed. */
enum class SenderTrust {
    /** It signed for a fingerprint this phone already knows. */
    KNOWN,

    /** Nothing ties it to anyone. Every browser, every other LocalSend app, and any device seen for the first time. */
    UNVERIFIED,

    /** It's using the name of a device this phone knows, with a different fingerprint. */
    BORROWED_NAME
}

object PromptRules {
    /** Nobody answering within this long counts as a Reject, and the sender is told. */
    const val ANSWER_WITHIN_MS = 60_000L

    private const val NAMES_SHOWN = 2

    fun trust(proven: Boolean, alias: String, fingerprint: String, saved: List<SavedPeer>): SenderTrust = when {
        PeerMerge.impersonates(alias, fingerprint, saved) -> SenderTrust.BORROWED_NAME
        proven && saved.any { it.fingerprint.equals(fingerprint, ignoreCase = true) } -> SenderTrust.KNOWN
        else -> SenderTrust.UNVERIFIED
    }

    /**
     * The first name or two of what's coming, as a sender wrote them. A name is whatever it says it is, so it's
     * cleaned the same way a device's is: a right-to-left override can make an app look like a photo. One with no name
     * left is called [unnamed].
     */
    fun firstNames(names: List<String>, unnamed: String): List<String> =
        names.take(NAMES_SHOWN).map { PeerNames.clean(it.substringAfterLast('/'), fallback = unnamed) }

    fun moreThanShown(count: Int): Int = (count - NAMES_SHOWN).coerceAtLeast(0)
}

/**
 * One question on the screen at a time, and the rest waiting their turn. Each is only ever answered once: taking it
 * out is what decides who gets to answer it, so a tap and a timeout landing together can't both count.
 */
class PromptQueue<T : Any>(private val idOf: (T) -> String) {
    private val waiting = ArrayDeque<T>()

    var current: T? = null
        private set

    /** True if this is now the question on the screen. */
    @Synchronized
    fun add(item: T): Boolean {
        if (current == null) {
            current = item
            return true
        }
        waiting.addLast(item)
        return false
    }

    /** Takes out the question with this id, wherever it is, or nothing if it's already been answered. */
    @Synchronized
    fun take(id: String): T? {
        current?.takeIf { idOf(it) == id }?.let {
            current = null
            return it
        }
        val index = waiting.indexOfFirst { idOf(it) == id }
        return if (index >= 0) waiting.removeAt(index) else null
    }

    /** Puts the next one up, if nothing is up now. */
    @Synchronized
    fun promote(): T? {
        if (current != null) return null
        current = waiting.removeFirstOrNull()
        return current
    }
}
