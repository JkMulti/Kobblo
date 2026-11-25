package com.kobblo.gallery.file.explorer.transfer

import com.kobblo.gallery.file.explorer.transfer.core.PromptQueue
import com.kobblo.gallery.file.explorer.transfer.core.PromptRules
import com.kobblo.gallery.file.explorer.transfer.core.SavedPeer
import com.kobblo.gallery.file.explorer.transfer.core.SenderTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRulesTest {
    private val friend = SavedPeer(host = "192.168.1.5", port = 53318, alias = "Bravo", fingerprint = "ABC")

    @Test fun onlyASignatureFromAKnownFingerprintIsKnown() {
        assertEquals(SenderTrust.KNOWN, PromptRules.trust(true, "Bravo", "abc", listOf(friend)))
    }

    @Test fun aKnownFingerprintWithoutASignatureIsOnlyAClaim() {
        assertEquals(SenderTrust.UNVERIFIED, PromptRules.trust(false, "Bravo", "ABC", listOf(friend)))
    }

    @Test fun aSignatureFromSomeoneNewProvesNothingAboutWhoTheyAre() {
        assertEquals(SenderTrust.UNVERIFIED, PromptRules.trust(true, "Charlie", "DEF", listOf(friend)))
    }

    @Test fun aKnownNameWithAnotherFingerprintIsCalledOut() {
        assertEquals(SenderTrust.BORROWED_NAME, PromptRules.trust(false, "Bravo", "DEF", listOf(friend)))
        assertEquals(SenderTrust.BORROWED_NAME, PromptRules.trust(true, "bravo ", "DEF", listOf(friend)))
    }

    @Test fun aStrangerWithNoNameInCommonIsUnverified() {
        assertEquals(SenderTrust.UNVERIFIED, PromptRules.trust(false, "Mac", "", emptyList()))
    }

    @Test fun showsTheFirstTwoNamesWithoutTheirFolders() {
        assertEquals(listOf("a.jpg", "b.jpg"), PromptRules.firstNames(listOf("Trip/a.jpg", "b.jpg", "c.jpg"), "File"))
        assertEquals(1, PromptRules.moreThanShown(3))
        assertEquals(0, PromptRules.moreThanShown(2))
        assertEquals(0, PromptRules.moreThanShown(1))
    }

    @Test fun aNameCantDressItselfUp() {
        // A right-to-left override would show this as a harmless-looking picture.
        assertEquals(listOf("harmlessgpj.exe"), PromptRules.firstNames(listOf("harmless‮gpj.exe"), "File"))
    }

    @Test fun oneQuestionAtATime() {
        val queue = PromptQueue<String> { it }
        assertTrue(queue.add("a"))
        assertFalse(queue.add("b"))
        assertFalse(queue.add("c"))
        assertEquals("a", queue.current)
        assertEquals("a", queue.take("a"))
        assertNull(queue.current)
        assertEquals("b", queue.promote())
        assertEquals("b", queue.current)
    }

    @Test fun promotingWhileOneIsUpDoesNothing() {
        val queue = PromptQueue<String> { it }
        queue.add("a")
        queue.add("b")
        assertNull(queue.promote())
        assertEquals("a", queue.current)
    }

    @Test fun onlyTheFirstAnswerCounts() {
        val queue = PromptQueue<String> { it }
        queue.add("a")
        assertEquals("a", queue.take("a"))
        assertNull(queue.take("a"))
    }

    @Test fun oneStillWaitingItsTurnCanBeWithdrawn() {
        val queue = PromptQueue<String> { it }
        queue.add("a")
        queue.add("b")
        queue.add("c")
        assertEquals("b", queue.take("b"))
        assertEquals("a", queue.take("a"))
        assertEquals("c", queue.promote())
        assertNull(queue.take("gone"))
    }

    @Test fun anEmptyQueuePromotesNothing() {
        assertNull(PromptQueue<String> { it }.promote())
    }
}
