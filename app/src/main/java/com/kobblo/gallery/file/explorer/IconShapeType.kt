package com.kobblo.gallery.file.explorer

/**
 * The outline once chosen for icon tiles. Every tile is drawn in one shape now, but the choice is
 * still saved by its name under `icon_shape`, so each name an install may hold has to stay readable.
 */
enum class IconShapeType {
    COOKIE,
    CIRCLE,
    SQUARE,
    ROUNDED_SQUARE,
    IOS,
    ONE_UI,
    AUTO;

    companion object {
        /** Names from before two of the shapes were renamed, and the shape each one became. */
        private val retiredNames = mapOf("SYSTEM" to COOKIE, "SQUIRCLE" to IOS)

        /** Reads a saved name. A missing one, or one that was never a shape, is [COOKIE]. */
        fun fromString(name: String?): IconShapeType =
            entries.firstOrNull { it.name == name } ?: retiredNames[name] ?: COOKIE
    }
}
