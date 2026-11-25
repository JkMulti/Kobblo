package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.runtime.staticCompositionLocalOf
import com.kobblo.gallery.file.explorer.IconShapeType

/**
 * The icon shape saved in settings. Every tile is drawn in `KobbloShapes.tile` whatever this says, so
 * the app has one silhouette; the value is only carried for the settings code that still stores it.
 */
val LocalIconShapeType = staticCompositionLocalOf { IconShapeType.IOS }

/** What the "automatic" shape setting stands for, which is the same on every device. */
object AutoIconShapeSession {
    fun refresh(context: android.content.Context): IconShapeType = IconShapeType.IOS
}
