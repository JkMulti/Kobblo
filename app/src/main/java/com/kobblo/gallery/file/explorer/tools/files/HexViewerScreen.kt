package com.kobblo.gallery.file.explorer.tools.files

import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.ToolMenuRow
import com.kobblo.gallery.file.explorer.tools.ToolSheet
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.tools.editor.EditorToolbar
import com.kobblo.gallery.file.explorer.tools.editor.NoteLine
import com.kobblo.gallery.file.explorer.tools.editor.openElsewhere
import com.kobblo.gallery.file.explorer.ui.expressive.formatFileSize
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

private val HexStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp)
private const val PAGE_SIZE = 64 * 1024

/** Reads a file in blocks and remembers the recent ones, so scrolling never holds the whole file. */
private class HexPager(file: File) {
    private val source = RandomAccessFile(file, "r")
    private val pages = LruCache<Long, ByteArray>(32)
    private val lock = Mutex()
    val length: Long = file.length()

    suspend fun read(offset: Long, count: Int): ByteArray = lock.withLock {
        withContext(Dispatchers.IO) {
            val out = ByteArray(count.toLong().coerceAtMost((length - offset).coerceAtLeast(0)).toInt())
            var written = 0
            while (written < out.size) {
                val at = offset + written
                val pageIndex = at / PAGE_SIZE
                val page = pages.get(pageIndex) ?: loadPage(pageIndex)
                val inPage = (at - pageIndex * PAGE_SIZE).toInt()
                val take = minOf(out.size - written, page.size - inPage)
                if (take <= 0) break
                System.arraycopy(page, inPage, out, written, take)
                written += take
            }
            out
        }
    }

    private fun loadPage(index: Long): ByteArray {
        source.seek(index * PAGE_SIZE)
        val bytes = ByteArray(PAGE_SIZE)
        var filled = 0
        while (filled < PAGE_SIZE) {
            val read = source.read(bytes, filled, PAGE_SIZE - filled)
            if (read < 0) break
            filled += read
        }
        val page = if (filled == PAGE_SIZE) bytes else bytes.copyOf(filled)
        pages.put(index, page)
        return page
    }

    fun close() {
        try {
            source.close()
        } catch (_: Exception) {
        }
    }
}

/** Any file, as its bytes. A file of any size opens at once, since only the rows on screen are read. */
@Composable
internal fun HexViewerScreen(path: String) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val scope = rememberCoroutineScope()
    val file = remember(path) { File(path) }
    val pager = remember(path) {
        try {
            HexPager(file)
        } catch (_: Exception) {
            null
        }
    }
    DisposableEffect(pager) { onDispose { pager?.close() } }

    var showOptions by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var bytesPerRowForJump by remember { mutableStateOf(8) }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize().background(colors.canvas).statusBarsPadding().navigationBarsPadding()) {
        EditorToolbar(
            title = file.name,
            subtitle = stringResource(R.string.tools_hex_subtitle, formatFileSize(file.length())),
            onBack = { Hub.back() }
        ) {
            KobbloIconButton(
                icon = AppIcons.MoreHoriz,
                description = stringResource(R.string.tools_options),
                onClick = { showOptions = true }
            )
        }
        when {
            pager == null -> KobbloMessage(icon = AppIcons.Alert, title = stringResource(R.string.tools_editor_open_failed))
            pager.length == 0L -> KobbloMessage(icon = AppIcons.File, title = stringResource(R.string.tools_hex_empty))
            else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // Eight bytes fit a phone in portrait with this type, and sixteen fit anything wider.
                val bytesPerRow = if (maxWidth >= 600.dp) 16 else 8
                bytesPerRowForJump = bytesPerRow
                val digits = remember(pager.length) { HexFormat.offsetDigits(pager.length) }
                val rows = remember(pager.length, bytesPerRow) {
                    HexFormat.rowCount(pager.length, bytesPerRow).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().background(colors.snow)
                ) {
                    items(rows, contentType = { "row" }) { row ->
                        HexRow(pager, row.toLong() * bytesPerRow, bytesPerRow, digits)
                    }
                }
            }
        }
    }

    if (showOptions) {
        ToolSheet(title = stringResource(R.string.tools_options), onDismiss = { showOptions = false }) {
            KobbloGroup {
                ToolMenuRow(stringResource(R.string.tools_hex_go_to), AppIcons.List, showDivider = true) {
                    showOptions = false
                    showGoTo = true
                }
                ToolMenuRow(stringResource(R.string.tools_open_as_text), AppIcons.FileText, showDivider = true) {
                    showOptions = false
                    Hub.back()
                    Hub.open(ToolsRoute.TextFile(path, forceText = true))
                }
                ToolMenuRow(stringResource(R.string.tools_open_with), AppIcons.OpenExternal) {
                    showOptions = false
                    openElsewhere(context, path)
                }
            }
        }
    }

    if (showGoTo) {
        GoToOffsetSheet(
            onDismiss = { showGoTo = false },
            onGo = { offset ->
                showGoTo = false
                scope.launch {
                    val row = (offset / bytesPerRowForJump).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    listState.scrollToItem(row)
                }
            }
        )
    }
}

@Composable
private fun HexRow(pager: HexPager, offset: Long, bytesPerRow: Int, digits: Int) {
    val colors = KobbloDesign.colors
    val bytes by produceState<ByteArray?>(initialValue = null, offset, bytesPerRow) {
        value = pager.read(offset, bytesPerRow)
    }
    val smoke = colors.smoke
    val text: AnnotatedString = remember(bytes, smoke, offset) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = smoke)) { append(HexFormat.offset(offset, digits)) }
            append("  ")
            bytes?.let { row ->
                append(HexFormat.hexColumn(row, 0, row.size, bytesPerRow))
                append("  ")
                withStyle(SpanStyle(color = smoke)) { append(HexFormat.textColumn(row, 0, row.size)) }
            }
        }
    }
    Text(
        text = text,
        style = HexStyle,
        color = colors.ink,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    )
}

@Composable
private fun GoToOffsetSheet(onDismiss: () -> Unit, onGo: (Long) -> Unit) {
    val colors = KobbloDesign.colors
    var value by remember { mutableStateOf("") }
    val parsed = HexFormat.parseOffset(value)
    ToolSheet(title = stringResource(R.string.tools_hex_go_to), onDismiss = onDismiss) {
        BasicTextField(
            value = value,
            onValueChange = { value = it.take(20) },
            singleLine = true,
            textStyle = KobbloType.body.copy(color = colors.ink, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(colors.action),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KobbloSpace.screen)
                .clip(CapsuleShape)
                .background(colors.mist)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(stringResource(R.string.tools_hex_offset_hint), style = KobbloType.body, color = colors.smoke)
                inner()
            }
        )
        NoteLine(stringResource(R.string.tools_hex_offset_note))
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { parsed?.let(onGo) },
            enabled = parsed != null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen)
        ) {
            Text(stringResource(R.string.tools_editor_go), style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}
