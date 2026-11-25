package com.kobblo.gallery.file.explorer.localsend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun request(id: String, vararg sizes: Long, kind: PromptKind = PromptKind.FILES) = IncomingTransferRequest(
    sessionId = id,
    sender = DeviceDto(alias = "Asha", deviceModel = "Pixel", deviceType = null, fingerprint = "AAAA", port = 53318),
    senderIp = "10.0.0.2",
    files = sizes.mapIndexed { index, size ->
        TransferFileItem(fileId = "f$index", fileName = "f$index.bin", size = size, fileType = "application/octet-stream")
    },
    totalBytes = sizes.sum(),
    decisionChannel = CompletableDeferred(),
    kind = kind
)

class IncomingPictureTest {
    private var now = 1_000L
    private val card = MutableStateFlow<TransferSession?>(null)
    private val picture = IncomingPicture(card) { now }

    @Test fun whoeverAskedBecomesADeviceOnTheCard() {
        val peer = request("s1", 10).senderAsPeer()

        assertEquals("10.0.0.2", peer.ip)
        assertEquals(53318, peer.port)
        assertEquals("Asha", peer.alias)
        assertEquals("Pixel", peer.deviceModel)
        assertEquals("mobile", peer.deviceType)
        assertEquals("https", peer.protocol)
        assertEquals("AAAA", peer.fingerprint)
    }

    @Test fun anAcceptedRequestGoesOnTheCardAsUnderWay() {
        picture.begin(request("s1", 10, 30))

        val shown = card.value!!
        assertEquals("s1", shown.sessionId)
        assertTrue(shown.isIncoming)
        assertEquals(TransferStatus.IN_PROGRESS, shown.status)
        assertEquals(40L, shown.totalBytes)
        assertEquals(0L, shown.bytesTransferred)
        assertEquals(2, shown.files.size)
    }

    @Test fun progressAddsUpAcrossFilesArrivingAtOnce() {
        picture.begin(request("s1", 100, 100))

        picture.progress("s1", "f0", 40)
        now += 200
        picture.progress("s1", "f1", 30)

        assertEquals(70L, card.value!!.bytesTransferred)
        assertEquals(1, card.value!!.currentFileIndex)
        assertEquals(30L, card.value!!.currentFileBytes)

        now += 200
        picture.progress("s1", "f0", 90)
        assertEquals(120L, card.value!!.bytesTransferred)
        assertEquals(0, card.value!!.currentFileIndex)
    }

    @Test fun theCardChangesNoMoreOftenThanAScreenCanUse() {
        picture.begin(request("s1", 100))

        picture.progress("s1", "f0", 10)
        now += 10
        picture.progress("s1", "f0", 20)
        assertEquals(10L, card.value!!.bytesTransferred)

        now += 200
        picture.progress("s1", "f0", 30)
        assertEquals(30L, card.value!!.bytesTransferred)
    }

    @Test fun progressForSomeOtherSessionChangesNothing() {
        picture.begin(request("s1", 100))

        picture.progress("old", "f0", 50)

        assertEquals(0L, card.value!!.bytesTransferred)
    }

    @Test fun aSavedFileIsMarkedDoneWithWhereItWent() {
        picture.begin(request("s1", 10, 30))

        picture.fileSaved("s1", "f1", File("/storage/Received/f1.bin"))

        val files = card.value!!.files
        assertFalse(files[0].isFinished)
        assertTrue(files[1].isFinished)
        assertEquals(30L, files[1].bytesTransferred)
        assertEquals(File("/storage/Received/f1.bin").absolutePath, files[1].localPath)
    }

    @Test fun endingReturnsTheSessionAsItEndedOnce() {
        picture.begin(request("s1", 10, 30))
        picture.progress("s1", "f0", 10)

        val ended = picture.end("s1", TransferStatus.COMPLETED, null, allArrived = true)!!

        assertEquals(TransferStatus.COMPLETED, ended.status)
        assertEquals(40L, ended.bytesTransferred)
        assertSame(ended, card.value)
        assertNull(picture.end("s1", TransferStatus.FAILED, "late"))
        assertEquals(TransferStatus.COMPLETED, card.value!!.status)
    }

    @Test fun aFailureKeepsTheCountWhereItStoppedAndSaysWhy() {
        picture.begin(request("s1", 10, 30))
        picture.progress("s1", "f0", 7)

        val ended = picture.end("s1", TransferStatus.FAILED, "Asha stopped sending")!!

        assertEquals(7L, ended.bytesTransferred)
        assertEquals("Asha stopped sending", ended.errorMessage)
    }

    @Test fun endingASessionThatIsNotOnTheCardDoesNothingToTheCard() {
        picture.begin(request("s1", 10))

        assertNull(picture.end("other", TransferStatus.CANCELLED, null))
        assertEquals(TransferStatus.IN_PROGRESS, card.value!!.status)
    }

    @Test fun nothingMovesOnTheCardOnceItHasEnded() {
        picture.begin(request("s1", 100))
        picture.end("s1", TransferStatus.CANCELLED, null)

        now += 500
        picture.progress("s1", "f0", 50)

        assertEquals(0L, card.value!!.bytesTransferred)
        assertEquals(TransferStatus.CANCELLED, card.value!!.status)
    }
}

class PromptDeskTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 5_000L
    private val timedOut = CopyOnWriteArrayList<String>()

    private fun desk(answerWithinMs: Long = 60_000, onTimeUp: (String) -> Unit = { timedOut += it }) =
        PromptDesk(scope, { now }, answerWithinMs, onTimeUp)

    @After fun stop() {
        scope.cancel()
    }

    @Test fun theFirstQuestionGoesStraightOnTheScreen() {
        val desk = desk()
        val first = request("q1", 10)

        desk.ask(first)

        assertSame(first, desk.onScreen.value)
        assertTrue(desk.isOnScreen("q1"))
        assertEquals(60_000L, desk.timeLeftMs)
        now += 20_000
        assertEquals(40_000L, desk.timeLeftMs)
    }

    @Test fun aSecondQuestionWaitsItsTurnAndGetsItsFullTime() {
        val desk = desk()
        val first = request("q1", 10)
        val second = request("q2", 20, kind = PromptKind.BROWSER_OPEN)
        desk.ask(first)
        desk.ask(second)
        assertSame(first, desk.onScreen.value)

        now += 45_000
        assertSame(first, desk.settle("q1"))

        assertSame(second, desk.onScreen.value)
        assertEquals(60_000L, desk.timeLeftMs)
    }

    @Test fun aQuestionIsSettledOnlyOnce() {
        val desk = desk()
        val first = request("q1", 10)
        desk.ask(first)

        assertSame(first, desk.settle("q1"))
        assertNull(desk.settle("q1"))
        assertNull(desk.onScreen.value)
        assertNull(desk.settle("never asked"))
    }

    @Test fun aQuestionCanBeWithdrawnWhileItIsStillWaitingInLine() {
        val desk = desk()
        val first = request("q1", 10)
        val second = request("q2", 20)
        val third = request("q3", 30)
        desk.ask(first)
        desk.ask(second)
        desk.ask(third)

        assertSame(second, desk.settle("q2"))
        assertSame(first, desk.onScreen.value)

        desk.settle("q1")
        assertSame(third, desk.onScreen.value)
    }

    @Test fun whenNobodyAnswersInTimeTheOwnerIsToldWhichQuestion() {
        val told = CountDownLatch(1)
        val desk = desk(answerWithinMs = 50) {
            timedOut += it
            told.countDown()
        }
        desk.ask(request("q1", 10))

        assertTrue(told.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("q1"), timedOut)
        // Running out of time doesn't settle it: whoever is told does that, and may find a tap got there first.
        assertTrue(desk.isOnScreen("q1"))
    }

    @Test fun anAnsweredQuestionsClockIsStopped() {
        val desk = desk(answerWithinMs = 100)
        desk.ask(request("q1", 10))
        desk.settle("q1")

        Thread.sleep(400)

        assertTrue(timedOut.isEmpty())
    }
}
