package com.kobblo.gallery.file.explorer.localsend

import com.kobblo.gallery.file.explorer.transfer.core.PromptQueue
import com.kobblo.gallery.file.explorer.transfer.core.PromptRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The questions waiting on the person holding the phone: someone wants to send files, a note, or to use the
 * Web Share link. One is on the screen at a time, and the rest wait their turn behind it.
 *
 * Each question gets the same time to be answered, counted from when it reaches the screen and not from when
 * it was asked, so one that waited in line isn't short-changed. And each is settled exactly once: a tap, the
 * clock and the asker hanging up can all arrive together, and whichever takes the question out first is the
 * one that counts.
 *
 * @param uptimeMs a clock that never jumps.
 * @param onTimeUp nobody answered the question with this id in time. It hasn't been settled yet.
 */
internal class PromptDesk(
    private val scope: CoroutineScope,
    private val uptimeMs: () -> Long,
    private val answerWithinMs: Long = PromptRules.ANSWER_WITHIN_MS,
    private val onTimeUp: (sessionId: String) -> Unit
) {
    private val line = PromptQueue<IncomingTransferRequest> { it.sessionId }
    private val _onScreen = MutableStateFlow<IncomingTransferRequest?>(null)
    private var clock: Job? = null

    @Volatile
    private var answerByMs = 0L

    /** The question to show, if there is one. */
    val onScreen: StateFlow<IncomingTransferRequest?> = _onScreen.asStateFlow()

    /** How long whoever is looking at the question has left to answer it. */
    val timeLeftMs: Long get() = answerByMs - uptimeMs()

    fun isOnScreen(sessionId: String): Boolean = _onScreen.value?.sessionId == sessionId

    /** Shows [request] now if nothing else is up, or once everything ahead of it has been answered. */
    fun ask(request: IncomingTransferRequest) {
        synchronized(line) {
            if (line.add(request)) putUp(request)
        }
    }

    /**
     * Ends the question with this id and returns it, for the caller to act on. Null if something else ended it
     * first, in which case there's nothing left to do. If it was the one on the screen, the next one goes up.
     */
    fun settle(sessionId: String): IncomingTransferRequest? = synchronized(line) {
        val settled = line.take(sessionId) ?: return null
        if (isOnScreen(sessionId)) {
            clock?.cancel()
            _onScreen.value = null
            line.promote()?.let(::putUp)
        }
        settled
    }

    private fun putUp(request: IncomingTransferRequest) {
        clock?.cancel()
        answerByMs = uptimeMs() + answerWithinMs
        _onScreen.value = request
        clock = scope.launch {
            delay(answerWithinMs)
            onTimeUp(request.sessionId)
        }
    }
}
