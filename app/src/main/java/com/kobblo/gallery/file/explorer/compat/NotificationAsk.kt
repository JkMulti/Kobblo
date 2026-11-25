package com.kobblo.gallery.file.explorer.compat

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * From Android 13 notifications start out off, so a copy's progress and Cancel, and the Accept button for an
 * incoming file, would never show. It's asked once, at the first moment one of those would be useful, so the
 * question comes with its reason in plain view. Saying no is final: Android's own settings can still change it.
 */
internal object NotificationAsk {
    private const val PREFS = "kobblo_prefs"
    private const val ASKED_KEY = "notifications_asked"

    fun isWanted(context: Context): Boolean = VersionRules.asksForNotifications(
        sdk = Build.VERSION.SDK_INT,
        granted = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        askedBefore = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED_KEY, false)
    )

    fun rememberAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED_KEY, true).apply()
    }
}
