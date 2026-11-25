package com.kobblo.gallery.file.explorer.vault

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Which of the Vault's faces is showing. */
internal enum class VaultStage {
    /** There's no vault yet: the wizard that makes one. */
    Setup,

    /** A vault waiting for its password or a fingerprint. */
    Locked,

    /** The password is forgotten: the recovery key, then a new password. */
    Recovering,

    /** Unlocked, showing its files and the settings reached from them. */
    Open
}

/**
 * Where the Vault screen is and what it's in the middle of.
 *
 * None of this is saved with the activity. The key only ever lives in memory, so a restored "open"
 * would describe a vault that nothing can read; a screen that comes back always comes back locked.
 */
@Stable
internal class VaultScreenState(hasVault: Boolean) {
    var stage by mutableStateOf(if (hasVault) VaultStage.Locked else VaultStage.Setup)
        private set

    /** Kobblo's own file picker is up, for choosing what goes in. */
    var isChoosingFiles by mutableStateOf(false)
        private set

    /** Chosen files are on their way in. The list waits for them. */
    var isAddingFiles by mutableStateOf(false)
        private set

    /** Goes up each time files finish arriving, so the list knows to read the vault again. */
    var filesVersion by mutableIntStateOf(0)
        private set

    val isOpen: Boolean get() = stage == VaultStage.Open

    /** Setup finished, the password or a fingerprint was accepted, or recovery set a new password. */
    fun open() {
        stage = VaultStage.Open
    }

    /** Back to asking for the password. Only an open vault can be locked: setup and recovery stay where they are. */
    fun lock() {
        if (stage == VaultStage.Open) stage = VaultStage.Locked
    }

    fun startRecovery() {
        if (stage == VaultStage.Locked) stage = VaultStage.Recovering
    }

    fun leaveRecovery() {
        if (stage == VaultStage.Recovering) stage = VaultStage.Locked
    }

    fun chooseFiles() {
        isChoosingFiles = true
    }

    fun doneChoosing() {
        isChoosingFiles = false
    }

    /** Call when [count] chosen files are about to go in. False when nothing was chosen, so nothing starts. */
    fun startAdding(count: Int): Boolean {
        if (count <= 0) return false
        isAddingFiles = true
        return true
    }

    fun finishAdding() {
        isAddingFiles = false
        filesVersion++
    }
}
