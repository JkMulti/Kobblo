package com.kobblo.gallery.file.explorer.fileops

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.kobblo.gallery.file.explorer.MainActivity

/** Handing a file to another app, which this app isn't: it registers for most file types itself. */
internal object OpenElsewhere {
    fun chooser(context: Context, intent: Intent, title: CharSequence): Intent =
        Intent.createChooser(intent, title)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun anyoneElse(context: Context, intent: Intent): Boolean =
        context.packageManager.queryIntentActivities(intent, 0).any { it.activityInfo.packageName != context.packageName }
}
