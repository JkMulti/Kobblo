package com.kobblo.gallery.file.explorer.tools.documents

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.music.MusicLibrary
import com.kobblo.gallery.file.explorer.music.MusicPlayer
import com.kobblo.gallery.file.explorer.music.MusicStore
import com.kobblo.gallery.file.explorer.music.MusicTrack
import com.kobblo.gallery.file.explorer.music.PlaylistFiles
import com.kobblo.gallery.file.explorer.music.canonicalStoragePath
import com.kobblo.gallery.file.explorer.music.trackFromPath
import com.kobblo.gallery.file.explorer.tools.ToolLoading
import com.kobblo.gallery.file.explorer.tools.ToolMenuRow
import com.kobblo.gallery.file.explorer.tools.editor.TextCodec
import com.kobblo.gallery.file.explorer.tools.files.PreviewFrame
import com.kobblo.gallery.file.explorer.tools.files.TableView
import com.kobblo.gallery.file.explorer.tools.files.TextTable
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

// ── Databases ─────────────────────────────────────────────────────────────────

internal data class DatabaseTable(val name: String, val isView: Boolean, val rows: Long?)

/** The SQL a preview runs. A table's name comes from the file, so it's quoted everywhere it appears. */
internal object SqliteQueries {
    const val PREVIEW_ROWS = 200
    const val MAX_COLUMNS = 64
    private const val MAX_CELL = 500

    /** Starts a cell that stands for a blob, followed by its size. A private-use character, which real text all but never starts with. */
    const val BLOB_MARK = '\uE000'

    val ListTables = "SELECT name, type FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%' ORDER BY type, name"

    fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

    fun count(table: String): String = "SELECT COUNT(*) FROM ${quote(table)}"

    fun columns(table: String): String = "PRAGMA table_info(${quote(table)})"

    /** A cell's bytes are never fetched whole: a blob becomes its size after [BLOB_MARK], and long text its start. */
    fun rows(table: String, columns: List<String>): String {
        val cells = columns.take(MAX_COLUMNS).joinToString(", ") { name ->
            val column = quote(name)
            "CASE WHEN typeof($column) = 'blob' THEN char(${BLOB_MARK.code}) || length($column) ELSE substr($column, 1, $MAX_CELL) END"
        }
        return "SELECT $cells FROM ${quote(table)} LIMIT $PREVIEW_ROWS"
    }
}

/**
 * A database opened only as a private copy. SQLite will write beside any database it opens, even just
 * to read one in WAL mode, and Android's default error handler deletes a file it finds corrupt. Neither
 * is acceptable for a preview of someone's data, so the original is never handed to it.
 */
internal class SqlitePreview private constructor(private val db: SQLiteDatabase, private val copy: File) : java.io.Closeable {
    fun tables(): List<DatabaseTable> {
        val found = ArrayList<Pair<String, Boolean>>()
        query(SqliteQueries.ListTables) { cursor ->
            while (cursor.moveToNext() && found.size < MAX_TABLES) {
                found.add(cursor.getString(0) to (cursor.getString(1) == "view"))
            }
        }
        return found.map { (name, isView) ->
            // A view can be arbitrarily slow, and counting one isn't worth finding out.
            val rows = if (isView) {
                null
            } else {
                try {
                    var total: Long? = null
                    query(SqliteQueries.count(name), COUNT_TIMEOUT_MS) { cursor -> if (cursor.moveToFirst()) total = cursor.getLong(0) }
                    total
                } catch (_: Exception) {
                    null
                }
            }
            DatabaseTable(name, isView, rows)
        }
    }

    /** @param blobLabel says how big a blob is, in words, from its size in bytes. */
    fun rows(table: String, blobLabel: (Long) -> String): TextTable {
        val columns = ArrayList<String>()
        query(SqliteQueries.columns(table)) { cursor ->
            while (cursor.moveToNext()) columns.add(cursor.getString(1))
        }
        if (columns.isEmpty()) return TextTable(emptyList(), false)
        val shown = columns.take(SqliteQueries.MAX_COLUMNS)
        val out = ArrayList<List<String>>(SqliteQueries.PREVIEW_ROWS + 1)
        out.add(shown)
        query(SqliteQueries.rows(table, shown)) { cursor ->
            while (cursor.moveToNext()) {
                out.add(List(shown.size) { cell(cursor.getString(it).orEmpty(), blobLabel) })
            }
        }
        return TextTable(out, truncated = out.size > SqliteQueries.PREVIEW_ROWS)
    }

    private fun cell(raw: String, blobLabel: (Long) -> String): String =
        if (raw.startsWith(SqliteQueries.BLOB_MARK)) raw.drop(1).toLongOrNull()?.let(blobLabel) ?: raw else raw

    private fun query(sql: String, timeoutMs: Long = QUERY_TIMEOUT_MS, block: (android.database.Cursor) -> Unit) {
        val signal = CancellationSignal()
        val timer = java.util.Timer(true)
        timer.schedule(object : java.util.TimerTask() {
            override fun run() = signal.cancel()
        }, timeoutMs)
        try {
            db.rawQuery(sql, null, signal).use(block)
        } finally {
            timer.cancel()
        }
    }

    override fun close() {
        try {
            db.close()
        } catch (_: Exception) {
        }
        removeCopy(copy)
    }

    companion object {
        const val MAX_COPY_BYTES = 512L * 1024 * 1024
        private const val MAX_TABLES = 2_000
        private const val QUERY_TIMEOUT_MS = 5_000L
        private const val COUNT_TIMEOUT_MS = 1_500L
        private const val FOLDER = "doc_preview"

        private fun removeCopy(copy: File) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) File(copy.path + suffix).delete()
        }

        /** Null means the file is too big to copy, which only a size check can say up front. */
        suspend fun open(context: Context, source: File): SqlitePreview? = withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, FOLDER)
            folder.mkdirs()
            // Whatever a killed process left behind goes first, so these never pile up.
            folder.listFiles()?.forEach { it.delete() }
            val wal = File(source.path + "-wal")
            val needed = source.length() + (if (wal.isFile) wal.length() else 0L)
            if (needed > MAX_COPY_BYTES || needed * 2 > folder.usableSpace) return@withContext null
            val copy = File(folder, "preview_${System.nanoTime()}.db")
            try {
                copyFile(source, copy)
                if (wal.isFile) copyFile(wal, File(copy.path + "-wal"))
                val db = SQLiteDatabase.openDatabase(
                    copy.path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                    DatabaseErrorHandler { }
                )
                try {
                    // Nothing in the file gets to run anything of its own while this looks at it.
                    db.rawQuery("PRAGMA query_only = ON", null).use { it.moveToFirst() }
                    db.rawQuery("PRAGMA trusted_schema = OFF", null).use { it.moveToFirst() }
                } catch (_: Exception) {
                }
                SqlitePreview(db, copy)
            } catch (e: Throwable) {
                removeCopy(copy)
                throw e
            }
        }

        private suspend fun copyFile(from: File, to: File) {
            val context = coroutineContext
            from.inputStream().use { input ->
                to.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        context.ensureActive()
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }
}

private sealed interface DatabaseLoad {
    data class Ready(val preview: SqlitePreview, val tables: List<DatabaseTable>) : DatabaseLoad
    data object TooBig : DatabaseLoad
    data object Failed : DatabaseLoad
}

private sealed interface RowsLoad {
    data class Ready(val table: TextTable) : RowsLoad
    data object Failed : RowsLoad
}

/**
 * Opens the database at [path] and lists what is in it. If the list can't be read, the private copy
 * that was opened is closed and removed before the failure goes on to whoever asked.
 */
private suspend fun openDatabase(context: Context, path: String): DatabaseLoad {
    val preview = SqlitePreview.open(context, File(path)) ?: return DatabaseLoad.TooBig
    return try {
        DatabaseLoad.Ready(preview, preview.tables())
    } catch (e: Throwable) {
        preview.close()
        throw e
    }
}

/** A database's tables, and the first rows of whichever one is opened. */
@Composable
internal fun SqliteScreen(path: String) {
    val context = LocalContext.current
    var open by remember(path) { mutableStateOf<DatabaseTable?>(null) }
    val load by produceState<DatabaseLoad?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                openDatabase(context, path)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                DatabaseLoad.Failed
            } catch (_: OutOfMemoryError) {
                DatabaseLoad.Failed
            }
        }
    }
    val current = load
    DisposableEffect(current) {
        onDispose { (current as? DatabaseLoad.Ready)?.preview?.close() }
    }
    BackHandler(enabled = open != null) { open = null }

    val table = open
    if (table != null && current is DatabaseLoad.Ready) {
        val rows by produceState<RowsLoad?>(initialValue = null, table) {
            value = withContext(Dispatchers.IO) {
                try {
                    RowsLoad.Ready(
                        current.preview.rows(table.name) { size ->
                            context.resources.getQuantityString(R.plurals.tool_sqlite_blob, size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), size)
                        }
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    RowsLoad.Failed
                } catch (_: OutOfMemoryError) {
                    RowsLoad.Failed
                }
            }
        }
        PreviewFrame(
            path = path,
            title = table.name,
            subtitle = table.rows?.let { pluralStringResource(R.plurals.tools_sqlite_rows, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), it) },
            onBack = { open = null },
            offerText = false
        ) {
            when (val state = rows) {
                null -> ToolLoading()
                RowsLoad.Failed -> KobbloMessage(icon = AppIcons.Alert, title = stringResource(R.string.tools_sqlite_table_failed))
                is RowsLoad.Ready -> {
                    if (state.table.rows.size <= 1) {
                        KobbloMessage(icon = AppIcons.Table, title = stringResource(R.string.tools_table_empty))
                    } else {
                        TableView(state.table, if (state.table.truncated) stringResource(R.string.tools_sqlite_first_rows, SqliteQueries.PREVIEW_ROWS) else null)
                    }
                }
            }
        }
        return
    }

    PreviewFrame(
        path = path,
        subtitle = (current as? DatabaseLoad.Ready)?.let { pluralStringResource(R.plurals.tools_sqlite_tables, it.tables.size, it.tables.size) },
        offerText = false
    ) {
        when (current) {
            null -> ToolLoading()
            DatabaseLoad.Failed -> DocumentProblem(path, locked = false)
            DatabaseLoad.TooBig -> KobbloMessage(icon = AppIcons.Table, title = stringResource(R.string.tools_preview_too_big))
            is DatabaseLoad.Ready -> {
                if (current.tables.isEmpty()) {
                    KobbloMessage(icon = AppIcons.Table, title = stringResource(R.string.tools_sqlite_empty))
                } else {
                    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)) {
                        // No keys: a damaged schema can list the same name twice, which a key would crash on.
                        itemsIndexed(current.tables) { index, item ->
                            KobbloRow(
                                title = item.name,
                                subtitle = when {
                                    item.isView -> stringResource(R.string.tools_sqlite_view)
                                    item.rows != null -> pluralStringResource(R.plurals.tools_sqlite_rows, item.rows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), item.rows)
                                    else -> null
                                },
                                trailing = { KobbloChevron() },
                                showDivider = index < current.tables.lastIndex,
                                onClick = { open = item },
                                modifier = Modifier.groupRow(index, current.tables.size)
                            )
                        }
                        item { KobbloGroupFooter(stringResource(R.string.tools_sqlite_read_only)) }
                    }
                }
            }
        }
    }
}

// ── Playlists ─────────────────────────────────────────────────────────────────

private class LoadedPlaylist(val file: PlaylistFile, val available: List<Boolean>, val resolved: List<String?>)

/** What's on a playlist, and a way to play it. Anything not on this device says so instead of failing. */
@Composable
internal fun PlaylistScreen(path: String, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val scope = rememberCoroutineScope()
    val library = remember { MusicLibrary.get(context) }
    val snapshot by library.snapshot.collectAsState()
    val loaded by produceState<LoadedPlaylist?>(initialValue = null, path, snapshot) {
        value = withContext(Dispatchers.IO) {
            try {
                val source = File(path)
                val bytes = source.inputStream().use { LimitedInputStream(it, DocLimits.MAX_TEXT_FILE_BYTES).readBytes() }
                val playlist = PlaylistReader.read(source.extension.lowercase(), TextCodec.decode(bytes).text, source.parent)
                val root = android.os.Environment.getExternalStorageDirectory().path
                val byName = HashMap<String, String>(snapshot.tracks.size)
                snapshot.tracks.forEach { byName.putIfAbsent(it.fileName.lowercase(), it.path) }
                val resolved = playlist.entries.map { entry ->
                    val named = entry.path ?: return@map null
                    val real = canonicalStoragePath(named, root)
                    when {
                        File(real).isFile -> real
                        // A list made on another device rarely has the same folders.
                        else -> byName[named.substringAfterLast('/').lowercase()]
                    }
                }
                LoadedPlaylist(playlist, resolved.map { it != null }, resolved)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }
    }
    val current = loaded

    fun trackFor(index: Int, list: LoadedPlaylist): MusicTrack? {
        val real = list.resolved[index] ?: return null
        val known = snapshot.byPath[real] ?: trackFromPath(real)
        val entry = list.file.entries[index]
        // A cue sheet's track is a slice of a longer file, so only its own title describes it.
        return if (entry.startMs != null) known.copy(title = entry.title ?: known.title, artist = entry.artist ?: known.artist) else known
    }

    fun play(startIndex: Int, list: LoadedPlaylist) {
        val player = MusicPlayer.get(context)
        val tapped = list.file.entries.getOrNull(startIndex)
        val start = tapped?.startMs
        if (start != null) {
            // Every track of a cue sheet is one recording, so playing a track means moving within it.
            val track = trackFor(startIndex, list) ?: return
            player.play(listOf(track.copy(title = list.file.title ?: track.title)), 0, shuffle = false)
            if (start > 0) player.seekTo(start)
        } else {
            val tracks = ArrayList<MusicTrack>()
            var position = 0
            list.file.entries.indices.forEach { index ->
                val track = trackFor(index, list) ?: return@forEach
                if (index == startIndex) position = tracks.size
                tracks.add(track)
            }
            player.play(tracks, position, shuffle = false)
        }
        onAction(AppAction.SetShowFullScreenPlayer(true))
    }

    val canImport = remember(path) { File(path).extension.lowercase().let { it == "m3u" || it == "m3u8" } }
    val importedMessage = stringResource(R.string.music_import_failed)
    PreviewFrame(
        path = path,
        title = current?.file?.title,
        subtitle = current?.let { pluralStringResource(R.plurals.tools_playlist_entries, it.file.entries.size, it.file.entries.size) },
        extraOptions = if (canImport) { dismiss ->
            KobbloGroup(Modifier.padding(bottom = KobbloSpace.lg)) {
                ToolMenuRow(stringResource(R.string.tools_playlist_add), AppIcons.Playlist) {
                    dismiss()
                    scope.launch {
                        val result = PlaylistFiles.import(MusicStore.get(context), snapshot, path)
                        onAction(
                            AppAction.ShowToast(
                                if (result != null) {
                                    context.getString(R.string.music_imported, result.name, result.matched, result.total)
                                } else {
                                    importedMessage
                                }
                            )
                        )
                    }
                }
            }
        } else {
            null
        }
    ) {
        when {
            current == null -> ToolLoading()
            current.file.entries.isEmpty() -> KobbloMessage(icon = AppIcons.Playlist, title = stringResource(R.string.tools_playlist_empty))
            else -> LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)) {
                val playable = current.available.count { it }
                if (playable > 0) {
                    item(key = "play") {
                        Button(
                            onClick = { play(current.available.indexOf(true), current) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen, vertical = 8.dp)
                        ) {
                            Text(stringResource(R.string.tools_playlist_play))
                        }
                    }
                }
                itemsIndexed(current.file.entries) { index, entry ->
                    val available = current.available[index]
                    val name = entry.title
                        ?: entry.path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
                        ?: entry.remote.orEmpty()
                    KobbloRow(
                        title = name,
                        titleColor = if (available) colors.ink else colors.smoke,
                        subtitle = when {
                            entry.remote != null -> stringResource(R.string.tools_playlist_remote)
                            !available -> stringResource(R.string.tools_playlist_missing)
                            entry.startMs != null -> formatClock(entry.startMs)
                            else -> entry.artist?.takeIf { it.isNotBlank() }
                        },
                        trailing = entry.durationSec?.takeIf { it > 0 }?.let { seconds ->
                            { Text(formatClock(seconds * 1000L), color = colors.smoke) }
                        },
                        showDivider = index < current.file.entries.lastIndex,
                        enabled = available,
                        onClick = { play(index, current) },
                        modifier = Modifier.groupRow(index, current.file.entries.size)
                    )
                }
                if (current.file.truncated) {
                    item { KobbloGroupFooter(stringResource(R.string.tools_doc_truncated)) }
                }
            }
        }
    }
}

/** A row of an inset group in a lazy list, which can't use `KobbloGroup` around rows it hasn't made yet. */
@Composable
private fun Modifier.groupRow(index: Int, count: Int): Modifier =
    padding(horizontal = KobbloSpace.screen)
        .clip(KobbloShapes.groupItem(index, count))
        .background(KobbloDesign.colors.snow)

private fun formatClock(millis: Long): String {
    val seconds = millis / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
