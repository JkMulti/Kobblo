package com.kobblo.gallery.file.explorer.vault

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultUnlockStateTest {
    /** A vault that opens for one password and pauses for half a minute on every fifth wrong one in a row. */
    private class FakeDoor(private val rightPassword: String = "open sesame") : VaultDoor {
        override var hint = "a hint"
        override var hasRecoveryKey = true
        var mode = VaultBiometricMode.None
        var pauseMs = 0L
        var wrongInARow = 0
        var timesForgotten = 0
        val tried = mutableListOf<String>()
        val promptedWith = mutableListOf<VaultBiometricMode>()
        var fingerprintAnswer: VaultBiometricResult = VaultBiometricResult.Cancelled
        var whileOpening: suspend () -> Unit = {}
        var whilePrompting: () -> Unit = {}

        override fun fingerprintMode() = mode

        override fun pauseLeftMs() = pauseMs

        override fun countWrongPassword() {
            wrongInARow++
            if (wrongInARow % 5 == 0) pauseMs = 30_000L
        }

        override fun forgetWrongPasswords() {
            wrongInARow = 0
            pauseMs = 0L
            timesForgotten++
        }

        override suspend fun open(password: String): Boolean {
            tried += password
            whileOpening()
            return password == rightPassword
        }

        override suspend fun openWithFingerprint(mode: VaultBiometricMode): VaultBiometricResult {
            promptedWith += mode
            whilePrompting()
            return fingerprintAnswer
        }
    }

    private val door = FakeDoor()

    private fun VaultUnlockState.submitTyped(password: String): Boolean {
        type(password)
        return runBlocking { submit() }
    }

    @Test
    fun thePage_showsWhatTheVaultSaysAboutItself() {
        door.hint = "first pet"
        door.hasRecoveryKey = false
        door.mode = VaultBiometricMode.VaultKey

        val unlock = VaultUnlockState(door)

        assertEquals("first pet", unlock.hint)
        assertFalse(unlock.hasRecoveryKey)
        assertEquals(VaultBiometricMode.VaultKey, unlock.fingerprintMode)
        assertEquals("", unlock.password)
        assertNull(unlock.problem)
        assertFalse(unlock.isChecking)
    }

    @Test
    fun theButton_waitsForSomethingToBeTyped() {
        val unlock = VaultUnlockState(door)
        assertFalse(unlock.canSubmit)

        unlock.type("x")
        assertTrue(unlock.canSubmit)

        unlock.type("")
        assertFalse(unlock.canSubmit)
    }

    @Test
    fun submittingNothing_triesNothing() {
        val unlock = VaultUnlockState(door)

        assertFalse(runBlocking { unlock.submit() })

        assertEquals(emptyList<String>(), door.tried)
        assertNull(unlock.problem)
        assertEquals(0, door.wrongInARow)
    }

    @Test
    fun theRightPassword_opensTheVaultAndEndsTheRunOfWrongOnes() {
        val unlock = VaultUnlockState(door)
        unlock.submitTyped("wrong")

        assertTrue(unlock.submitTyped("open sesame"))

        assertNull(unlock.problem)
        assertEquals(0, door.wrongInARow)
        assertEquals(1, door.timesForgotten)
        assertFalse(unlock.isChecking)
    }

    @Test
    fun aWrongPassword_isCountedAndSaid() {
        val unlock = VaultUnlockState(door)

        assertFalse(unlock.submitTyped("wrong"))

        assertEquals(UnlockProblem.WrongPassword, unlock.problem)
        assertEquals(1, door.wrongInARow)
        assertEquals(0, door.timesForgotten)
        assertFalse(unlock.isChecking)
    }

    @Test
    fun typingAgain_clearsWhatWasSaid() {
        val unlock = VaultUnlockState(door)
        unlock.submitTyped("wrong")

        unlock.type("wrong again")

        assertNull(unlock.problem)
    }

    @Test
    fun theFifthWrongPasswordInARow_earnsAPause_andSaysThatInstead() {
        val unlock = VaultUnlockState(door)
        repeat(4) {
            unlock.submitTyped("wrong $it")
            assertEquals(UnlockProblem.WrongPassword, unlock.problem)
        }

        unlock.submitTyped("wrong 4")

        assertEquals(UnlockProblem.Paused(30_000L), unlock.problem)
        assertEquals(5, door.tried.size)
    }

    @Test
    fun duringAPause_noPasswordIsTriedNotEvenTheRightOne() {
        val unlock = VaultUnlockState(door)
        door.pauseMs = 12_000L

        assertFalse(unlock.submitTyped("open sesame"))

        assertEquals(emptyList<String>(), door.tried)
        assertEquals("It says how long is left now", UnlockProblem.Paused(12_000L), unlock.problem)
        assertEquals("A refused try isn't another wrong one", 0, door.wrongInARow)
        assertFalse(unlock.isChecking)
    }

    @Test
    fun onceThePauseIsOver_passwordsAreTriedAgain() {
        val unlock = VaultUnlockState(door)
        door.pauseMs = 12_000L
        unlock.submitTyped("open sesame")

        door.pauseMs = 0L

        assertTrue(unlock.submitTyped("open sesame"))
    }

    @Test
    fun whileAPasswordIsBeingChecked_thePageSaysSo_andASecondTryIsIgnored() {
        val unlock = VaultUnlockState(door)
        var checkingDuring = false
        var secondTry = true
        door.whileOpening = {
            checkingDuring = unlock.isChecking
            door.whileOpening = {}
            secondTry = unlock.submit()
        }

        assertTrue(unlock.submitTyped("open sesame"))

        assertTrue(checkingDuring)
        assertFalse(secondTry)
        assertEquals("Only the first try reached the vault", listOf("open sesame"), door.tried)
        assertFalse(unlock.isChecking)
    }

    @Test
    fun aFingerprintIsOffered_onlyWhenTheVaultHasAUseForOne() {
        door.mode = VaultBiometricMode.None
        assertFalse(VaultUnlockState(door).offersFingerprint)

        door.mode = VaultBiometricMode.PromptOnly
        assertTrue(VaultUnlockState(door).offersFingerprint)

        door.mode = VaultBiometricMode.VaultKey
        assertTrue(VaultUnlockState(door).offersFingerprint)
    }

    @Test
    fun onlyAFingerprintThatReleasesTheKey_isAskedForOnArrival() {
        door.mode = VaultBiometricMode.VaultKey
        assertTrue(VaultUnlockState(door).promptsForFingerprintOnArrival)

        door.mode = VaultBiometricMode.PromptOnly
        assertFalse(VaultUnlockState(door).promptsForFingerprintOnArrival)

        door.mode = VaultBiometricMode.None
        assertFalse(VaultUnlockState(door).promptsForFingerprintOnArrival)
    }

    @Test
    fun anAcceptedFingerprint_opensTheVaultAndEndsTheRunOfWrongPasswords() {
        door.mode = VaultBiometricMode.VaultKey
        door.fingerprintAnswer = VaultBiometricResult.Done
        val unlock = VaultUnlockState(door)
        unlock.submitTyped("wrong")

        assertTrue(runBlocking { unlock.useFingerprint() })

        assertEquals(listOf(VaultBiometricMode.VaultKey), door.promptedWith)
        assertEquals(0, door.wrongInARow)
        assertEquals(1, door.timesForgotten)
    }

    @Test
    fun backingOutOfTheFingerprintPrompt_changesNothing() {
        door.mode = VaultBiometricMode.VaultKey
        door.fingerprintAnswer = VaultBiometricResult.Cancelled
        val unlock = VaultUnlockState(door)
        unlock.submitTyped("wrong")

        assertFalse(runBlocking { unlock.useFingerprint() })

        assertEquals("What was said about the password still stands", UnlockProblem.WrongPassword, unlock.problem)
        assertEquals(VaultBiometricMode.VaultKey, unlock.fingerprintMode)
        assertEquals(1, door.wrongInARow)
    }

    @Test
    fun aFingerprintPromptThatFails_saysWhyInTheSystemsWords() {
        door.mode = VaultBiometricMode.VaultKey
        door.fingerprintAnswer = VaultBiometricResult.Failed("Too many attempts. Try again later.")
        val unlock = VaultUnlockState(door)

        assertFalse(runBlocking { unlock.useFingerprint() })

        assertEquals(UnlockProblem.FingerprintFailed("Too many attempts. Try again later."), unlock.problem)
        assertTrue("It can be tried again", unlock.offersFingerprint)
    }

    @Test
    fun changedFingerprints_areExplained_andTheOfferGoesWithTheKey() {
        door.mode = VaultBiometricMode.VaultKey
        door.fingerprintAnswer = VaultBiometricResult.KeyLost
        // Finding the key dead is when the real vault drops it, and with it the offer.
        door.whilePrompting = { door.mode = VaultBiometricMode.None }
        val unlock = VaultUnlockState(door)
        assertTrue(unlock.offersFingerprint)

        assertFalse(runBlocking { unlock.useFingerprint() })

        assertEquals(UnlockProblem.FingerprintsChanged, unlock.problem)
        assertEquals(VaultBiometricMode.None, unlock.fingerprintMode)
        assertFalse(unlock.offersFingerprint)
    }

    @Test
    fun aFingerprintIsNotHeldBackByThePasswordPause() {
        door.mode = VaultBiometricMode.VaultKey
        door.fingerprintAnswer = VaultBiometricResult.Done
        door.pauseMs = 60_000L
        val unlock = VaultUnlockState(door)

        assertTrue(runBlocking { unlock.useFingerprint() })
    }

    @Test
    fun typingAfterAFingerprintProblem_clearsItToo() {
        door.mode = VaultBiometricMode.VaultKey
        door.fingerprintAnswer = VaultBiometricResult.Failed("Sensor is dirty")
        val unlock = VaultUnlockState(door)
        runBlocking { unlock.useFingerprint() }

        unlock.type("p")

        assertNull(unlock.problem)
    }
}
