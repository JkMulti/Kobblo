package com.kobblo.gallery.file.explorer.explorer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kobblo.gallery.file.explorer.SortMode

/** What one folder asked for. Anything left null is taken from the folders above it. */
internal data class FolderView(
    val sort: SortMode? = null,
    val reversed: Boolean = false,
    val list: Boolean? = null
)

/** How a folder should look once everything above it has had its say. */
data class ResolvedFolderView(
    val sort: SortMode,
    val reversed: Boolean,
    val list: Boolean
)

/** The rules behind [FolderViewMemory], without Android types so they can be tested on the JVM. */
internal object FolderViewRules {

    /**
     * A folder looks how it was last left. If it never was, it looks like the nearest folder above it
     * that says anything, so choosing a sort at the top of storage chooses it for everything below.
     * A few well-known folders have a better start than that: nobody looks in Downloads for what comes
     * first in the alphabet.
     */
    fun resolve(
        path: String,
        roots: List<String>,
        fallbackList: Boolean,
        stored: (String) -> FolderView?
    ): ResolvedFolderView {
        var sort: SortMode? = null
        var reversed = false
        var list: Boolean? = null
        var current: String? = path
        while (current != null && (sort == null || list == null)) {
            val own = stored(current)
            val known = wellKnown(current, roots, isTheFolderItself = current == path)
            val said = if (own != null) mergeInto(own, known) else known
            if (said != null) {
                if (sort == null && said.sort != null) {
                    sort = said.sort
                    reversed = said.reversed
                }
                if (list == null) list = said.list
            }
            current = parentOf(current)
        }
        return ResolvedFolderView(sort ?: SortMode.ALPHABETICAL, reversed, list ?: fallbackList)
    }

    private fun mergeInto(own: FolderView, base: FolderView?): FolderView = when {
        base == null -> own
        own.sort == null -> own.copy(sort = base.sort, reversed = base.reversed, list = own.list ?: base.list)
        else -> own.copy(list = own.list ?: base.list)
    }

    /**
     * Where a person's own photos and downloads collect. [roots] are the top of each storage volume. A
     * folder of photos passes this on to the albums inside it, but what someone keeps in a folder inside
     * Downloads is theirs to find by name, like anywhere else.
     */
    private fun wellKnown(path: String, roots: List<String>, isTheFolderItself: Boolean): FolderView? {
        val root = roots.firstOrNull { path.startsWith("$it/") } ?: return null
        return when (path.substring(root.length + 1).lowercase()) {
            "download", "downloads" -> if (isTheFolderItself) FolderView(sort = SortMode.DATE) else null
            "dcim", "pictures", "movies" -> FolderView(sort = SortMode.DATE, list = false)
            else -> null
        }
    }

    private fun parentOf(path: String): String? {
        val slash = path.lastIndexOf('/')
        return if (slash <= 0) null else path.substring(0, slash)
    }

    /** Which sort is on after tapping [chosen]: another sort starts fresh, the same one flips over. */
    fun nextSort(current: ResolvedFolderView, chosen: SortMode): Pair<SortMode, Boolean> =
        if (current.sort == chosen) chosen to !current.reversed else chosen to false

    /** Upright phones get exactly what was asked for. Past that, the count grows with the width. */
    fun columnsFor(columnsOnPhone: Int, widthDp: Float): Int =
        if (widthDp < WideFromDp) columnsOnPhone else (columnsOnPhone * widthDp / PhoneWidthDp).toInt().coerceAtLeast(columnsOnPhone)

    private const val WideFromDp = 500f
    private const val PhoneWidthDp = 380f

    fun encode(view: FolderView, stamp: Long): String =
        listOf(view.sort?.name.orEmpty(), if (view.reversed) "r" else "", view.list?.let { if (it) "l" else "g" }.orEmpty(), stamp.toString())
            .joinToString("|")

    fun decode(text: String?): FolderView? {
        val parts = text?.split('|') ?: return null
        if (parts.size < 3) return null
        return FolderView(
            sort = SortMode.entries.firstOrNull { it.name == parts[0] },
            reversed = parts[1] == "r",
            list = when (parts[2]) {
                "l" -> true
                "g" -> false
                else -> null
            }
        )
    }

    fun stampOf(text: String?): Long = text?.substringAfterLast('|')?.toLongOrNull() ?: 0L
}

/**
 * Sort and view, remembered per folder. Only folders somebody actually changed are written, so the
 * list stays short, and past [MaxFolders] the ones untouched the longest are forgotten.
 */
object FolderViewMemory {
    private const val MaxFolders = 1000
    private var prefs: SharedPreferences? = null

    /** Moves on with each choice, so a sheet showing the current one can follow it. */
    var revision by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("explorer_folder_views", Context.MODE_PRIVATE)
    }

    /** Null for anywhere that isn't a folder on this device, where the one global choice still holds. */
    fun viewFor(location: String, fallbackList: Boolean): ResolvedFolderView? {
        if (!location.startsWith("/")) return null
        return FolderViewRules.resolve(location, roots(), fallbackList) { FolderViewRules.decode(prefs?.getString(it, null)) }
    }

    fun isReversed(location: String): Boolean = viewFor(location, false)?.reversed == true

    /** Tapping the sort that's already on flips its direction. */
    fun chooseSort(location: String, chosen: SortMode, fallbackList: Boolean) {
        val current = viewFor(location, fallbackList) ?: return
        val (sort, reversed) = FolderViewRules.nextSort(current, chosen)
        val own = FolderViewRules.decode(prefs?.getString(location, null)) ?: FolderView()
        write(location, own.copy(sort = sort, reversed = reversed))
    }

    fun chooseList(location: String, list: Boolean) {
        val own = FolderViewRules.decode(prefs?.getString(location, null)) ?: FolderView()
        write(location, own.copy(list = list))
    }

    private fun write(path: String, view: FolderView) {
        val store = prefs ?: return
        store.edit().putString(path, FolderViewRules.encode(view, System.currentTimeMillis())).apply()
        revision++
val all = store.all
        if (all.size > MaxFolders) {
            val oldest = all.entries.sortedBy { FolderViewRules.stampOf(it.value as? String) }.take(all.size - MaxFolders)
            store.edit().apply { oldest.forEach { remove(it.key) } }.apply()
        }
    }

    private fun roots(): List<String> =
        com.kobblo.gallery.file.explorer.tools.storage.StorageVolumes.snapshot.map { it.path }
            .ifEmpty { listOf(android.os.Environment.getExternalStorageDirectory().absolutePath) }
}
