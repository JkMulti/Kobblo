package com.kobblo.gallery.file.explorer.tools.editor

import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.ToolMenuRow
import com.kobblo.gallery.file.explorer.tools.ToolSheet
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSwitch
import com.kobblo.gallery.file.explorer.ui.expressive.formatFileSize
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSearchField
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

private val LineStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

/** Reads lines on demand and remembers the ones around the screen. */
private class LineReader(private val index: LineIndex) {
    private val cache = LruCache<Int, String>(1024)
    private val lock = Mutex()
    private var source: RandomAccessFile? = null

    fun peek(line: Int): String? = cache.get(line)

    suspend fun load(line: Int): String = lock.withLock {
        cache.get(line) ?: withContext(Dispatchers.IO) {
            val file = source ?: index.open().also { source = it }
            index.read(line, file).also { cache.put(line, it) }
        }
    }

    fun close() {
        try {
            source?.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * A file too big to edit: every line reachable, but only the ones on screen in memory. It works the
 * same for a gigabyte of logs as for a two-megabyte file.
 */
@Composable
internal fun LargeTextScreen(path: String, index: LineIndex, onReopen: (Charset) -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val reader = remember(index) { LineReader(index) }
    val prefs = remember { context.getSharedPreferences("tools_prefs", android.content.Context.MODE_PRIVATE) }
    var lineNumbers by remember { mutableStateOf(prefs.getBoolean("editor_line_numbers", false)) }
    var showOptions by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var showEncodings by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }

    DisposableEffect(reader) { onDispose { reader.close() } }
    BackHandler(enabled = showFind) { showFind = false }

    val file = remember(path) { File(path) }
    val digits = remember(index) { index.lineCount.toString().length }

    fun findNext() {
        if (query.isEmpty() || searching) return
        searching = true
        notFound = false
        scope.launch {
            val from = listState.firstVisibleItemIndex + 1
            val job = coroutineContext
            val found = withContext(Dispatchers.IO) {
                index.findNext(query, from) { job.isActive }.takeIf { it >= 0 }
                    ?: index.findNext(query, 0) { job.isActive }
            }
            searching = false
            if (found >= 0) listState.scrollToItem(found) else notFound = true
        }
    }

    Column(Modifier.fillMaxSize().background(colors.canvas).statusBarsPadding().navigationBarsPadding()) {
        EditorToolbar(
            title = file.name,
            subtitle = stringResource(R.string.tools_editor_read_only),
            onBack = { Hub.back() }
        ) {
            KobbloIconButton(
                icon = AppIcons.MoreHoriz,
                description = stringResource(R.string.tools_options),
                onClick = { showOptions = true }
            )
        }
        NoteLine(stringResource(R.string.tools_editor_too_large, formatFileSize(file.length()), index.lineCount))

        if (showFind) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KobbloSearchField(
                    query = query,
                    onQueryChange = {
                        query = it
                        notFound = false
                    },
                    placeholder = stringResource(R.string.tools_editor_find),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(4.dp))
                if (notFound) {
                    Text(stringResource(R.string.tools_editor_no_match), color = colors.smoke, style = com.kobblo.gallery.file.explorer.ui.design.KobbloType.caption)
                    Spacer(Modifier.width(4.dp))
                }
                KobbloIconButton(
                    icon = AppIcons.ChevronDown,
                    description = stringResource(R.string.tools_editor_next),
                    onClick = { findNext() },
                    size = 34.dp,
                    iconSize = 18.dp,
                    tint = if (searching) colors.pewter else colors.action,
                    background = androidx.compose.ui.graphics.Color.Transparent
                )
                KobbloIconButton(
                    icon = AppIcons.Close,
                    description = stringResource(R.string.tools_editor_close_find),
                    onClick = { showFind = false },
                    size = 34.dp,
                    iconSize = 16.dp,
                    tint = colors.smoke,
                    background = androidx.compose.ui.graphics.Color.Transparent
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().background(colors.snow)
        ) {
            items(index.lineCount, contentType = { "line" }) { line ->
                val text by produceState(initialValue = reader.peek(line), line, reader) {
                    value = reader.load(line)
                }
                Row(modifier = Modifier.fillMaxWidth().heightIn(min = 18.dp).padding(horizontal = 12.dp)) {
                    if (lineNumbers) {
                        Text(
                            text = (line + 1).toString().padStart(digits),
                            style = LineStyle,
                            color = colors.smoke,
                            maxLines = 1,
                            softWrap = false
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(text = text.orEmpty(), style = LineStyle, color = colors.ink)
                }
            }
        }
    }

    if (showOptions) {
        ToolSheet(title = stringResource(R.string.tools_options), onDismiss = { showOptions = false }) {
            KobbloGroup {
                ToolMenuRow(stringResource(R.string.tools_editor_find), AppIcons.Search, showDivider = true) {
                    showOptions = false
                    showFind = true
                }
                ToolMenuRow(stringResource(R.string.tools_editor_go_to_line), AppIcons.List) {
                    showOptions = false
                    showGoTo = true
                }
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                KobbloRow(
                    title = stringResource(R.string.tools_editor_line_numbers),
                    trailing = {
                        ExpressiveSwitch(checked = lineNumbers, onCheckedChange = {
                            lineNumbers = it
                            prefs.edit().putBoolean("editor_line_numbers", it).apply()
                        })
                    }
                )
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                ToolMenuRow(
                    stringResource(R.string.tools_editor_encoding),
                    AppIcons.ChevronRight,
                    trailingText = index.charset.name(),
                    showDivider = true
                ) {
                    showOptions = false
                    showEncodings = true
                }
                ToolMenuRow(stringResource(R.string.tools_view_hex), AppIcons.Hash, showDivider = true) {
                    showOptions = false
                    Hub.open(ToolsRoute.Hex(path))
                }
                ToolMenuRow(stringResource(R.string.tools_open_with), AppIcons.OpenExternal) {
                    showOptions = false
                    openElsewhere(context, path)
                }
            }
        }
    }

    if (showEncodings) {
        ToolSheet(title = stringResource(R.string.tools_editor_encoding), onDismiss = { showEncodings = false }) {
            KobbloGroup {
                TextCodec.choices.forEachIndexed { i, choice ->
                    ToolMenuRow(
                        title = choice.name(),
                        checked = choice == index.charset,
                        showDivider = i < TextCodec.choices.lastIndex
                    ) {
                        showEncodings = false
                        if (choice != index.charset) onReopen(choice)
                    }
                }
            }
        }
    }

    if (showGoTo) {
        GoToLineSheet(
            onDismiss = { showGoTo = false },
            onGo = { line ->
                showGoTo = false
                scope.launch { listState.scrollToItem((line - 1).coerceIn(0, index.lineCount - 1)) }
            }
        )
    }
}
