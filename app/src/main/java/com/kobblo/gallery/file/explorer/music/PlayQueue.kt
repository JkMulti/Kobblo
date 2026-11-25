package com.kobblo.gallery.file.explorer.music

import kotlin.random.Random

/** A song's place in the queue. The same song can be queued twice, so [uid] tells them apart. */
data class QueueEntry(val uid: Long, val track: MusicTrack)

/**
 * The play queue, in the order it will really play. Shuffle physically reorders it rather than
 * leaving that to the player, so Up Next shows the true order and reordering it simply works. The
 * order to return to lives in [unshuffled] until shuffle is turned off.
 */
data class PlayQueue(
    val entries: List<QueueEntry> = emptyList(),
    val index: Int = -1,
    val unshuffled: List<Long>? = null
) {
    val isShuffled: Boolean get() = unshuffled != null
    val current: QueueEntry? get() = entries.getOrNull(index)
    val upNext: List<QueueEntry> get() = if (index < 0) entries else entries.drop(index + 1)
    val isEmpty: Boolean get() = entries.isEmpty()

    fun at(newIndex: Int): PlayQueue = copy(index = newIndex.coerceIn(entries.indices))

    /** Plays [new] straight after the current song. */
    fun playNext(new: List<QueueEntry>): PlayQueue {
        if (entries.isEmpty()) return copy(entries = new, index = if (new.isEmpty()) -1 else 0)
        val at = index + 1
        val currentUid = current?.uid
        return copy(
            entries = entries.take(at) + new + entries.drop(at),
            unshuffled = unshuffled?.let { order ->
                // Off shuffle, they should still come next rather than wherever they fell.
                val after = order.indexOf(currentUid) + 1
                order.take(after) + new.map { it.uid } + order.drop(after)
            }
        )
    }

    fun append(new: List<QueueEntry>): PlayQueue {
        if (entries.isEmpty()) return copy(entries = new, index = if (new.isEmpty()) -1 else 0)
        return copy(entries = entries + new, unshuffled = unshuffled?.plus(new.map { it.uid }))
    }

    /** Moves one song, keeping [index] on the song that is playing. */
    fun moved(from: Int, to: Int): PlayQueue {
        if (from == to || from !in entries.indices || to !in entries.indices) return this
        val reordered = entries.toMutableList().apply { add(to, removeAt(from)) }
        val newIndex = when {
            index == from -> to
            from < index && to >= index -> index - 1
            from > index && to <= index -> index + 1
            else -> index
        }
        return copy(entries = reordered, index = newIndex)
    }

    /** Removes one song. Removing the current one lands on what followed it, or else the new last. */
    fun removedAt(at: Int): PlayQueue {
        if (at !in entries.indices) return this
        val removedUid = entries[at].uid
        val remaining = entries.toMutableList().apply { removeAt(at) }
        val newIndex = when {
            remaining.isEmpty() -> -1
            at < index -> index - 1
            at == index -> index.coerceAtMost(remaining.lastIndex)
            else -> index
        }
        return copy(
            entries = remaining,
            index = newIndex,
            unshuffled = unshuffled?.minus(removedUid)
        )
    }

    /** Leaves only the song that is playing. */
    fun cleared(): PlayQueue {
        val playing = current ?: return PlayQueue()
        return PlayQueue(entries = listOf(playing), index = 0, unshuffled = unshuffled?.let { listOf(playing.uid) })
    }

    /** Shuffles everything around the current song, which moves to the front and keeps playing. */
    fun shuffled(random: Random = Random.Default): PlayQueue {
        if (isShuffled || entries.isEmpty()) return if (isShuffled) this else copy(unshuffled = emptyList())
        val playing = current
        val rest = entries.filter { it !== playing }.shuffled(random)
        return PlayQueue(
            entries = listOfNotNull(playing) + rest,
            index = if (playing != null) 0 else -1,
            unshuffled = entries.map { it.uid }
        )
    }

    /** Puts the queue back in the order it had, with the current song still current. */
    fun unshuffledBack(): PlayQueue {
        val order = unshuffled ?: return this
        val byUid = entries.associateBy { it.uid }
        val restored = order.mapNotNull { byUid[it] }
        val playing = current
        return PlayQueue(entries = restored, index = restored.indexOfFirst { it.uid == playing?.uid }, unshuffled = null)
    }

    /** Replaces the details of songs already queued, as when a file's tags have finally been read. */
    fun withTrack(uid: Long, track: MusicTrack): PlayQueue =
        copy(entries = entries.map { if (it.uid == uid) it.copy(track = track) else it })

    fun indexOfUid(uid: Long): Int = entries.indexOfFirst { it.uid == uid }
}
