package com.kobblo.gallery.file.explorer.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultScreenStateTest {
    @Test
    fun withNoVault_itStartsAtSetup() {
        val screen = VaultScreenState(hasVault = false)

        assertEquals(VaultStage.Setup, screen.stage)
        assertFalse(screen.isOpen)
    }

    @Test
    fun withAVault_itStartsLockedHoweverItWasLeft() {
        val screen = VaultScreenState(hasVault = true)

        assertEquals(VaultStage.Locked, screen.stage)
        assertFalse(screen.isOpen)
    }

    @Test
    fun finishingSetup_opensTheNewVault() {
        val screen = VaultScreenState(hasVault = false)

        screen.open()

        assertEquals(VaultStage.Open, screen.stage)
        assertTrue(screen.isOpen)
    }

    @Test
    fun unlocking_opensIt_andLocking_asksForThePasswordAgain() {
        val screen = VaultScreenState(hasVault = true)

        screen.open()
        assertTrue(screen.isOpen)

        screen.lock()
        assertEquals(VaultStage.Locked, screen.stage)
        assertFalse(screen.isOpen)
    }

    @Test
    fun aVaultMadeInThisVisit_locksToThePasswordNotBackToSetup() {
        val screen = VaultScreenState(hasVault = false)
        screen.open()

        screen.lock()

        assertEquals(VaultStage.Locked, screen.stage)
    }

    @Test
    fun locking_onlyMeansSomethingWhileOpen() {
        val settingUp = VaultScreenState(hasVault = false)
        settingUp.lock()
        assertEquals("Leaving the wizard mustn't skip it", VaultStage.Setup, settingUp.stage)

        val recovering = VaultScreenState(hasVault = true).apply { startRecovery() }
        recovering.lock()
        assertEquals(VaultStage.Recovering, recovering.stage)

        val locked = VaultScreenState(hasVault = true)
        locked.lock()
        assertEquals(VaultStage.Locked, locked.stage)
    }

    @Test
    fun forgettingThePassword_leadsToRecovery_andBackLeadsToThePassword() {
        val screen = VaultScreenState(hasVault = true)

        screen.startRecovery()
        assertEquals(VaultStage.Recovering, screen.stage)
        assertFalse(screen.isOpen)

        screen.leaveRecovery()
        assertEquals(VaultStage.Locked, screen.stage)
    }

    @Test
    fun recovery_endsWithTheVaultOpen_andLockingThenAsksForThePassword() {
        val screen = VaultScreenState(hasVault = true)
        screen.startRecovery()

        screen.open()
        assertEquals(VaultStage.Open, screen.stage)

        screen.lock()
        assertEquals("Recovery is over once it has let someone in", VaultStage.Locked, screen.stage)
    }

    @Test
    fun recovery_isOnlyReachedFromTheLockedVault() {
        val settingUp = VaultScreenState(hasVault = false)
        settingUp.startRecovery()
        assertEquals(VaultStage.Setup, settingUp.stage)

        val open = VaultScreenState(hasVault = true).apply { open() }
        open.startRecovery()
        assertEquals(VaultStage.Open, open.stage)
    }

    @Test
    fun leavingRecovery_doesNothingAnywhereElse() {
        val open = VaultScreenState(hasVault = true).apply { open() }
        open.leaveRecovery()
        assertEquals("It must not lock an open vault", VaultStage.Open, open.stage)

        val settingUp = VaultScreenState(hasVault = false)
        settingUp.leaveRecovery()
        assertEquals(VaultStage.Setup, settingUp.stage)
    }

    @Test
    fun choosingFiles_showsThePickerUntilItIsDone() {
        val screen = VaultScreenState(hasVault = true).apply { open() }
        assertFalse(screen.isChoosingFiles)

        screen.chooseFiles()
        assertTrue(screen.isChoosingFiles)

        screen.doneChoosing()
        assertFalse(screen.isChoosingFiles)
    }

    @Test
    fun addingNothing_startsNothing() {
        val screen = VaultScreenState(hasVault = true).apply { open() }

        assertFalse(screen.startAdding(0))

        assertFalse(screen.isAddingFiles)
        assertEquals(0, screen.filesVersion)
    }

    @Test
    fun addingFiles_isBusyUntilTheyArrive_andThenTheListReadsAgain() {
        val screen = VaultScreenState(hasVault = true).apply { open() }

        assertTrue(screen.startAdding(3))
        assertTrue(screen.isAddingFiles)
        assertEquals("Nothing has arrived yet", 0, screen.filesVersion)

        screen.finishAdding()
        assertFalse(screen.isAddingFiles)
        assertEquals(1, screen.filesVersion)
    }

    @Test
    fun everyArrival_makesTheListReadAgain() {
        val screen = VaultScreenState(hasVault = true).apply { open() }

        repeat(3) {
            screen.startAdding(1)
            screen.finishAdding()
        }

        assertEquals(3, screen.filesVersion)
    }

    @Test
    fun filesStillArriving_whenTheVaultLocks_arriveAllTheSame() {
        val screen = VaultScreenState(hasVault = true).apply { open() }
        screen.startAdding(2)

        screen.lock()
        assertTrue("The job is still running", screen.isAddingFiles)

        screen.finishAdding()
        assertFalse(screen.isAddingFiles)
        assertEquals(VaultStage.Locked, screen.stage)
    }
}
