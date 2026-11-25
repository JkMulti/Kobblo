package com.kobblo.gallery.file.explorer.tools.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.RouteMemory
import com.kobblo.gallery.file.explorer.tools.ToolLoading
import com.kobblo.gallery.file.explorer.tools.ToolMenuRow
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.tools.editor.EditorToolbar
import com.kobblo.gallery.file.explorer.tools.editor.NoteLine
import com.kobblo.gallery.file.explorer.tools.editor.TextCodec
import com.kobblo.gallery.file.explorer.tools.picker.MimeFilter
import com.kobblo.gallery.file.explorer.tools.picker.PickerScreen
import com.kobblo.gallery.file.explorer.ui.expressive.DetailRow
import com.kobblo.gallery.file.explorer.ui.expressive.formatFileSize
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSearchField
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

private const val MAX_RESULTS = 500

@Composable
internal fun ContentSearchScreen(root: String) {
    val colors = KobbloDesign.colors
    // Opening a match draws over this screen, so the search has to survive coming back.
    val memoryKey = "search:$root"
    var query by remember { mutableStateOf(RouteMemory.get(memoryKey).orEmpty()) }
    fun leave() {
        RouteMemory.put(memoryKey, null)
        Hub.back()
    }
    BackHandler { leave() }
    val results = remember { mutableStateListOf<ContentMatch>() }
    var filesRead by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    val rootName = remember(root) { File(root).name.ifEmpty { root } }
    val appContext = LocalContext.current.applicationContext

    LaunchedEffect(query) {
        results.clear()
        filesRead = 0
        if (query.length < 2) {
            searching = false
            return@LaunchedEffect
        }
        delay(350)
        searching = true
        val running = coroutineContext
        val pending = ArrayList<ContentMatch>()
        var total = 0
        fun showPending() = synchronized(pending) {
            results.addAll(pending)
            pending.clear()
        }
        // Matches appear as they're found, since a big folder can take a while to read through.
        val showing = launch {
            while (true) {
                delay(250)
                showPending()
            }
        }
        withContext(Dispatchers.IO) {
            // What's in a locked file is exactly what this would otherwise print on the screen.
            val locks = com.kobblo.gallery.file.explorer.fileops.Locks.forLists(appContext)
            ContentSearcher.search(
                root = File(root),
                query = query,
                onFile = { filesRead = it },
                checkCancelled = { running.ensureActive() },
                skip = { locks.covers(it.path) },
                onMatch = { match ->
                    synchronized(pending) {
                        pending.add(match)
                        total++
                        total < MAX_RESULTS
                    }
                }
            )
        }
        showing.cancel()
        showPending()
        searching = false
    }

    val grouped = remember(results.size) { results.groupBy { it.path } }

    Column(Modifier.fillMaxSize().background(colors.canvas).statusBarsPadding().navigationBarsPadding()) {
        EditorToolbar(title = stringResource(R.string.tools_search_in_files), subtitle = rootName, onBack = { leave() }) {}
        KobbloSearchField(
            query = query,
            onQueryChange = {
                query = it
                RouteMemory.put(memoryKey, it)
            },
            placeholder = stringResource(R.string.tools_search_hint),
            modifier = Modifier.padding(horizontal = KobbloSpace.screen, vertical = 4.dp)
        )
        when {
            query.length < 2 -> NoteLine(stringResource(R.string.tools_search_start, rootName))
            searching -> NoteLine(stringResource(R.string.tools_search_searching, filesRead))
            results.isEmpty() -> KobbloMessage(icon = AppIcons.Search, title = stringResource(R.string.tools_search_empty))
            else -> NoteLine(pluralStringResource(R.plurals.tools_search_done, results.size, results.size, grouped.size, filesRead))
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            grouped.forEach { (path, matches) ->
                item(key = path) {
                    KobbloGroupHeader(File(path).name)
                    KobbloGroup {
                        matches.forEachIndexed { index, match ->
                            KobbloRow(
                                title = match.text,
                                subtitle = stringResource(R.string.tools_search_line, match.line),
                                showDivider = index < matches.lastIndex,
                                onClick = { Hub.open(ToolsRoute.TextFile(path, goToLine = match.line)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed interface CompareResult {
    data class Done(
        val identical: Boolean,
        val firstSize: Long,
        val secondSize: Long,
        val changes: List<DiffLine>?,
        val note: Int?
    ) : CompareResult

    data object Failed : CompareResult
}

/** Two files, side by side in the only ways that matter: are they the same, and if not, where not. */
@Composable
internal fun CompareScreen(first: String, second: String?) {
    val colors = KobbloDesign.colors
    var other by remember { mutableStateOf(second) }
    var pickOther by remember { mutableStateOf(second == null) }
    val firstFile = remember(first) { File(first) }
    val otherPath = other

    val result by produceState<CompareResult?>(initialValue = null, first, otherPath) {
        value = null
        if (otherPath == null) return@produceState
        value = withContext(Dispatchers.IO) { compare(File(first), File(otherPath)) }
    }

    Column(Modifier.fillMaxSize().background(colors.canvas).statusBarsPadding().navigationBarsPadding()) {
        EditorToolbar(title = stringResource(R.string.tools_compare_title), subtitle = null, onBack = { Hub.back() }) {}
        if (otherPath == null) {
            KobbloGroup {
                ToolMenuRow(stringResource(R.string.tools_compare_choose), AppIcons.File) { pickOther = true }
            }
        } else {
            when (val done = result) {
                null -> ToolLoading(label = stringResource(R.string.tools_compare_checking))
                CompareResult.Failed -> KobbloMessage(icon = AppIcons.Alert, title = stringResource(R.string.tools_editor_open_failed))
                is CompareResult.Done -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "summary") {
                        KobbloMessage(
                            icon = if (done.identical) AppIcons.CheckCircle else AppIcons.Compare,
                            title = stringResource(if (done.identical) R.string.tools_compare_identical else R.string.tools_compare_different)
                        )
                        KobbloGroup {
                            DetailRow(firstFile.name, formatFileSize(done.firstSize))
                            DetailRow(File(otherPath).name, formatFileSize(done.secondSize))
                        }
                        done.note?.let { NoteLine(stringResource(it)) }
                    }
                    if (!done.changes.isNullOrEmpty()) {
                        item(key = "changes_header") { KobbloGroupHeader(stringResource(R.string.tools_compare_changes)) }
                        items(done.changes) { line -> DiffRow(line) }
                    }
                }
            }
        }
    }

    if (pickOther) {
        // Usually the other file is right beside the first, so that's where choosing starts.
        PickerScreen(
            filter = remember { MimeFilter(emptyList()) },
            allowMultiple = false,
            requesterName = null,
            startAt = firstFile.parent,
            onCancel = {
                pickOther = false
                if (other == null) Hub.back()
            },
            onPicked = { paths ->
                pickOther = false
                other = paths.firstOrNull()
                if (other == null) Hub.back()
            }
        )
    }
}

private val DiffStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
private fun DiffRow(line: DiffLine) {
    val colors = KobbloDesign.colors
    val (mark, tint) = when (line.kind) {
        DiffLine.Kind.SAME -> " " to Color.Transparent
        DiffLine.Kind.REMOVED -> "−" to KobbloTint.Red.copy(alpha = 0.14f)
        DiffLine.Kind.ADDED -> "+" to KobbloTint.Green.copy(alpha = 0.16f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.snow)
            .background(tint)
            .padding(horizontal = 12.dp, vertical = 1.dp)
    ) {
        Text(
            text = (line.newLine ?: line.oldLine ?: 0).toString().padStart(5),
            style = DiffStyle,
            color = colors.smoke,
            maxLines = 1
        )
        Spacer(Modifier.width(6.dp))
        Text(mark, style = DiffStyle, color = colors.ink, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(line.text, style = DiffStyle, color = colors.ink, overflow = TextOverflow.Ellipsis, maxLines = 3)
    }
}

private const val MAX_DIFF_BYTES = 2L * 1024 * 1024

private fun compare(first: File, second: File): CompareResult = try {
    val identical = first.length() == second.length() && sameBytes(first, second)
    if (identical || first.length() > MAX_DIFF_BYTES || second.length() > MAX_DIFF_BYTES) {
        CompareResult.Done(
            identical = identical,
            firstSize = first.length(),
            secondSize = second.length(),
            changes = null,
            note = if (identical) null else R.string.tools_compare_too_big_note
        )
    } else {
        val a = first.readBytes()
        val b = second.readBytes()
        if (!TextCodec.looksLikeText(a.copyOf(minOf(a.size, 4096))) || !TextCodec.looksLikeText(b.copyOf(minOf(b.size, 4096)))) {
            CompareResult.Done(false, first.length(), second.length(), null, R.string.tools_compare_binary_note)
        } else {
            val oldLines = TextCodec.decode(a).text.split('\n')
            val newLines = TextCodec.decode(b).text.split('\n')
            if (LineDiff.isTooLarge(oldLines.size, newLines.size)) {
                CompareResult.Done(false, first.length(), second.length(), null, R.string.tools_compare_too_big_note)
            } else {
                val changes = LineDiff.changesOnly(LineDiff.diff(oldLines, newLines))
                CompareResult.Done(
                    identical = false,
                    firstSize = first.length(),
                    secondSize = second.length(),
                    changes = changes,
                    note = if (changes.isEmpty()) R.string.tools_compare_no_line_changes else null
                )
            }
        }
    }
} catch (_: Exception) {
    CompareResult.Failed
}

private fun sameBytes(first: File, second: File): Boolean {
    first.inputStream().buffered().use { a ->
        second.inputStream().buffered().use { b ->
            val left = ByteArray(64 * 1024)
            val right = ByteArray(64 * 1024)
            while (true) {
                val read = a.read(left)
                if (read < 0) return b.read() < 0
                var filled = 0
                while (filled < read) {
                    val more = b.read(right, filled, read - filled)
                    if (more < 0) return false
                    filled += more
                }
                for (i in 0 until read) if (left[i] != right[i]) return false
            }
        }
    }
}
