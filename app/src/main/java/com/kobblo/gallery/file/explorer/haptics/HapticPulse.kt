package com.kobblo.gallery.file.explorer.haptics

import com.kobblo.gallery.file.explorer.HapticEvent

/**
 * How long the motor runs for each kind of event, in milliseconds. They sit within ten milliseconds of
 * each other on purpose: enough to tell finishing a clean-up from opening a file, with none of them
 * long enough to read as a warning. Deleting is deliberately no heavier than any other confirmation.
 */
internal val HapticEvent.pulseMillis: Long
    get() = when (this) {
        HapticEvent.FileOpen, HapticEvent.SettingsToggle, HapticEvent.General -> 12L
        HapticEvent.CopyPaste -> 14L
        HapticEvent.Fab -> 16L
        HapticEvent.Delete -> 18L
        HapticEvent.Cleaner -> 22L
    }

/** The length of a tick on phones too old to have the system's own. */
internal const val FallbackTickMillis = 5L
