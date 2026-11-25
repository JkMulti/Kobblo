package com.kobblo.gallery.file.explorer.startup

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AwaitLastingTest {
    @Test
    fun somethingThatLasts_endsTheWait() = runBlocking {
        val busy = MutableStateFlow(true)

        withTimeout(5_000) { busy.awaitLasting(millis = 1) }
    }

    @Test
    fun nothingGoingOn_keepsWaitingUntilSomethingIs() = runBlocking {
        val busy = MutableStateFlow(false)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { busy.awaitLasting(millis = 1) }

        delay(30)
        assertFalse(waiting.isCompleted)

        busy.value = true
        withTimeout(5_000) { waiting.await() }
    }

    /** Each time the flow is looked at is one look, and the answers below are what it says in turn. */
    @Test
    fun somethingOverWithinTheTime_startsTheWaitAgain() = runBlocking {
        val answers = ArrayDeque(listOf(true, false, true, true))
        var looks = 0
        val busy = flow {
            looks++
            emit(answers.removeFirst())
        }

        withTimeout(5_000) { busy.awaitLasting(millis = 1) }

        assertEquals(4, looks)
    }
}
