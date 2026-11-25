package com.kobblo.gallery.file.explorer.data.core

/** What the person chose when the name something is headed for is already taken. */
sealed class ConflictResolution {
    /**
     * Take the place of what's there. A storage addressed by path does that just by writing to the name.
     * Google Drive and MEGA would end up holding two things with one name, so whoever carries this out
     * has to remove the old one first.
     */
    object Overwrite : ConflictResolution()

    /** Leave what's there alone and arrive as [newName]. */
    data class Rename(val newName: String) : ConflictResolution()
}
