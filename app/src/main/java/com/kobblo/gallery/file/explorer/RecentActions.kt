package com.kobblo.gallery.file.explorer

import android.content.Context

/**
 * What Recents says was done to something. It's saved in English, as a key, and only put into words when it's
 * shown, so an old entry reads in whatever language the app is in by then.
 */
object RecentActions {
    const val Visited = "Visited"
    const val Opened = "Opened"

    /** What earlier versions saved for [Visited]. */
    private const val Explored = "Explored"
    private const val RenamedFromPrefix = "Renamed from "

    fun renamedFrom(oldName: String): String = RenamedFromPrefix + oldName

    /** The words for a saved action. One this doesn't know is shown as it was saved. */
    fun label(context: Context, action: String): String = when {
        action == Visited || action == Explored -> context.getString(R.string.app_recent_visited)
        action == Opened -> context.getString(R.string.app_recent_opened)
        action.startsWith(RenamedFromPrefix) -> context.getString(R.string.app_recent_renamed_from, action.removePrefix(RenamedFromPrefix))
        else -> action.replace(Explored, Visited)
    }
}
