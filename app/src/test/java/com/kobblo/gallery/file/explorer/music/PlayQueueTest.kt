package com.kobblo.gallery.file.explorer.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlayQueueTest {
    private fun entry(n: Long) = QueueEntry(n, MusicTrack(path = "/m/$n.mp3", title = "$n"))
    private fun queue(size: Int, index: Int = 0) = PlayQueue((1L..size).map(::entry), index)
    private val PlayQueue.uids get() = entries.map { it.uid }

    @Test
    fun playNextGoesRightAfterTheCurrentSong() {
        val q = queue(3, index = 1).playNext(listOf(entry(9)))
        assertEquals(listOf(1L, 2L, 9L, 3L), q.uids)
        assertEquals(1, q.index)
    }

    @Test
    fun playNextKeepsTheirOrder() {
        val q = queue(2).playNext(listOf(entry(8), entry(9)))
        assertEquals(listOf(1L, 8L, 9L, 2L), q.uids)
    }

    @Test
    fun appendGoesLast() {
        val q = queue(2).append(listOf(entry(9)))
        assertEquals(listOf(1L, 2L, 9L), q.uids)
    }

    @Test
    fun addingToAnEmptyQueueStartsIt() {
        assertEquals(0, PlayQueue().append(listOf(entry(1))).index)
        assertEquals(0, PlayQueue().playNext(listOf(entry(1))).index)
    }

    @Test
    fun movingUpcomingSongsLeavesTheCurrentOneAlone() {
        val q = queue(5, index = 1).moved(4, 2)
        assertEquals(listOf(1L, 2L, 5L, 3L, 4L), q.uids)
        assertEquals(2L, q.current?.uid)
    }

    @Test
    fun movingTheCurrentSongFollowsIt() {
        val q = queue(4, index = 1).moved(1, 3)
        assertEquals(listOf(1L, 3L, 4L, 2L), q.uids)
        assertEquals(3, q.index)
        assertEquals(2L, q.current?.uid)
    }

    @Test
    fun movingASongAcrossTheCurrentOneShiftsIt() {
        val down = queue(4, index = 2).moved(0, 3)
        assertEquals(listOf(2L, 3L, 4L, 1L), down.uids)
        assertEquals(3L, down.current?.uid)

        val up = queue(4, index = 1).moved(3, 0)
        assertEquals(listOf(4L, 1L, 2L, 3L), up.uids)
        assertEquals(2L, up.current?.uid)
    }

    @Test
    fun anInvalidMoveChangesNothing() {
        val q = queue(3)
        assertSame(q, q.moved(1, 1))
        assertSame(q, q.moved(0, 9))
        assertSame(q, q.moved(-1, 0))
    }

    @Test
    fun removingABeforeOrAfterSongKeepsTheCurrentOne() {
        val before = queue(4, index = 2).removedAt(0)
        assertEquals(3L, before.current?.uid)
        val after = queue(4, index = 2).removedAt(3)
        assertEquals(3L, after.current?.uid)
    }

    @Test
    fun removingTheCurrentSongLandsOnTheNext() {
        val q = queue(3, index = 1).removedAt(1)
        assertEquals(listOf(1L, 3L), q.uids)
        assertEquals(3L, q.current?.uid)
    }

    @Test
    fun removingTheLastCurrentSongLandsOnTheNewLast() {
        val q = queue(3, index = 2).removedAt(2)
        assertEquals(2L, q.current?.uid)
    }

    @Test
    fun removingTheOnlySongEmptiesIt() {
        val q = queue(1).removedAt(0)
        assertTrue(q.isEmpty)
        assertNull(q.current)
    }

    @Test
    fun clearingLeavesOnlyTheCurrentSong() {
        val q = queue(4, index = 2).cleared()
        assertEquals(listOf(3L), q.uids)
        assertEquals(0, q.index)
    }

    @Test
    fun upNextIsWhatFollows() {
        assertEquals(listOf(3L, 4L), queue(4, index = 1).upNext.map { it.uid })
        assertTrue(queue(2, index = 1).upNext.isEmpty())
    }

    @Test
    fun shuffleKeepsEverySongAndPutsTheCurrentOneFirst() {
        val q = queue(50, index = 7).shuffled(Random(1))
        assertTrue(q.isShuffled)
        assertEquals(0, q.index)
        assertEquals(8L, q.current?.uid)
        assertEquals((1L..50L).toSet(), q.uids.toSet())
        assertEquals(50, q.uids.size)
    }

    @Test
    fun shuffleReallyChangesTheOrder() {
        assertFalse(queue(50).shuffled(Random(1)).uids == (1L..50L).toList())
    }

    @Test
    fun turningShuffleOffRestoresTheOrderAroundTheSameSong() {
        val shuffled = queue(20, index = 7).shuffled(Random(2))
        val back = shuffled.at(5).unshuffledBack()
        assertFalse(back.isShuffled)
        assertEquals((1L..20L).toList(), back.uids)
        assertEquals(shuffled.entries[5].uid, back.current?.uid)
    }

    @Test
    fun songsAddedWhileShuffledSurviveTurningItOff() {
        val shuffled = queue(4, index = 1).shuffled(Random(3))
        val added = shuffled.playNext(listOf(entry(8))).append(listOf(entry(9)))
        val back = added.unshuffledBack()
        // Play Next stays next to the current song, and the appended one stays last.
        assertEquals(listOf(1L, 2L, 8L, 3L, 4L, 9L), back.uids)
        assertEquals(2L, back.current?.uid)
    }

    @Test
    fun songsRemovedWhileShuffledStayGone() {
        val shuffled = queue(4).shuffled(Random(4))
        val without = shuffled.removedAt(shuffled.entries.indexOfFirst { it.uid == 3L })
        assertEquals(listOf(1L, 2L, 4L), without.unshuffledBack().uids)
    }

    @Test
    fun shufflingTwiceKeepsTheOriginalOrderToReturnTo() {
        val once = queue(10).shuffled(Random(5))
        assertSame(once, once.shuffled(Random(6)))
    }

    @Test
    fun theSameSongCanBeQueuedTwice() {
        val twice = queue(2).append(listOf(QueueEntry(5, MusicTrack(path = "/m/1.mp3", title = "1"))))
        assertEquals(1, twice.entries.count { it.uid == 1L })
        assertEquals(2, twice.entries.count { it.track.path == "/m/1.mp3" })
        assertEquals(1, twice.removedAt(2).entries.count { it.track.path == "/m/1.mp3" })
    }

    @Test
    fun shuffleOnAnEmptyQueueIsRemembered() {
        val q = PlayQueue().shuffled()
        assertTrue(q.isShuffled)
        assertFalse(q.unshuffledBack().isShuffled)
    }

    @Test
    fun tagsCanBeFilledInLater() {
        val q = queue(2).withTrack(2, MusicTrack(path = "/m/2.mp3", title = "Real Title"))
        assertEquals("Real Title", q.entries[1].track.title)
        assertEquals("1", q.entries[0].track.title)
    }
}
