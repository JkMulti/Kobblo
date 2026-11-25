package com.kobblo.gallery.file.explorer.ui.root

import androidx.compose.runtime.Stable

/**
 * What the root keeps in mind beyond the app's state: which of its own sheets and dialogs are open, and
 * what they're about. Each part has a holder that its own composables read, so a sheet opening redraws
 * the sheet and not the pages under it. None of it outlives the screen.
 */
@Stable
internal class RootMemory {
    val trail = PageTrail()
    val details = FileDetailsDesk()
    val deleting = DeleteQuestion()
    val hiding = Hiding()
    val archives = ArchiveDesk()
    val installs = InstallDesk()
    val creating = Creating()
    val forms = PlaceForms()
}
