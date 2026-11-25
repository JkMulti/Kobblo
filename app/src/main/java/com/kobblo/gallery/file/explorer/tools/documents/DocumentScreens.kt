package com.kobblo.gallery.file.explorer.tools.documents

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.ToolLoading
import com.kobblo.gallery.file.explorer.tools.ToolMenuRow
import com.kobblo.gallery.file.explorer.tools.ToolSheet
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.tools.editor.TextEditorScreen
import com.kobblo.gallery.file.explorer.tools.editor.openElsewhere
import com.kobblo.gallery.file.explorer.tools.files.CalendarScreen
import com.kobblo.gallery.file.explorer.tools.files.ContactsScreen
import com.kobblo.gallery.file.explorer.tools.files.FontPreviewScreen
import com.kobblo.gallery.file.explorer.tools.files.InspectScreen
import com.kobblo.gallery.file.explorer.tools.files.PreviewFrame
import com.kobblo.gallery.file.explorer.tools.files.TableScreen
import com.kobblo.gallery.file.explorer.tools.files.ToolsFiles
import com.kobblo.gallery.file.explorer.ui.expressive.formatFileSize
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.rowDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A file nothing else claimed, or one whose name can't be taken at its word. Its bytes decide which
 * screen it gets, so this stays a single route however many formats there are.
 */
@Composable
internal fun OpenedFileScreen(path: String, onAction: (AppAction) -> Unit) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val kind by produceState<OpenAs?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { FileOpening.decide(File(path)) }
    }
    when (val decided = kind) {
        null -> Column(Modifier.fillMaxSize().background(colors.canvas).statusBarsPadding()) { ToolLoading() }
        OpenAs.TEXT -> TextEditorScreen(path, startEditing = false, forceText = false, onAction = onAction)
        OpenAs.WORD_ZIP, OpenAs.WORD_BINARY, OpenAs.ODT, OpenAs.RTF, OpenAs.SLIDES_ZIP, OpenAs.SLIDES_BINARY, OpenAs.ODP,
        OpenAs.TORRENT, OpenAs.EMAIL, OpenAs.BINARY_PLIST, OpenAs.MARKDOWN, OpenAs.HTML -> ReadableScreen(path, decided)
        OpenAs.SHEET_ZIP, OpenAs.SHEET_BINARY, OpenAs.ODS -> TableScreen(path, decided)
        OpenAs.CSV -> {
            // Past what a table can hold it's still text, and the editor can page through all of it.
            if (File(path).length() > PreviewTableLimit) {
                TextEditorScreen(path, startEditing = false, forceText = false, onAction = onAction)
            } else {
                TableScreen(path, decided)
            }
        }
        OpenAs.EPUB -> EpubScreen(path)
        OpenAs.SQLITE -> SqliteScreen(path)
        OpenAs.PLAYLIST -> PlaylistScreen(path, onAction)
        OpenAs.FONT -> FontPreviewScreen(path)
        OpenAs.CONTACTS -> ContactsScreen(path)
        OpenAs.CALENDAR -> CalendarScreen(path)
        OpenAs.LOCKED -> LockedScreen(path)
        OpenAs.IMAGE, OpenAs.VIDEO, OpenAs.AUDIO, OpenAs.ARCHIVE -> {
            // The file's real kind has its own full-screen home, so this route steps aside for it.
            LaunchedEffect(path) {
                val item = withContext(Dispatchers.IO) { FileRepository(context).itemFor(File(path)) }
                Hub.back()
                when (decided) {
                    OpenAs.IMAGE -> onAction(AppAction.ViewFile(item.copy(type = "image")))
                    OpenAs.VIDEO -> onAction(AppAction.ViewFile(item.copy(type = "video")))
                    OpenAs.AUDIO -> onAction(AppAction.PlayAudio(item.copy(type = "audio")))
                    else -> ToolsFiles.openAsArchive(item)
                }
            }
            Column(Modifier.fillMaxSize().background(colors.canvas).statusBarsPadding()) { ToolLoading() }
        }
        OpenAs.INSPECT -> InspectScreen(path, onAction)
    }
}

private const val PreviewTableLimit = 16L * 1024 * 1024

/** Words and structure from any document, drawn the same way whatever format they came from. */
@Composable
private fun ReadableScreen(path: String, kind: OpenAs) {
    val labels = docLabels()
    val load by produceState<DocLoad?>(initialValue = null, path) {
        // Interruptible, so leaving the screen stops a long read rather than letting it run on.
        value = runInterruptible(Dispatchers.IO) {
            DocumentLoader.load(File(path), kind, ::formatFileSize, labels)
        }
    }
    val isTextFormat = kind == OpenAs.MARKDOWN || kind == OpenAs.HTML
    val current = load
    val slides = (current as? DocLoad.Ready)?.document?.takeIf { it.paged }?.sections?.size
    PreviewFrame(
        path = path,
        subtitle = slides?.let { pluralStringResource(R.plurals.tools_doc_slides, it, it) },
        offerText = isTextFormat || kind == OpenAs.EMAIL,
        extraOptions = if (isTextFormat) { dismiss ->
            KobbloGroup(Modifier.padding(bottom = KobbloSpace.lg)) {
                ToolMenuRow(stringResource(R.string.tools_edit), AppIcons.Pencil) {
                    dismiss()
                    Hub.open(ToolsRoute.TextFile(path, edit = true))
                }
            }
        } else {
            null
        }
    ) {
        when (current) {
            null -> ToolLoading()
            DocLoad.Failed -> DocumentProblem(path, locked = false)
            DocLoad.Locked -> DocumentProblem(path, locked = true)
            is DocLoad.Ready -> {
                if (current.document.isEmpty) {
                    KobbloMessage(
                        icon = AppIcons.FileText,
                        title = stringResource(R.string.tools_doc_no_text),
                        body = stringResource(R.string.tools_doc_no_text_body),
                        action = { OpenWithButton(path) }
                    )
                } else {
                    DocumentBody(current.document)
                }
            }
        }
    }
}

@Composable
private fun LockedScreen(path: String) {
    PreviewFrame(path = path, offerText = false) { DocumentProblem(path, locked = true) }
}

/** The file can't be shown here, and what to do about it. Never just a blank page. */
@Composable
internal fun DocumentProblem(path: String, locked: Boolean) {
    KobbloMessage(
        icon = if (locked) AppIcons.Lock else AppIcons.Alert,
        title = stringResource(if (locked) R.string.tools_doc_locked else R.string.tools_doc_failed),
        body = stringResource(if (locked) R.string.tools_doc_locked_body else R.string.tools_doc_failed_body),
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OpenWithButton(path)
                TextButton(onClick = { Hub.open(ToolsRoute.Inspect(path)) }) {
                    Text(stringResource(R.string.tools_inspect), color = KobbloDesign.colors.action)
                }
            }
        }
    )
}

@Composable
private fun OpenWithButton(path: String) {
    val context = LocalContext.current
    Button(onClick = { openElsewhere(context, path) }) { Text(stringResource(R.string.tools_open_with)) }
}

/** The words readers add of their own, in the app's language. */
internal fun docLabelsFor(context: android.content.Context) = DocLabels(
    size = context.getString(R.string.sort_size_label),
    files = context.getString(R.string.tools_doc_files),
    comment = context.getString(R.string.tools_doc_comment),
    createdBy = context.getString(R.string.tools_doc_created_by),
    created = context.getString(R.string.tools_inspect_created),
    trackers = context.getString(R.string.tools_doc_trackers),
    from = context.getString(R.string.tools_doc_from),
    to = context.getString(R.string.tools_doc_to),
    copied = context.getString(R.string.tools_doc_cc),
    date = context.getString(R.string.tools_doc_date),
    attachments = context.getString(R.string.tools_doc_attachments),
    unnamed = context.getString(R.string.tools_doc_unnamed),
    value = context.getString(R.string.tools_doc_value),
    binaryData = context.getString(R.string.tool_plist_data)
)

@Composable
private fun docLabels() = docLabelsFor(LocalContext.current)

/**
 * The document itself. Slides sit on the canvas as separate cards, and anything else reads as one page.
 * Words stay selectable, so a preview is still somewhere to copy from.
 */
@Composable
internal fun DocumentBody(document: ReadableDocument, modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState()) {
    val colors = KobbloDesign.colors
    SelectionContainer(modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().background(if (document.paged) colors.canvas else colors.snow),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
        ) {
            if (document.paged) {
                items(document.sections) { section ->
                    KobbloGroupHeader(stringResource(R.string.tools_slide_number, section.slide ?: 0))
                    KobbloGroup {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            if (section.blocks.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.tools_slide_empty),
                                    style = KobbloType.callout,
                                    color = colors.smoke,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                            }
                            section.blocks.forEach { BlockView(it) }
                        }
                    }
                }
            } else {
                document.sections.forEach { section ->
                    items(section.blocks) { BlockView(it) }
                }
            }
            if (document.truncated) {
                item { KobbloGroupFooter(stringResource(R.string.tools_doc_truncated)) }
            }
        }
    }
}

private val Mono = FontFamily.Monospace

@Composable
private fun BlockView(block: DocBlock) {
    val colors = KobbloDesign.colors
    when (block) {
        is DocBlock.Heading -> Text(
            text = block.text,
            style = when (block.level) {
                1 -> KobbloType.title
                2 -> KobbloType.headline.copy(fontWeight = FontWeight.Bold, fontSize = 19.sp)
                3 -> KobbloType.headline.copy(fontWeight = FontWeight.SemiBold)
                else -> KobbloType.callout.copy(fontWeight = FontWeight.SemiBold)
            },
            color = colors.ink,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
        )
        is DocBlock.Paragraph -> Text(
            text = styled(block.text, block.spans),
            style = KobbloType.body.copy(lineHeight = 24.sp),
            color = colors.ink,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        is DocBlock.Bullet -> Row(Modifier.padding(start = (16 + block.depth * 16).dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
            if (block.marker.isNotEmpty()) {
                Text(block.marker, style = KobbloType.body.copy(lineHeight = 24.sp), color = colors.smoke)
                Spacer(Modifier.width(8.dp))
            }
            Text(styled(block.text, block.spans), style = KobbloType.body.copy(lineHeight = 24.sp), color = colors.ink)
        }
        is DocBlock.Quote -> Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(CapsuleShape).background(colors.silver))
            Spacer(Modifier.width(12.dp))
            Text(styled(block.text, block.spans), style = KobbloType.body.copy(lineHeight = 24.sp), color = colors.smoke)
        }
        is DocBlock.Code -> Box(
            Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .fillMaxWidth()
                .clip(KobbloShapes.sm)
                .background(colors.mist)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(block.text.replace("\t", "    "), style = KobbloType.footnote.copy(fontFamily = Mono, lineHeight = 19.sp), color = colors.ink)
        }
        is DocBlock.Table -> TableBlock(block)
        DocBlock.Rule -> Box(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().height(1.dp).background(colors.hairline)
        )
    }
}

@Composable
private fun TableBlock(block: DocBlock.Table) {
    val colors = KobbloDesign.colors
    val columns = remember(block) { block.rows.maxOfOrNull { it.size } ?: 0 }
    val widths = remember(block) {
        List(columns) { column ->
            val longest = block.rows.maxOfOrNull { it.getOrNull(column)?.length ?: 0 } ?: 0
            (longest.coerceIn(4, 30) * 8 + 24).dp
        }
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .clip(KobbloShapes.sm)
                .background(colors.canvas)
                .horizontalScroll(rememberScrollState())
        ) {
            block.rows.forEachIndexed { index, row ->
                val isHeader = block.hasHeader && index == 0 && block.rows.size > 1
                Row(Modifier.rowDivider(index < block.rows.lastIndex, 0.dp, colors.hairline).background(if (isHeader) colors.mist else Color.Transparent)) {
                    widths.forEachIndexed { column, width ->
                        Text(
                            text = row.getOrNull(column).orEmpty(),
                            style = KobbloType.footnote.copy(fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal),
                            color = colors.ink,
                            modifier = Modifier.width(width).padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
        if (block.more > 0) {
            Text(
                text = stringResource(R.string.tools_doc_more_rows),
                style = KobbloType.footnote,
                color = colors.smoke,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }
    }
}

/** Where a link inside the document leads, for a format that has an inside: a book's other chapters. */
private val LocalInternalLinks = staticCompositionLocalOf<((String) -> Boolean)?> { null }

/** A tab has no width in this text, and a document uses one as a gap, so a space keeps its words apart. */
private fun tabsToSpaces(text: String) = text.replace('\t', ' ')

@Composable
private fun styled(text: String, spans: List<InlineSpan>): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(tabsToSpaces(text))
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val internal = LocalInternalLinks.current
    return remember(text, spans, colors.action) {
        buildAnnotatedString {
            append(tabsToSpaces(text))
            spans.forEach { span ->
                if (span.start < 0 || span.end > text.length || span.start >= span.end) return@forEach
                when (span.style) {
                    InlineSpan.Style.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), span.start, span.end)
                    InlineSpan.Style.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), span.start, span.end)
                    InlineSpan.Style.CODE -> addStyle(SpanStyle(fontFamily = Mono, background = colors.mist), span.start, span.end)
                    InlineSpan.Style.LINK -> {
                        val url = span.url
                        val linkStyle = TextLinkStyles(SpanStyle(color = colors.action))
                        // A link that can't lead anywhere stays plain text rather than promising a tap.
                        if (url != null && HtmlReader.isOpenableLink(url)) {
                            addLink(LinkAnnotation.Url(url, linkStyle) { openLink(context, url) }, span.start, span.end)
                        } else if (url != null && internal != null) {
                            addLink(LinkAnnotation.Clickable(url, linkStyle) { internal(url) }, span.start, span.end)
                        }
                    }
                }
            }
        }
    }
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

// ── Books ─────────────────────────────────────────────────────────────────────

private sealed interface BookLoad {
    data class Ready(val book: EpubBook) : BookLoad
    data object Locked : BookLoad
    data object Failed : BookLoad
}

/** A book, one chapter at a time, picking up where it was left. */
@Composable
private fun EpubScreen(path: String) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val file = remember(path) { File(path) }
    val prefs = remember { context.getSharedPreferences("epub_positions", Context.MODE_PRIVATE) }
    val key = remember(path) { path.hashCode().toString() }
    val book by produceState<BookLoad?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                BookLoad.Ready(EpubReader.readBook(file))
            } catch (_: ProtectedDocumentException) {
                BookLoad.Locked
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                BookLoad.Failed
            } catch (_: OutOfMemoryError) {
                BookLoad.Failed
            }
        }
    }
    var showContents by remember { mutableStateOf(false) }
    val loaded = (book as? BookLoad.Ready)?.book
    var index by rememberSaveable(path) { mutableIntStateOf(prefs.getInt(key, 0)) }
    val chapterCount = loaded?.chapters?.size ?: 0
    val safeIndex = if (chapterCount == 0) 0 else index.coerceIn(0, chapterCount - 1)
    val chapter = loaded?.chapters?.getOrNull(safeIndex)

    val content by produceState<DocLoad?>(initialValue = null, chapter) {
        value = null
        val wanted = chapter ?: return@produceState
        value = runInterruptible(Dispatchers.IO) { DocumentLoader.guarded { EpubReader.readChapter(file, wanted) } }
    }
    DisposableEffect(path, safeIndex) {
        prefs.edit().putInt(key, safeIndex).apply()
        onDispose {}
    }

    PreviewFrame(
        path = path,
        title = loaded?.title,
        subtitle = if (chapterCount > 0) stringResource(R.string.tools_epub_position, safeIndex + 1, chapterCount) else null,
        offerText = false,
        extraOptions = if (loaded != null) { dismiss ->
            KobbloGroup(Modifier.padding(bottom = KobbloSpace.lg)) {
                ToolMenuRow(stringResource(R.string.tools_epub_contents), AppIcons.List) {
                    dismiss()
                    showContents = true
                }
            }
        } else {
            null
        }
    ) {
        when (val state = book) {
            null -> ToolLoading()
            BookLoad.Failed -> DocumentProblem(path, locked = false)
            BookLoad.Locked -> DocumentProblem(path, locked = true)
            is BookLoad.Ready -> {
                // A new state per chapter, so each one starts at its own top.
                val listState = remember(safeIndex) { LazyListState() }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val current = content) {
                        null -> ToolLoading()
                        DocLoad.Failed -> DocumentProblem(path, locked = false)
                        DocLoad.Locked -> DocumentProblem(path, locked = true)
                        is DocLoad.Ready -> {
                            if (current.document.isEmpty) {
                                KobbloMessage(icon = AppIcons.Book, title = stringResource(R.string.tools_epub_no_words))
                            } else {
                                val chapterLinks: (String) -> Boolean = { href ->
                                    val target = chapter?.let { EpubReader.resolveLink(it, href) }
                                    val position = state.book.chapters.indexOfFirst { it.part == target }
                                    if (position >= 0) index = position
                                    position >= 0
                                }
                                CompositionLocalProvider(LocalInternalLinks provides chapterLinks) {
                                    DocumentBody(current.document, state = listState)
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(colors.canvas).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { index = safeIndex - 1 }, enabled = safeIndex > 0) {
                        Text(stringResource(R.string.tools_epub_previous), color = if (safeIndex > 0) colors.action else colors.pewter)
                    }
                    Text(
                        text = chapter?.title ?: stringResource(R.string.tools_epub_chapter, safeIndex + 1),
                        style = KobbloType.footnote,
                        color = colors.smoke,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).clickable { showContents = true },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    val hasNext = safeIndex < state.book.chapters.lastIndex
                    TextButton(onClick = { index = safeIndex + 1 }, enabled = hasNext) {
                        Text(stringResource(R.string.tools_epub_next), color = if (hasNext) colors.action else colors.pewter)
                    }
                }
            }
        }
    }
    if (showContents && loaded != null) {
        ToolSheet(title = stringResource(R.string.tools_epub_contents), onDismiss = { showContents = false }) {
            KobbloGroup {
                val shown = loaded.chapters.take(MaxContentsRows)
                shown.forEachIndexed { position, item ->
                    KobbloRow(
                        title = item.title ?: stringResource(R.string.tools_epub_chapter, position + 1),
                        titleColor = if (position == safeIndex) colors.action else colors.ink,
                        showDivider = position < shown.lastIndex,
                        onClick = {
                            index = position
                            showContents = false
                        }
                    )
                }
            }
        }
    }
}

private const val MaxContentsRows = 500
