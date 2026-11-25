package com.kobblo.gallery.file.explorer.session

import androidx.annotation.StringRes
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reads what's at a location. */
internal fun interface LocationReader {
    /** @param firstRows takes the start of a list that's long in coming, to go on screen while the rest is read. */
    suspend fun read(location: String, firstRows: suspend (List<FileItem>) -> Unit): List<FileItem>
}

/** The threads a load runs on: one to read, one to sort, and the screen's own. */
internal class LoaderThreads(
    val reading: CoroutineDispatcher = Dispatchers.IO,
    val sorting: CoroutineDispatcher = Dispatchers.Default,
    val screen: CoroutineDispatcher = Dispatchers.Main
)

/**
 * Reads the open location and puts it on screen. Only one read is under way at a time: a new one stops the
 * last, and from then on the last one changes nothing, however late its answer comes.
 */
internal class FolderLoader(
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val words: Words,
    private val reader: LocationReader,
    private val contents: FolderContents,
    private val arrangement: Arrangement,
    private val outside: Outside,
    private val threads: LoaderThreads = LoaderThreads()
) {
    /** What a load asks of the rest of the app. */
    interface Outside {
        /** Whether these words, or the search's filters, mean a search is what's on screen. */
        fun isSearch(query: String): Boolean

        /** Asks the search on screen again, keeping what's chosen. */
        fun searchAgain(query: String)

        /** The favorites that Browse lists along with its locations. */
        fun favorites(): ImmutableList<String>

        /** Adds up the categories that Browse lists along with its locations. */
        fun countCategories()

        /** Times a read, for finding where a tab switch drags. What it answers with is told how many files came. */
        fun timeRead(location: String): (count: Int) -> Unit
    }

    private var reading: Job? = null

    fun load(location: String) = when (location) {
        // Not a list of files, so whatever is still being read may go on, and be remembered for when it's wanted.
        Places.Send -> live.update { it.copy(location = location, files = persistentListOf(), isLoading = false) }
        Places.Locations -> showLocations()
        else -> read(location)
    }

    private fun showLocations() {
        reading?.cancel()
        // Otherwise clearing a search here would bring back the last folder's files.
        contents.all = emptyList()
        val favorites = outside.favorites()
        live.update { it.copy(files = persistentListOf(), favoritePaths = favorites, isLoading = false) }
        outside.countCategories()
    }

    private fun read(location: String) {
        reading?.cancel()
        reading = scope.launch(threads.reading) {
            try {
                val came = outside.timeRead(location)
                show(location, reader.read(location, ::showFirstRows), came)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserRecoverableAuthIOException) {
                showFailure(e, R.string.app_auth_required) { copy(recoverableAuthIntent = e.intent) }
            } catch (e: Exception) {
                showFailure(e, R.string.app_folder_open_failed)
            }
        }
    }

    private suspend fun showFirstRows(rows: List<FileItem>) {
        stopIfLeft()
        val start = rows.toImmutableList()
        live.update { it.copy(files = start, galleryFiles = start, isLoading = false) }
    }

    private suspend fun show(location: String, files: List<FileItem>, came: (count: Int) -> Unit) {
        stopIfLeft()
        contents.all = files
        val state = live.now
        // Something changed while searching. The folder's own matches aren't what's on screen, so the search
        // asks again rather than the list shrinking to this one folder.
        if (state.location == location && outside.isSearch(state.query)) {
            withContext(threads.screen) { outside.searchAgain(state.query) }
            return
        }
        val shown = withContext(threads.sorting) { arrangement.of(files, state).toImmutableList() }
        came(files.size)
        live.update { it.withListing(location, shown) }
    }

    private suspend fun showFailure(failure: Exception, @StringRes explanation: Int, also: AppState.() -> AppState = { this }) {
        failure.printStackTrace()
        stopIfLeft()
        contents.all = emptyList()
        val message = words.text(explanation)
        live.update { it.copy(files = persistentListOf(), isLoading = false, errorMessage = message).also() }
    }

    /**
     * Some reading can't be interrupted, so it can come back after another location has been opened. Nobody
     * is waiting for it then, whether it went well or not.
     */
    private suspend fun stopIfLeft() = currentCoroutineContext().ensureActive()
}
