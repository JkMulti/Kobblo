package com.kobblo.gallery.file.explorer.explorer.search

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import com.kobblo.gallery.file.explorer.DayLabels
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.explorer.FastStat
import com.kobblo.gallery.file.explorer.stableFileId
import com.kobblo.gallery.file.explorer.tools.storage.StorageVolumes
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Where a search stands: everything found so far, best first. */
internal class SearchSnapshot(val files: List<FileItem>, val done: Boolean, val capped: Boolean)

/**
 * Finds files by name. From Android 11 the system's index answers first, in milliseconds. The disk
 * then fills the gaps the index leaves: it has no row for an empty folder, and it may not know a card
 * that was written to elsewhere. Before Android 11 writes don't all reach the index, so the disk is
 * the only answer, and matches appear as the walk reaches them.
 */
internal object SearchEngine {
    /** Enough for any list a person will read, and a bound on memory when one letter matches everything. */
    const val MaxResults = 20_000

    private const val TypingPauseMillis = 120L
    private const val WalkTypingPauseMillis = 280L
    private const val EmitEveryMillis = 200L
    private const val FirstScreenful = 200
    private const val LooseEntriesPauseMillis = 400L

    suspend fun run(
        context: Context,
        repository: FileRepository,
        query: String,
        filters: SearchFilters,
        location: String,
        showHidden: Boolean,
        current: List<FileItem>,
        onUpdate: (SearchSnapshot) -> Unit
    ) = withContext(Dispatchers.IO) {
        val text = SearchText(query)
        val scope = if (filters.everywhere) null else location.takeIf { it.startsWith("/") }
        // Standing inside a hidden folder means wanting what's in it.
        val hidden = showHidden || scope?.contains("/.") == true
        val locked = repository.getLockedFiles()
        val matcher = SearchMatcher(text, filters, scope, hidden, locked)
        val found = Collector(text, repository.getPinnedFiles(), locked)
        val indexAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        // What's already on screen answers at once, so typing another letter narrows the list straight
        // away rather than leaving it stale until the next query returns.
        if (!text.isBlank) {
            val narrowed = current.filter {
                matcher.matches(it.path, it.name, it.type == "folder", 0, it.sizeBytes, it.lastModified)
            }
            onUpdate(SearchSnapshot(narrowed, done = false, capped = false))
        }
        delay(if (indexAvailable) TypingPauseMillis else WalkTypingPauseMillis)

        val startedAt = android.os.SystemClock.elapsedRealtime()
        // Newest first, so a query matching thousands has something to show well before it's all read.
        val indexed = indexAvailable && readIndex(context, text, filters, scope, hidden, matcher, found) {
            onUpdate(found.snapshot(done = false))
        }
        val indexMillis = android.os.SystemClock.elapsedRealtime() - startedAt
        val walkRoots = walkRoots(indexed, hidden, scope)
        // Where nothing has to be walked, this is the answer. What the disk might add is rare and shows up
        // on its own, so nobody should watch a spinner for it, least of all when nothing matched.
        if (indexed) onUpdate(found.snapshot(done = walkRoots.isEmpty()))

        var lastEmit = System.currentTimeMillis()
        val emit = {
            val now = System.currentTimeMillis()
            if (now - lastEmit >= EmitEveryMillis) {
                lastEmit = now
                onUpdate(found.snapshot(done = false))
            }
        }
        for (root in walkRoots) walk(File(root), hidden, locked, matcher, found, emit)
        if (walkRoots.isNotEmpty() || !indexed) onUpdate(found.snapshot(done = true))
        if (indexed && !text.isBlank && !hidden) {
            // Not until typing has really paused, since this reads every folder's names.
            delay(LooseEntriesPauseMillis)
            val before = found.count
            looseEntries(context, text, scope, matcher, found, walkRoots)
            if (found.count != before) onUpdate(found.snapshot(done = true))
        }
        if (com.kobblo.gallery.file.explorer.BuildConfig.DEBUG) {
            android.util.Log.i("ExplorerSearch", "${found.count} found: index $indexMillis ms, disk ${android.os.SystemClock.elapsedRealtime() - startedAt - indexMillis} ms, walked ${walkRoots.size} roots")
        }
    }

    /**
     * The folders to walk in full. Without the index, that's everything in scope. With it, only a card or
     * drive, which can be written to on another device. Hidden files are the other reason, because the
     * scanner passes over hidden folders that were there before it first ran.
     */
    private fun walkRoots(indexed: Boolean, hidden: Boolean, scope: String?): List<String> {
        val volumes = StorageVolumes.snapshot
        if (!indexed || hidden) return if (scope != null) listOf(scope) else volumes.map { it.path }
        return if (scope != null) {
            listOfNotNull(scope.takeIf { StorageVolumes.forPath(it)?.isPrimary == false })
        } else {
            volumes.filterNot { it.isPrimary }.map { it.path }
        }
    }

    private suspend fun readIndex(
        context: Context,
        text: SearchText,
        filters: SearchFilters,
        scope: String?,
        hidden: Boolean,
        matcher: SearchMatcher,
        found: Collector,
        onFirstScreenful: () -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val job = currentCoroutineContext()
        val built = IndexSelectionBuilder.build(text, filters, scope, hidden)
        // A word with a non-ASCII letter is only partly checked by the query, so a limit there could
        // cut off real matches. The rows it lets through are still far fewer than a whole library.
        val exact = text.tokens.all { token -> token.all { it.code < 128 } }
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, built.selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, built.args.toTypedArray())
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_modified DESC")
            if (exact) putString(ContentResolver.QUERY_ARG_SQL_LIMIT, MaxResults.toString())
            // A file written straight to storage can stay "pending" until something scans it.
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val cursor = try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(BaseColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, "media_type", "format"),
                args,
                null
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SearchEngine", "The file index wouldn't answer", e)
            null
        } ?: return false
        cursor.use { c ->
            while (c.moveToNext()) {
                if (c.position % 512 == 0) job.ensureActive()
                if (found.isFull) break
                val path = c.getString(1) ?: continue
                val name = path.substringAfterLast('/')
                val isFolder = c.getInt(5) == FOLDER_FORMAT
                val size: Long
                val modified: Long
                if (c.isNull(3) || (!isFolder && c.isNull(2))) {
                    // The scanner hasn't reached this one, so the disk has to say. Anything gone is skipped.
                    val stat = FastStat.of(File(path)) ?: continue
                    size = if (isFolder) 0L else stat.size
                    modified = stat.modifiedMillis
                } else {
                    size = if (isFolder) 0L else c.getLong(2)
                    modified = c.getLong(3) * 1000L
                }
                if (matcher.matches(path, name, isFolder, c.getInt(4), size, modified)) {
                    found.add(path, name, isFolder, size, modified)
                    if (found.count == FirstScreenful) onFirstScreenful()
                }
            }
        }
        return true
    }

    /** Looks through a tree one folder at a time. One stat per entry, and nothing kept but matches. */
    private suspend fun walk(
        root: File,
        hidden: Boolean,
        locked: Set<String>,
        matcher: SearchMatcher,
        found: Collector,
        emit: () -> Unit
    ) {
        val job = currentCoroutineContext()
        val pending = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty() && !found.isFull) {
            job.ensureActive()
            val folder = pending.removeLast()
            val names = folder.list() ?: continue
            for (name in names) {
                if (!hidden && name.startsWith(".")) continue
                if (name == IndexSelectionBuilder.BIN_FOLDER || isRestricted(folder, name)) continue
                val child = File(folder, name)
                val stat = try {
                    Os.lstat(child.path)
                } catch (_: Exception) {
                    continue
                }
                val isFolder = OsConstants.S_ISDIR(stat.st_mode)
                // A folder that's locked is listed but never read, and a link could lead back up the tree.
                if (isFolder && child.path !in locked) pending.addLast(child)
                val size = if (isFolder) 0L else stat.st_size
                val modified = stat.st_mtime * 1000L
                if (matcher.matches(child.path, name, isFolder, 0, size, modified)) {
                    found.add(child.path, name, isFolder, size, modified)
                }
            }
            emit()
        }
    }

    /**
     * Whatever is on disk that the index has no row for, which is mostly empty folders. The index knows
     * every folder with a file in it, so reading those folders' names costs a listing each rather than a
     * stat for every file, and only a name that matches is looked at any closer.
     */
    private suspend fun looseEntries(
        context: Context,
        text: SearchText,
        scope: String?,
        matcher: SearchMatcher,
        found: Collector,
        alreadyWalked: List<String>
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val job = currentCoroutineContext()
        val folders = LinkedHashSet<String>()
        if (scope != null) folders.add(scope) else StorageVolumes.snapshot.forEach { folders.add(it.path) }
        val built = IndexSelectionBuilder.foldersIn(scope, showHidden = false)
        val cursor = try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.MediaColumns.DATA),
                Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, built.selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, built.args.toTypedArray())
                },
                null
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        cursor.use { c -> while (c.moveToNext()) c.getString(0)?.let { folders.add(it) } }

        for (folder in folders) {
            job.ensureActive()
            if (found.isFull) return
            if (alreadyWalked.any { folder == it || folder.startsWith("$it/") }) continue
            val names = File(folder).list() ?: continue
            for (name in names) {
                if (name.startsWith(".") || !text.matches(name)) continue
                if (isRestricted(File(folder), name)) continue
                val path = "$folder/$name"
                if (found.has(path)) continue
                val stat = try {
                    Os.lstat(path)
                } catch (_: Exception) {
                    continue
                }
                val isFolder = OsConstants.S_ISDIR(stat.st_mode)
                val size = if (isFolder) 0L else stat.st_size
                val modified = stat.st_mtime * 1000L
                if (matcher.matches(path, name, isFolder, 0, size, modified)) {
                    found.add(path, name, isFolder, size, modified)
                }
            }
        }
    }

    private fun isRestricted(parent: File, name: String): Boolean =
        (name == "data" || name == "obb") && parent.name == "Android"

    private const val FOLDER_FORMAT = 12289

    /** The results, kept with what they're sorted by so a long list isn't scored again on every update. */
    private class Collector(
        private val text: SearchText,
        private val pinned: Set<String>,
        private val locked: Set<String>
    ) {
        private class Scored(val file: FileItem, val rank: Int)

        private val dates = DayLabels()
        private val seen = HashSet<String>()
        private val items = ArrayList<Scored>()
        private val usedIds = HashSet<Int>()
        private var capped = false

        val isFull: Boolean get() = (items.size >= MaxResults).also { if (it) capped = true }

        val count: Int get() = items.size

        fun has(path: String): Boolean = path in seen

        fun add(path: String, name: String, isFolder: Boolean, size: Long, modified: Long) {
            if (!seen.add(path) || isFull) return
            val type = when {
                isFolder -> "folder"
                // Only a .ts needs its bytes looked at, which is why this takes a file for just that.
                FileKinds.extensionOf(name) == "ts" -> FileKinds.typeOf(File(path))
                else -> FileKinds.typeOf(name)
            }
            items.add(
                Scored(
                    FileItem(
                        // The same id a folder's own list gives this file, so a row stays put as a list narrows.
                        id = stableFileId(path, usedIds),
                        path = path,
                        name = name,
                        type = type,
                        kind = when (type) {
                            "folder" -> "folder"
                            "image", "video", "audio" -> "media"
                            "archive" -> "archive"
                            "doc" -> "doc"
                            else -> "file"
                        },
                        size = if (isFolder) "" else FileSizes.label(size),
                        changed = dates.labelFor(modified),
                        owner = "Me",
                        isPinned = path in pinned,
                        isLocked = path in locked,
                        sizeBytes = size,
                        lastModified = modified
                    ),
                    text.rank(name)
                )
            )
        }

        fun snapshot(done: Boolean): SearchSnapshot =
            SearchSnapshot(
                items.sortedWith(ORDER).map { it.file },
                done = done,
                capped = capped
            )

        private companion object {
            val ORDER = Comparator<Scored> { a, b ->
                when {
                    a.rank != b.rank -> a.rank - b.rank
                    a.file.lastModified != b.file.lastModified -> b.file.lastModified.compareTo(a.file.lastModified)
                    else -> a.file.path.compareTo(b.file.path)
                }
            }
        }
    }
}
