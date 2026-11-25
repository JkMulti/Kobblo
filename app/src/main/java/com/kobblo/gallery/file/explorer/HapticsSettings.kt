package com.kobblo.gallery.file.explorer

import androidx.compose.runtime.Immutable

/** The moments a buzz can mark, each with the switch in Settings that turns it on and off. */
enum class HapticEvent(internal val isWantedIn: (HapticsSettings) -> Boolean) {
    FileOpen(HapticsSettings::fileOpen),
    CopyPaste(HapticsSettings::copyPaste),
    Delete(HapticsSettings::delete),
    Cleaner(HapticsSettings::cleaner),
    SettingsToggle(HapticsSettings::settings),
    Fab(HapticsSettings::fab),

    /** An ordinary tap, which has no switch of its own. */
    General({ true })
}

/** Which moments buzz. [masterEnabled] is over all of them. */
@Immutable
data class HapticsSettings(
    val masterEnabled: Boolean = true,
    val fileOpen: Boolean = true,
    val copyPaste: Boolean = true,
    val delete: Boolean = true,
    val cleaner: Boolean = true,
    val settings: Boolean = false,
    val fab: Boolean = true
) {
    fun isEnabled(event: HapticEvent): Boolean = masterEnabled && event.isWantedIn(this)
}
