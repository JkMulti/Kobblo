package com.kobblo.gallery.file.explorer.files

import android.content.Context
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.RecentActions
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.vault.VaultPaths
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File

/** How long ago something was, in the one unit Recents tells it in. */
internal data class Age(val scale: Scale, val count: Int) {
    enum class Scale { Now, Minutes, Hours, Days }

    companion object {
        private const val Minute = 60_000L
        private const val Hour = 60 * Minute
        private const val Day = 24 * Hour

        /** A moment that's still to come, after the clock was put back, counts as now. */
        fun of(millis: Long): Age = when {
            millis < Minute -> Age(Scale.Now, 0)
            millis < Hour -> Age(Scale.Minutes, (millis / Minute).toInt())
            millis < Day -> Age(Scale.Hours, (millis / Hour).toInt())
            else -> Age(Scale.Days, (millis / Day).toInt())
        }
    }
}

/** The words Recents needs, which depend on the language. */
internal interface RecentWords {
    /** What was done, from the key it was saved under. */
    fun action(key: String): String

    fun age(age: Age): String
}

/** The rows of Recents: what was used lately, or the newest files around when nothing has been yet. */
internal class RecentLists(
    private val history: () -> Sequence<RecentEntry>,
    private val storage: PhoneStorage,
    private val rows: LocalRows,
    private val words: RecentWords,
    private val playingTime: (path: String) -> String?,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun list(marks: PathMarks): List<FileItem> {
        val items = ArrayList<FileItem>()
        try {
            history().mapNotNullTo(items) { rowFor(it, marks) }
            if (items.isEmpty()) newestAround(marks).toCollection(items)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    /** Null for what isn't there any more, or is in the Vault, which earlier versions remembered as well. */
    private fun rowFor(entry: RecentEntry, marks: PathMarks): FileItem? {
        val file = File(entry.path)
        val there = file.exists()
        if (!there && DriveLocation.idIn(entry.path) == null) return null
        if (VaultPaths.isVaultPath(entry.path)) return null
        val name = if (there) file.name else entry.path.substringAfterLast('/')
        val type = if (there && file.isDirectory) "folder" else FileKinds.typeOf(name)
        val bytes = if (file.isFile) file.length() else null
        return FileItem(
            // From the path, because the order of this list changes with every action,
            // and a selection has to keep pointing at the same file.
            id = entry.path.hashCode(),
            path = entry.path,
            name = name,
            type = type,
            kind = listKindOf(type),
            // A folder has no size to show, so it says what was done to it.
            size = bytes?.let(FileSizes::label) ?: words.action(entry.action),
            changed = words.age(Age.of(now() - entry.at)),
            owner = LocalOwner,
            isPinned = marks.isFavorite(entry.path),
            isLocked = marks.isLocked(entry.path),
            sizeBytes = bytes ?: 0L,
            lastModified = entry.at,
            duration = if (there && type == "video") playingTime(file.absolutePath) else null
        )
    }

    private fun newestAround(marks: PathMarks): List<FileItem> {
        val ids = ListIds()
        return storage.busyFolders
            .filter { it.exists() && it.canRead() }
            .flatMap { it.listFiles().orEmpty().asList() }
            .filter { !it.name.startsWith(".") && it.exists() }
            .distinctBy { it.absolutePath }
            .map { it to it.lastModified() }
            .sortedByDescending { (_, modified) -> modified }
            .take(NewestShown)
            .map { (file, _) -> rows.row(file, ids.of(file.absolutePath), marks) }
    }

    private companion object {
        const val NewestShown = 15
    }
}

/** The rows of Favorites: those that are files on the phone and are still there. */
internal class FavoriteLists(private val rows: LocalRows) {
    fun list(marks: PathMarks): List<FileItem> {
        val there = marks.favorites
            .filter { DriveLocation.idIn(it) == null }
            .map(::File)
            .filter(File::exists)
        return rows.listed(there.toTypedArray(), marks)
    }
}

/** The words out of the app's resources, in the language in use. */
internal class AndroidRecentWords(private val context: Context) : RecentWords {
    override fun action(key: String): String = RecentActions.label(context, key)

    override fun age(age: Age): String {
        val ago = when (age.scale) {
            Age.Scale.Now -> return context.getString(R.string.app_recent_just_now)
            Age.Scale.Minutes -> R.plurals.app_recent_minutes_ago
            Age.Scale.Hours -> R.plurals.app_recent_hours_ago
            Age.Scale.Days -> R.plurals.app_recent_days_ago
        }
        return context.resources.getQuantityString(ago, age.count, age.count)
    }
}
