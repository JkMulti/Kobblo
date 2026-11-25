package com.kobblo.gallery.file.explorer.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.ui.hub.HubRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * For work a sheet starts and shouldn't die with it: a sheet leaves composition the moment it's
 * dismissed, which would cancel its own scope halfway through a rename.
 */
internal val GalleryWorkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** The full screens of this area. Open one with `Hub.open(...)`. */
sealed interface GalleryRoute : HubRoute {
    data object LibraryStats : GalleryRoute
    data object SimilarPhotos : GalleryRoute
}

@Composable
fun GalleryRouteContent(route: GalleryRoute, state: AppState, onAction: (AppAction) -> Unit) {
    when (route) {
        GalleryRoute.LibraryStats -> LibraryStatsScreen(state)
        GalleryRoute.SimilarPhotos -> com.kobblo.gallery.file.explorer.gallery.similar.SimilarPhotosScreen(onAction)
    }
}

/**
 * Where the Home tab has gone. Albums are drawn inside the tab rather than over it, so the tab bar, the
 * selection bar, the file menu and the viewer all behave exactly as they do on the feed.
 */
sealed interface GalleryDest {
    data object Albums : GalleryDest
    data class Folder(val path: String, val name: String) : GalleryDest
    data class Smart(val kind: SmartKind) : GalleryDest
    data object Search : GalleryDest
    data object Places : GalleryDest
    data class Place(val place: Int, val name: String) : GalleryDest
    data class Country(val code: String, val name: String) : GalleryDest
    data object Tags : GalleryDest
    data class Tag(val name: String) : GalleryDest
    data object People : GalleryDest
    data class Person(val id: Long, val name: String) : GalleryDest
}

/** Where an album's remembered sort is filed. A folder's is its path, so a rename can carry it along. */
internal fun GalleryDest.sortKey(): String? = when (this) {
    is GalleryDest.Folder -> path
    is GalleryDest.Smart -> "smart:$kind"
    is GalleryDest.Place -> "place:$place"
    is GalleryDest.Country -> "country:$code"
    is GalleryDest.Tag -> "tag:$name"
    is GalleryDest.Person -> "person:$id"
    else -> null
}

object GalleryNav {
    private val stack = mutableStateListOf<GalleryDest>()

    val current: GalleryDest? get() = stack.lastOrNull()
    val depth: Int get() = stack.size

    fun open(dest: GalleryDest) {
        stack.add(dest)
    }

    fun back() {
        stack.removeLastOrNull()
    }

    fun backToRoot() {
        stack.clear()
    }

    /** Swaps the current album, for when a rename changes what it's called. */
    fun replace(dest: GalleryDest) {
        if (stack.isNotEmpty()) stack[stack.lastIndex] = dest
    }

    /** Counts taps on the Home tab while already on it, so the root feed can scroll back to the top. */
    var homeTaps by mutableIntStateOf(0)
        private set

    /** Tapping the tab you are on returns to its root, as on iOS. */
    fun onHomeTabTapped(alreadyHome: Boolean) {
        if (!alreadyHome) return
        if (stack.isNotEmpty()) stack.clear() else homeTaps++
    }
}

/**
 * What the selection bar and the viewer need to know about the feed on screen, without either of them
 * having to know albums exist. While the tab shows part of the library, Select All and swiping through
 * photos stay within that part.
 */
object GalleryScope {
    private var onScreen by mutableStateOf<List<FileItem>?>(null)

    /** The photo the viewer is on right now, for the grid under it to keep up with. */
    var viewerAt by mutableStateOf<String?>(null)

    /** Set from the selection bar, and answered by the Home tab with its album picker. */
    var pickAlbum by mutableStateOf(false)

    /** Set from the selection bar's More, and answered by the Home tab with its sheet. */
    var showBulkActions by mutableStateOf(false)

    /** Whether the picker opens set to move rather than copy. */
    var pickMoves by mutableStateOf(true)
        private set

    /**
     * Home has no folder to paste into, and a paste bar there would replace the tabs needed to reach
     * one, so Copy and Cut on the feed choose an album instead.
     */
    fun requestAlbumPick(move: Boolean) {
        pickMoves = move
        pickAlbum = true
    }

    fun setVisible(files: List<FileItem>) {
        onScreen = files
    }

    /** The files the viewer swipes through. */
    fun viewerFiles(location: String, allFiles: List<FileItem>): List<FileItem> =
        if (location == "gallery") onScreen ?: allFiles else allFiles

    /**
     * What the viewer pages through: [files] with no path twice, and always including the photo open
     * now. Everything here goes by path, because the viewer's strip is keyed by it, and a photo saved
     * over its original can come back under a new id while the old item is still open. A locked photo
     * only appears once its password has opened it, or swiping would walk straight past the lock.
     */
    fun viewerList(current: FileItem, files: List<FileItem>): List<FileItem> {
        val unique = files.distinctBy { it.path }.filter { !it.isLocked || it.path == current.path }
        return if (unique.none { it.path == current.path }) listOf(current) + unique else unique
    }

    /** The ids Select All takes, or null where the default of every file in the folder is right. */
    fun selectableIds(location: String): List<Int>? =
        if (location == "gallery") onScreen?.filterNot { it.isLocked }?.map { it.id } else null
}
