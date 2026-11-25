package com.kobblo.gallery.file.explorer.tools.files

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.music.MusicChip
import com.kobblo.gallery.file.explorer.tools.ToolLoading
import com.kobblo.gallery.file.explorer.tools.ToolMenuRow
import com.kobblo.gallery.file.explorer.tools.ToolSheet
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.tools.documents.CompoundFile
import com.kobblo.gallery.file.explorer.tools.documents.DocumentProblem
import com.kobblo.gallery.file.explorer.tools.documents.ExcelBinaryReader
import com.kobblo.gallery.file.explorer.tools.documents.NamedSheet
import com.kobblo.gallery.file.explorer.tools.documents.OpenAs
import com.kobblo.gallery.file.explorer.tools.documents.OpenDocumentReader
import com.kobblo.gallery.file.explorer.tools.documents.ProtectedDocumentException
import com.kobblo.gallery.file.explorer.tools.documents.UnreadableDocumentException
import com.kobblo.gallery.file.explorer.tools.documents.XlsxWorkbookReader
import com.kobblo.gallery.file.explorer.tools.editor.EditorToolbar
import com.kobblo.gallery.file.explorer.tools.editor.TextCodec
import com.kobblo.gallery.file.explorer.tools.editor.openElsewhere
import com.kobblo.gallery.file.explorer.ui.expressive.DetailRow
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.rowDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

/** The most a preview reads into memory: past this, the file's own apps are the better tool. */
private const val PREVIEW_LIMIT = 16L * 1024 * 1024

/**
 * The frame every preview shares, with its "..." offering the file's other views.
 *
 * @param offerText whether "Open as Text" means anything for this format. In a ZIP or a compound file
 * it would only be noise.
 */
@Composable
internal fun PreviewFrame(
    path: String,
    title: String? = null,
    subtitle: String? = null,
    offerText: Boolean = true,
    onBack: () -> Unit = { Hub.back() },
    extraOptions: @Composable (ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val file = remember(path) { File(path) }
    var showOptions by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(colors.canvas).statusBarsPadding().navigationBarsPadding()) {
        EditorToolbar(title = title ?: file.name, subtitle = subtitle, onBack = onBack) {
            KobbloIconButton(
                icon = AppIcons.MoreHoriz,
                description = stringResource(R.string.tools_options),
                onClick = { showOptions = true }
            )
        }
        content()
    }
    if (showOptions) {
        ToolSheet(title = stringResource(R.string.tools_options), onDismiss = { showOptions = false }) {
            extraOptions?.invoke(this) { showOptions = false }
            KobbloGroup {
                ToolMenuRow(stringResource(R.string.tools_open_with), AppIcons.OpenExternal, showDivider = true) {
                    showOptions = false
                    openElsewhere(context, path)
                }
                if (offerText) {
                    ToolMenuRow(stringResource(R.string.tools_open_as_text), AppIcons.FileText, showDivider = true) {
                        showOptions = false
                        Hub.open(ToolsRoute.TextFile(path, forceText = true))
                    }
                }
                ToolMenuRow(stringResource(R.string.tools_view_hex), AppIcons.Hash, showDivider = true) {
                    showOptions = false
                    Hub.open(ToolsRoute.Hex(path))
                }
                ToolMenuRow(stringResource(R.string.tools_inspect), AppIcons.Info) {
                    showOptions = false
                    Hub.open(ToolsRoute.Inspect(path))
                }
            }
        }
    }
}

// ── Fonts ─────────────────────────────────────────────────────────────────────

private class LoadedFont(val family: FontFamily?, val info: FontInfo?)

@Composable
internal fun FontPreviewScreen(path: String) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val font by produceState<LoadedFont?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            val unpacked = File(context.cacheDir, "font_preview.ttf")
            val source = if (WoffFont.unpackIfWoff(File(path), unpacked)) unpacked else File(path)
            val info = FontInfo.parse(source)
            val family = try {
                FontFamily(androidx.compose.ui.text.font.Typeface(Typeface.createFromFile(source)))
            } catch (_: Exception) {
                null
            }
            LoadedFont(family, info)
        }
    }
    val loaded = font
    PreviewFrame(path, subtitle = loaded?.info?.let { fontFormatLabel(it) }) {
        when {
            loaded == null -> ToolLoading()
            loaded.family == null || loaded.info == null -> KobbloMessage(
                icon = AppIcons.Alert,
                title = stringResource(R.string.tools_font_failed)
            )
            else -> {
                var sample by remember { mutableStateOf("") }
                val info = loaded.info
                val family = loaded.family
                val pangram = stringResource(R.string.tools_font_sample)
                val shown = sample.ifEmpty { pangram }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    KobbloGroup(Modifier.padding(top = 4.dp)) {
                        BasicTextField(
                            value = sample,
                            onValueChange = { sample = it },
                            textStyle = KobbloType.body.copy(color = colors.ink),
                            cursorBrush = SolidColor(colors.action),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            decorationBox = { inner ->
                                if (sample.isEmpty()) Text(stringResource(R.string.tools_font_type_here), style = KobbloType.body, color = colors.smoke)
                                inner()
                            }
                        )
                    }
                    KobbloGroupHeader(info.fullName ?: info.family ?: File(path).nameWithoutExtension)
                    KobbloGroup {
                        Column(Modifier.padding(com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.inset)) {
                            listOf(36, 24, 17, 13).forEach { size ->
                                Text(
                                    text = shown,
                                    style = TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = (size * 1.3f).sp),
                                    color = colors.ink,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                            }
                        }
                    }
                    KobbloGroupHeader(stringResource(R.string.tools_font_characters))
                    KobbloGroup {
                        Text(
                            text = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\nabcdefghijklmnopqrstuvwxyz\n0123456789 .,:;!?@#\$%&*()[]{}",
                            style = TextStyle(fontFamily = family, fontSize = 20.sp, lineHeight = 30.sp),
                            color = colors.ink,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    KobbloGroupHeader(stringResource(R.string.tools_details))
                    KobbloGroup {
                        info.family?.let { DetailRow(stringResource(R.string.tools_font_family), it) }
                        info.style?.let { DetailRow(stringResource(R.string.tools_font_style), it) }
                        DetailRow(stringResource(R.string.tools_font_format), fontFormatLabel(info))
                        if (info.fontsInCollection > 1) DetailRow(stringResource(R.string.tools_font_in_collection), info.fontsInCollection.toString())
                        info.glyphCount?.let { DetailRow(stringResource(R.string.tools_font_glyphs), it.toString()) }
                        info.version?.let { DetailRow(stringResource(R.string.tools_apps_version), it) }
                        info.copyright?.let { KobbloRow(title = stringResource(R.string.tools_font_copyright), subtitle = it) }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** A font's format as shown. OpenType and TrueType are names, but a collection is described in words. */
@Composable
private fun fontFormatLabel(info: FontInfo): String =
    if (info.fontsInCollection > 1) stringResource(R.string.tool_font_collection) else info.format

// ── Tables ────────────────────────────────────────────────────────────────────

private sealed interface TableLoad {
    data class Ready(val sheets: List<NamedSheet>) : TableLoad
    data object Locked : TableLoad
    data object Failed : TableLoad
}

/** A CSV is one table, and a workbook is several, chosen from the chips above whichever is showing. */
@Composable
internal fun TableScreen(path: String, kind: OpenAs = OpenAs.CSV) {
    val load by produceState<TableLoad?>(initialValue = null, path, kind) {
        value = runInterruptible(Dispatchers.IO) {
            try {
                val file = File(path)
                when (kind) {
                    OpenAs.SHEET_ZIP -> TableLoad.Ready(XlsxWorkbookReader.read(file).sheets)
                    OpenAs.SHEET_BINARY -> CompoundFile.open(file).use { TableLoad.Ready(ExcelBinaryReader.read(it).sheets) }
                    OpenAs.ODS -> TableLoad.Ready(OpenDocumentReader.readSpreadsheet(file).sheets)
                    else -> {
                        if (file.length() > PREVIEW_LIMIT) throw UnreadableDocumentException("too big")
                        val text = TextCodec.decode(file.readBytes()).text
                        val table = if (file.extension.equals("tsv", ignoreCase = true)) Csv.parse(text, '\t') else Csv.parse(text)
                        TableLoad.Ready(listOf(NamedSheet(file.name, table)))
                    }
                }
            } catch (_: ProtectedDocumentException) {
                TableLoad.Locked
            } catch (e: java.io.InterruptedIOException) {
                throw e
            } catch (_: Exception) {
                TableLoad.Failed
            } catch (_: OutOfMemoryError) {
                TableLoad.Failed
            }
        }
    }
    val current = load
    var selected by remember(path) { mutableStateOf(0) }
    val sheet = (current as? TableLoad.Ready)?.sheets?.getOrNull(selected)
    val subtitle = sheet?.table?.let { stringResource(R.string.tools_table_size, it.rows.size, it.columnCount) }
    PreviewFrame(path, subtitle = subtitle, offerText = kind == OpenAs.CSV) {
        when (current) {
            null -> ToolLoading()
            TableLoad.Failed -> DocumentProblem(path, locked = false)
            TableLoad.Locked -> DocumentProblem(path, locked = true)
            is TableLoad.Ready -> {
                if (current.sheets.size > 1) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = KobbloSpace.screen, vertical = 8.dp)
                    ) {
                        current.sheets.forEachIndexed { index, item ->
                            MusicChip(
                                // A sheet the file left unnamed goes by its place in the workbook.
                                label = item.name.ifEmpty { stringResource(R.string.tool_sheet_number, index + 1) },
                                selected = index == selected,
                                onClick = { selected = index },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }
                if (sheet == null || sheet.table.rows.isEmpty()) {
                    KobbloMessage(icon = AppIcons.Table, title = stringResource(R.string.tools_table_empty))
                } else {
                    key(selected) { TableView(sheet.table) }
                }
            }
        }
    }
}

/** @param note says what a cut-off table left out, where the row count alone wouldn't. */
@Composable
internal fun TableView(table: TextTable, note: String? = null) {
    val colors = KobbloDesign.colors
    val density = LocalDensity.current
    val widths: List<Dp> = remember(table) {
        val sample = table.rows.take(300)
        List(table.columnCount) { column ->
            val longest = sample.maxOfOrNull { it.getOrNull(column)?.length ?: 0 } ?: 0
            // About an average character's width at this size, kept readable at both ends.
            (longest.coerceIn(4, 32) * 8 + 24).dp
        }
    }
    val totalWidth = remember(widths) { widths.fold(0.dp) { sum, width -> sum + width } }
    val horizontal = rememberScrollState()
    Box(Modifier.fillMaxSize().horizontalScroll(horizontal).background(colors.snow)) {
        LazyColumn(
            modifier = Modifier.width(totalWidth).fillMaxHeight(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            itemsIndexed(table.rows, contentType = { index, _ -> if (index == 0) "header" else "row" }) { index, row ->
                Row(
                    Modifier
                        .heightIn(min = 36.dp)
                        .rowDivider(true, 0.dp, colors.hairline)
                        .background(if (index == 0) colors.mist else colors.snow)
                ) {
                    widths.forEachIndexed { column, width ->
                        Text(
                            text = row.getOrNull(column).orEmpty(),
                            style = KobbloType.footnote.copy(fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal),
                            color = colors.ink,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(width).padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            if (note != null) {
                item { KobbloGroupFooter(note) }
            } else if (table.truncated) {
                item { KobbloGroupFooter(stringResource(R.string.tools_table_truncated, table.rows.size)) }
            }
        }
    }
}

// ── Contacts and calendars ────────────────────────────────────────────────────

@Composable
internal fun ContactsScreen(path: String) {
    val context = LocalContext.current
    val contacts by produceState<List<ContactCard>?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                val file = File(path)
                if (file.length() > PREVIEW_LIMIT) emptyList() else VCard.parse(TextCodec.decode(file.readBytes()).text)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    val list = contacts
    PreviewFrame(path, subtitle = list?.let { pluralStringResource(R.plurals.tools_contacts_count, it.size, it.size) }) {
        when {
            list == null -> ToolLoading()
            list.isEmpty() -> KobbloMessage(icon = AppIcons.Person, title = stringResource(R.string.tools_contacts_empty))
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(list) { _, card ->
                    KobbloGroupHeader(card.name.ifEmpty { stringResource(R.string.tools_contacts_no_name) })
                    KobbloGroup {
                        val headline = listOfNotNull(card.title, card.organization).joinToString(", ")
                        // Rows that follow get a divider above them, so the group's last row has none below.
                        val rows = buildList<@Composable (Boolean) -> Unit> {
                            if (headline.isNotEmpty()) add { divider -> KobbloRow(title = headline, showDivider = divider) }
                            card.phones.forEach { phone ->
                                add { divider ->
                                    ContactAction(stringResource(R.string.tools_contacts_phone), phone, divider) {
                                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone))))
                                    }
                                }
                            }
                            card.emails.forEach { email ->
                                add { divider ->
                                    ContactAction(stringResource(R.string.tools_contacts_email), email, divider) {
                                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(email))))
                                    }
                                }
                            }
                            card.addresses.forEach { value ->
                                add { divider -> KobbloRow(title = stringResource(R.string.tools_contacts_address), subtitle = value, showDivider = divider) }
                            }
                            card.websites.forEach { value ->
                                add { divider -> KobbloRow(title = stringResource(R.string.tools_contacts_website), subtitle = value, showDivider = divider) }
                            }
                            card.birthday?.let { value ->
                                add { divider -> KobbloRow(title = stringResource(R.string.tools_contacts_birthday), subtitle = value, showDivider = divider) }
                            }
                            card.note?.let { value ->
                                add { divider -> KobbloRow(title = stringResource(R.string.tools_contacts_note), subtitle = value, showDivider = divider) }
                            }
                        }
                        rows.forEachIndexed { index, row -> row(index < rows.lastIndex) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactAction(label: String, value: String, showDivider: Boolean, onClick: () -> Unit) {
    KobbloRow(
        title = value,
        titleColor = KobbloDesign.colors.action,
        subtitle = label,
        showDivider = showDivider,
        onClick = {
            try {
                onClick()
            } catch (_: Exception) {
            }
        }
    )
}

@Composable
internal fun CalendarScreen(path: String) {
    val events by produceState<List<CalendarEvent>?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                val file = File(path)
                if (file.length() > PREVIEW_LIMIT) emptyList() else ICalendar.parse(TextCodec.decode(file.readBytes()).text)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    val list = events
    PreviewFrame(path, subtitle = list?.let { pluralStringResource(R.plurals.tools_calendar_count, it.size, it.size) }) {
        when {
            list == null -> ToolLoading()
            list.isEmpty() -> KobbloMessage(icon = AppIcons.Calendar, title = stringResource(R.string.tools_calendar_empty))
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(list) { _, event ->
                    KobbloGroupHeader(event.title.ifEmpty { stringResource(R.string.tools_calendar_no_title) })
                    KobbloGroup {
                        val time = remember(event) { ICalendar.describeTime(event) }
                        if (time.isNotEmpty()) KobbloRow(title = time, showDivider = event.location != null || event.description != null)
                        event.location?.let { KobbloRow(title = stringResource(R.string.tools_calendar_where), subtitle = it, showDivider = event.description != null) }
                        event.description?.let { KobbloRow(title = stringResource(R.string.tools_contacts_note), subtitle = it) }
                    }
                }
            }
        }
    }
}
