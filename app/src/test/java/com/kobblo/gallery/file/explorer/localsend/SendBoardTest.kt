package com.kobblo.gallery.file.explorer.localsend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendBoardTest {
    private val peer = NearbyPeer("10.0.0.2", 53318, "Asha", null, "mobile", "https", "AAAA", 0)
    private val shown = ArrayList<TransferSession>()

    private fun item(id: String, size: Long, finished: Boolean = false) =
        TransferFileItem(fileId = id, fileName = "$id.bin", size = size, fileType = "application/octet-stream", isFinished = finished)

    private fun board(items: List<TransferFileItem>, carried: List<TransferFileItem> = emptyList()) =
        SendBoard(peer, carried, items) { shown += it }

    private val last get() = shown.last()

    @Test fun itStartsByShowingEverythingThatIsToGo() {
        board(listOf(item("a", 10), item("b", 30)))

        assertEquals(1, shown.size)
        assertEquals(TransferStatus.INITIALIZING, last.status)
        assertFalse(last.isIncoming)
        assertEquals(listOf("a", "b"), last.files.map { it.fileId })
        assertEquals(40L, last.totalBytes)
        assertEquals(0L, last.bytesTransferred)
    }

    @Test fun whatArrivedOnAnEarlierTryCountsFromTheStart() {
        board(listOf(item("b", 30)), carried = listOf(item("a", 10, finished = true)))

        assertEquals(listOf("a", "b"), last.files.map { it.fileId })
        assertEquals(40L, last.totalBytes)
        assertEquals(10L, last.bytesTransferred)
        assertEquals(1, last.finishedFiles)
    }

    @Test fun aFirstTryWaitsForAnAnswerAndARetryIsStillReconnecting() {
        board(listOf(item("a", 10))).awaitingAnswer(isRetry = false)
        assertEquals(TransferStatus.WAITING_ACCEPT, last.status)

        board(listOf(item("a", 10))).awaitingAnswer(isRetry = true)
        assertEquals(TransferStatus.RECONNECTING, last.status)
    }

    @Test fun onceAcceptedItGoesByTheReceiversNameForTheSession() {
        val board = board(listOf(item("a", 10)))
        val own = last.sessionId

        board.accepted("remote-1")

        assertNotEquals("remote-1", own)
        assertEquals("remote-1", last.sessionId)
        assertEquals(TransferStatus.IN_PROGRESS, last.status)
    }

    @Test fun sortingTellsWhatToUploadFromWhatIsThereAlreadyAndWhatWasNotTaken() {
        val board = board(
            listOf(item("there", 5), item("refused", 7), item("go", 11)),
            carried = listOf(item("old", 3, finished = true))
        )

        val uploads = board.sort(tokens = mapOf("go" to "t-go", "there" to "t-there"), alreadyThere = setOf("there"), notAccepted = "not taken")

        assertEquals(listOf("go"), uploads.map { it.item.fileId })
        assertEquals(3, uploads.single().index)
        assertEquals("t-go", uploads.single().token)

        val there = last.files[1]
        assertTrue(there.isFinished && there.alreadyThere)
        assertEquals(5L, there.bytesTransferred)
        val refused = last.files[2]
        assertFalse(refused.isFinished)
        assertEquals("not taken", refused.failure)
        assertNull(last.files[3].failure)
        // What's there already has been counted. What wasn't taken is counted too, with the next change.
        assertEquals(8L, last.bytesTransferred)
    }

    @Test fun progressAddsUpAcrossFilesGoingAtOnce() {
        val board = board(listOf(item("a", 100), item("b", 100)))
        board.accepted("r")
        val uploads = board.sort(mapOf("a" to "ta", "b" to "tb"), emptySet(), "")

        assertEquals(40L, board.moved("a", 40, 100, nowMs = 1_000))
        assertEquals(70L, board.moved("b", 30, 100, nowMs = 1_200))
        assertEquals(70L, last.bytesTransferred)
        assertEquals(30L, last.currentFileBytes)

        board.sent(uploads[0])
        assertEquals(130L, last.bytesTransferred)
        assertTrue(last.files[0].isFinished)
        assertEquals(100L, last.files[0].bytesTransferred)
        assertEquals(100L, last.currentFileBytes)
    }

    @Test fun theScreenIsNotToldMoreOftenThanItCanUseButAlwaysOfAFilesEnd() {
        val board = board(listOf(item("a", 100)))
        board.accepted("r")
        board.sort(mapOf("a" to "ta"), emptySet(), "")
        val before = shown.size

        board.moved("a", 10, 100, nowMs = 1_000)
        board.moved("a", 20, 100, nowMs = 1_010)
        board.moved("a", 30, 100, nowMs = 1_020)
        assertEquals(before + 1, shown.size)
        assertEquals(10L, last.bytesTransferred)

        board.moved("a", 100, 100, nowMs = 1_030)
        assertEquals(before + 2, shown.size)
        assertEquals(100L, last.bytesTransferred)
    }

    @Test fun aFileThisPhoneCannotReadIsPassedOverAndTheRestGoOn() {
        val board = board(listOf(item("a", 10), item("b", 30)))
        board.accepted("r")
        val uploads = board.sort(mapOf("a" to "ta", "b" to "tb"), emptySet(), "")

        board.moved("a", 4, 10, nowMs = 1_000)
        board.unreadable(uploads[0], "can't read")

        assertEquals("can't read", last.files[0].failure)
        assertFalse(last.files[0].isFinished)
        // It counts as passed, so the bar can still reach the end.
        assertEquals(10L, last.bytesTransferred)
        assertEquals(TransferStatus.IN_PROGRESS, last.status)
        assertEquals("can't read", board.whyIncomplete)
    }

    @Test fun aRefusalIsRememberedAsWhyItStoppedAndShownOnlyWhenItEnds() {
        val board = board(listOf(item("a", 10), item("b", 30)))
        board.accepted("r")
        val uploads = board.sort(mapOf("a" to "ta", "b" to "tb"), emptySet(), "")
        val before = shown.size

        board.unreadable(uploads[0], "can't read")
        board.refused(uploads[1], "stopped by Asha")
        assertEquals(before + 1, shown.size)
        assertEquals("stopped by Asha", board.whyIncomplete)

        val ended = board.finish(TransferStatus.FAILED, "1 of 2")
        assertEquals("stopped by Asha", ended.files[1].failure)
        assertEquals(TransferStatus.FAILED, ended.status)
        assertEquals("1 of 2", ended.errorMessage)
        assertFalse(ended.connectionLost)
    }

    @Test fun aFileThatStopsWithoutAnAnswerNoLongerCounts() {
        val board = board(listOf(item("a", 100), item("b", 100)))
        board.accepted("r")
        val uploads = board.sort(mapOf("a" to "ta", "b" to "tb"), emptySet(), "")

        board.moved("a", 60, 100, nowMs = 1_000)
        board.dropped(uploads[0])

        assertEquals(10L, board.moved("b", 10, 100, nowMs = 2_000))
    }

    @Test fun aStallIsLaidOverWhatWasLastShown() {
        val board = board(listOf(item("a", 100)))
        board.accepted("r")
        board.sort(mapOf("a" to "ta"), emptySet(), "")
        board.moved("a", 10, 100, nowMs = 1_000)
        board.moved("a", 60, 100, nowMs = 2_000)
        assertTrue(last.speedBytesPerSec > 0)

        board.stalled()

        assertTrue(last.waitingOnNetwork)
        assertEquals(0L, last.speedBytesPerSec)
        assertEquals(60L, last.bytesTransferred)

        // The next byte that moves clears it again.
        board.moved("a", 100, 100, nowMs = 9_000)
        assertFalse(last.waitingOnNetwork)
    }

    @Test fun completingSquaresTheCountUpToTheTotal() {
        val board = board(listOf(item("a", 10), item("b", 30)))
        board.accepted("r")
        val uploads = board.sort(mapOf("a" to "ta", "b" to "tb"), emptySet(), "")
        uploads.forEach(board::sent)
        assertEquals(2, board.sentCount)
        assertEquals(2, board.fileCount)

        val ended = board.completed()

        assertEquals(TransferStatus.COMPLETED, ended.status)
        assertEquals(40L, ended.bytesTransferred)
        assertEquals(ended, last)
    }

    @Test fun nothingLeftToSendMeansEveryFileIsThere() {
        val board = board(listOf(item("note", 2)), carried = listOf(item("old", 3, finished = true)))
        board.awaitingAnswer(isRetry = false)

        val ended = board.nothingLeftToSend()

        assertEquals(TransferStatus.COMPLETED, ended.status)
        assertTrue(ended.files.all { it.isFinished })
        assertEquals(2L, ended.files[1].bytesTransferred)
        assertEquals(5L, ended.bytesTransferred)
        // It never got a name from the receiver, so it keeps its own.
        assertEquals(shown.first().sessionId, ended.sessionId)
    }

    @Test fun aLostConnectionIsMarkedSoItCanBeTriedAgain() {
        val board = board(listOf(item("a", 10)))

        val ended = board.finish(TransferStatus.FAILED, "lost", connectionLost = true)

        assertTrue(ended.connectionLost)
        assertEquals(TransferStatus.FAILED, board.status)
    }
}
