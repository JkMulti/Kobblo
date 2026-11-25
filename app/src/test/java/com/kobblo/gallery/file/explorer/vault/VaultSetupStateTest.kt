package com.kobblo.gallery.file.explorer.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSetupStateTest {
    private val goodPassword = "correct horse"

    // ── The rules for a new password ─────────────────────────────────────────────────────────────

    @Test
    fun nothingTypedYet_isNeitherWrongNorEnough() {
        val entry = PasswordEntry()

        assertNull(entry.fault)
        assertFalse(entry.isMismatched)
        assertFalse(entry.isAcceptable)
    }

    @Test
    fun eightCharacters_isTheShortestPasswordAllowed() {
        assertEquals(8, VaultManager.MIN_PASSWORD_LENGTH)
        assertFalse(PasswordEntry("1234567", "1234567").isAcceptable)
        assertTrue(PasswordEntry("12345678", "12345678").isAcceptable)
    }

    @Test
    fun aShortPassword_isSaidToBeShort() {
        assertEquals(PasswordFault.TooShort, PasswordEntry(password = "abc").fault)
        assertEquals(PasswordFault.TooShort, PasswordEntry("1234567", "1234567").fault)
    }

    @Test
    fun aLongEnoughPassword_stillNeedsItsRepeat() {
        val entry = PasswordEntry(password = goodPassword)

        assertNull("An empty repeat isn't a mistake yet", entry.fault)
        assertFalse(entry.isMismatched)
        assertFalse(entry.isAcceptable)
    }

    @Test
    fun aRepeatThatDisagrees_isAMismatchFromItsFirstCharacter() {
        val entry = PasswordEntry(goodPassword, "x")

        assertTrue(entry.isMismatched)
        assertEquals(PasswordFault.Mismatch, entry.fault)
        assertFalse(entry.isAcceptable)
    }

    @Test
    fun aRepeatPartwayThere_isStillAMismatch() {
        val entry = PasswordEntry(goodPassword, goodPassword.dropLast(1))

        assertEquals(PasswordFault.Mismatch, entry.fault)
        assertFalse(entry.isAcceptable)
    }

    @Test
    fun aMismatch_isSaidBeforeBeingTooShort() {
        assertEquals(PasswordFault.Mismatch, PasswordEntry("abc", "abd").fault)
    }

    @Test
    fun aRepeatWithNoPassword_isAMismatch() {
        val entry = PasswordEntry(password = "", repeat = "typed here first")

        assertTrue(entry.isMismatched)
        assertEquals(PasswordFault.Mismatch, entry.fault)
    }

    @Test
    fun matchingAndLongEnough_isAcceptable() {
        val entry = PasswordEntry(goodPassword, goodPassword)

        assertNull(entry.fault)
        assertFalse(entry.isMismatched)
        assertTrue(entry.isAcceptable)
    }

    @Test
    fun passwordsAreComparedExactly() {
        assertFalse(PasswordEntry("Correct Horse", "correct horse").isAcceptable)
        assertFalse(PasswordEntry("correct horse", "correct horse ").isAcceptable)
    }

    @Test
    fun theHint_isOptionalAndChangesNothing() {
        assertTrue(PasswordEntry(goodPassword, goodPassword, hint = "").isAcceptable)
        assertTrue(PasswordEntry(goodPassword, goodPassword, hint = "the usual").isAcceptable)
    }

    @Test
    fun editingOneField_keepsTheOthers() {
        val entry = PasswordEntry("one", "two", "three")

        entry.withPassword("ONE").let {
            assertEquals(listOf("ONE", "two", "three"), listOf(it.password, it.repeat, it.hint))
        }
        entry.withRepeat("TWO").let {
            assertEquals(listOf("one", "TWO", "three"), listOf(it.password, it.repeat, it.hint))
        }
        entry.withHint("THREE").let {
            assertEquals(listOf("one", "two", "THREE"), listOf(it.password, it.repeat, it.hint))
        }
    }

    // ── The wizard's pages ───────────────────────────────────────────────────────────────────────

    @Test
    fun theWizard_startsOnTheWelcomePageWithNothingChosen() {
        val setup = VaultSetupState(recoveryKey = "KEY")

        assertEquals(SetupStep.Welcome, setup.step)
        assertEquals("", setup.password)
        assertEquals("", setup.hint)
    }

    @Test
    fun thePagesComeInOrder() {
        assertEquals(
            listOf(SetupStep.Welcome, SetupStep.ChoosePassword, SetupStep.SaveRecoveryKey),
            SetupStep.entries.toList()
        )
    }

    @Test
    fun aGoodPassword_movesOnToTheRecoveryKey() {
        val setup = VaultSetupState(recoveryKey = "KEY")
        setup.showPasswordPage()
        assertEquals(SetupStep.ChoosePassword, setup.step)

        assertTrue(setup.choose(PasswordEntry(goodPassword, goodPassword, "a hint")))

        assertEquals(SetupStep.SaveRecoveryKey, setup.step)
        assertEquals(goodPassword, setup.password)
        assertEquals("a hint", setup.hint)
    }

    @Test
    fun aPasswordThatIsNotGoodEnough_goesNowhere() {
        val setup = VaultSetupState(recoveryKey = "KEY")
        setup.showPasswordPage()

        assertFalse(setup.choose(PasswordEntry("short", "short")))
        assertFalse(setup.choose(PasswordEntry(goodPassword, "something else")))
        assertFalse(setup.choose(PasswordEntry(goodPassword)))

        assertEquals(SetupStep.ChoosePassword, setup.step)
        assertEquals("", setup.password)
    }

    @Test
    fun backFromTheKey_returnsToThePasswordThatWasChosen() {
        val setup = VaultSetupState(recoveryKey = "KEY")
        setup.showPasswordPage()
        setup.choose(PasswordEntry(goodPassword, goodPassword, "a hint"))

        setup.showPasswordPage()

        assertEquals(SetupStep.ChoosePassword, setup.step)
        assertEquals(goodPassword, setup.chosen.password)
        assertEquals(goodPassword, setup.chosen.repeat)
        assertEquals("a hint", setup.chosen.hint)
    }

    @Test
    fun goingBackToTheStart_keepsWhatWasChosenForNextTime() {
        val setup = VaultSetupState(recoveryKey = "KEY")
        setup.showPasswordPage()
        setup.choose(PasswordEntry(goodPassword, goodPassword))
        setup.showPasswordPage()

        setup.showWelcome()
        assertEquals(SetupStep.Welcome, setup.step)

        setup.showPasswordPage()
        assertEquals(goodPassword, setup.chosen.password)
    }

    @Test
    fun aLaterChoice_replacesTheEarlierOne() {
        val setup = VaultSetupState(recoveryKey = "KEY")
        setup.showPasswordPage()
        setup.choose(PasswordEntry(goodPassword, goodPassword, "first"))
        setup.showPasswordPage()

        setup.choose(PasswordEntry("another long one", "another long one", "second"))

        assertEquals("another long one", setup.password)
        assertEquals("second", setup.hint)
    }

    @Test
    fun aRefusedChange_leavesTheEarlierChoiceStanding() {
        val setup = VaultSetupState(recoveryKey = "KEY")
        setup.showPasswordPage()
        setup.choose(PasswordEntry(goodPassword, goodPassword))
        setup.showPasswordPage()

        assertFalse(setup.choose(PasswordEntry("nope", "nope")))

        assertEquals(goodPassword, setup.password)
        assertEquals(SetupStep.ChoosePassword, setup.step)
    }

    @Test
    fun theRecoveryKey_isTheSameOneOnEveryVisitToItsPage() {
        val setup = VaultSetupState(recoveryKey = "KEY")
        setup.showPasswordPage()
        setup.choose(PasswordEntry(goodPassword, goodPassword))
        setup.showPasswordPage()
        setup.choose(PasswordEntry(goodPassword, goodPassword))

        assertEquals("KEY", setup.recoveryKey)
    }
}
