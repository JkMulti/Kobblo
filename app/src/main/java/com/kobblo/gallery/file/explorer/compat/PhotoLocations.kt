package com.kobblo.gallery.file.explorer.compat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Where a photo was taken. From Android 10 the system blanks that out of every photo an app reads,
 * unless the app may see photo locations, so Places and a photo's details would have nothing to show.
 */
internal object PhotoLocations {
    const val Permission = Manifest.permission.ACCESS_MEDIA_LOCATION

    /** Before Android 10 nothing was hidden, so there's nothing to ask for. */
    fun isAsked(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun allowed(context: Context): Boolean =
        !isAsked() || ContextCompat.checkSelfPermission(context, Permission) == PackageManager.PERMISSION_GRANTED
}
