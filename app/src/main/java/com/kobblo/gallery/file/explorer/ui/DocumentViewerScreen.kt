package com.kobblo.gallery.file.explorer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.fileops.OpenElsewhere
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlassSource
import com.kobblo.gallery.file.explorer.ui.expressive.DetailRow
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSheetContent
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.vault.VaultPaths
import com.kobblo.gallery.file.explorer.viewer.pdf.PdfReaderScreen
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Opens a document to read. A PDF has a reader of its own, which copes with any size of file. Every
 * other document is read as text and shown here as pages to leaf through.
 *
 * [onClose] puts the viewer away. [onLeave] is the way out the reader takes with Back, which for a file
 * opened from another app goes back to that app.
 */
@Composable
fun DocumentViewerScreen(
    fileItem: FileItem,
    onClose: () -> Unit,
    onLeave: () -> Unit = onClose,
    onAction: (AppAction) -> Unit,
    cornerRoundness: Float = 0.5f
) {
    if (isPdfName(fileItem.name)) {
        PdfReaderScreen(fileItem, onClose, onLeave, onAction)
    } else {
        PagedDocumentScreen(fileItem, onClose, onLeave, onAction)
    }
}

/**
 * Where reading the file has got to. A document already on screen stays while its file is read again,
 * so its toolbar doesn't blink away and back.
 */
private class DocumentReading {
    var underway by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set
    var document by mutableStateOf<DocumentRenderer?>(null)
        private set

    suspend fun read(context: Context, file: File, format: DocumentFormat) {
        underway = true
        failed = false
        try {
            document = DocumentRenderer.open(context, file, format)
        } catch (gone: CancellationException) {
            throw gone
        } catch (_: Exception) {
            failed = true
        }
        underway = false
    }
}

/** The sheets this screen can raise, one at a time. */
private enum class DocumentSheet { Details, More }

/** Room for the top bar, which floats over the pages. */
private val TopBarClearance = 62.dp

private const val ThumbnailWidth = 200
private const val ThumbnailsKept = 25
private const val PagesKept = 5

@Composable
private fun PagedDocumentScreen(
    fileItem: FileItem,
    onClose: () -> Unit,
    onLeave: () -> Unit,
    onAction: (AppAction) -> Unit
) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()

    val format = remember(fileItem.name) { DocumentFormat.of(fileItem.name) }
    val reading = remember { DocumentReading() }
    LaunchedEffect(fileItem.path) { reading.read(context, File(fileItem.path), format) }

    val document = reading.document
    val pageCount = document?.pageCount ?: 0
    // Drawn pages belong to the document they were drawn from, so another file starts with none.
    val thumbnails = remember(document) { document?.let { DrawnPages(it, ThumbnailWidth, ThumbnailsKept) } }
    val sheets = remember(document) { document?.let { DrawnPages(it, PagePaper.Width, PagesKept) } }

    val pager = rememberPagerState(pageCount = { (reading.document?.pageCount ?: 0).coerceAtLeast(1) })
    val rail = rememberLazyListState()
    var zoom by remember { mutableStateOf(PageZoom()) }
    var bookmarks by remember { mutableStateOf(emptySet<Int>()) }
    var favorite by remember(fileItem.path) { mutableStateOf(fileItem.isPinned) }
    var sheet by remember { mutableStateOf<DocumentSheet?>(null) }

    val backdrop = rememberLayerBackdrop {
        drawRect(colors.canvas)
        drawContent()
    }

    BackHandler(onBack = onLeave)

    // Turning the page brings its thumbnail into view, and the new page starts at its natural size.
    LaunchedEffect(pager.currentPage) {
        if (reading.document != null) {
            rail.animateScrollToItem(pager.currentPage)
            zoom = PageZoom()
        }
    }

    Box(modifier = Modifier.fillMaxSize().appGradientBackground()) {
        // Everything the glass bars show through. The pages run underneath the top bar.
        Column(modifier = Modifier.fillMaxSize().kobbloGlassSource(backdrop)) {
            Spacer(modifier = Modifier.statusBarsPadding().height(TopBarClearance))
            when {
                reading.underway -> ReadingNotice(format, Modifier.fillMaxSize().weight(1f))
                reading.failed -> UnreadableNotice(
                    onOpenElsewhere = { openFileInExternalApp(context, fileItem, "*/*") },
                    modifier = Modifier.fillMaxSize().weight(1f)
                )
                thumbnails != null && sheets != null -> Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    PageRail(
                        thumbnails = thumbnails,
                        currentPage = pager.currentPage,
                        bookmarks = bookmarks,
                        listState = rail,
                        onPick = { page -> scope.launch { pager.animateScrollToPage(page) } }
                    )
                    Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                        VerticalPager(
                            state = pager,
                            modifier = Modifier.fillMaxSize(),
                            // An enlarged page keeps drags to itself.
                            userScrollEnabled = zoom.scale == PageZoom.Min
                        ) { page ->
                            ZoomablePage(sheets = sheets, page = page, zoom = zoom, onZoom = { zoom = it })
                        }
                        PageControls(
                            bookmarked = pager.currentPage in bookmarks,
                            onZoomIn = { zoom = zoom.steppedIn() },
                            onZoomOut = { zoom = zoom.steppedOut() },
                            onBookmark = {
                                val page = pager.currentPage
                                bookmarks = if (page in bookmarks) bookmarks - page else bookmarks + page
                            },
                            // Clear of the toolbar that floats along the bottom.
                            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 80.dp)
                        )
                    }
                }
            }
        }

        ViewerTopBar(
            title = fileItem.name,
            subtitle = if (pageCount > 0) stringResource(R.string.page_of_total, pager.currentPage + 1, pageCount) else "",
            backdrop = backdrop,
            onBack = {
                haptics.tap()
                onLeave()
            },
            onMore = {
                haptics.tap()
                sheet = DocumentSheet.More
            },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        if (document != null) {
            DocumentToolbar(
                backdrop = backdrop,
                // Favorites has no password in front of it, so a Vault file doesn't go there.
                favorite = favorite.takeUnless { VaultPaths.isVaultPath(fileItem.path) },
                // A Word file has no editor here, and a copy made for viewing has nothing behind it to edit.
                editable = format == DocumentFormat.Text && !fileItem.path.startsWith(context.cacheDir.path),
                onShare = { shareDocument(context, fileItem) },
                onFavorite = {
                    favorite = !favorite
                    onAction(AppAction.TogglePin(fileItem.path))
                },
                onEdit = {
                    onClose()
                    Hub.open(ToolsRoute.TextFile(fileItem.path, edit = true))
                },
                onDetails = { sheet = DocumentSheet.Details },
                onOpenWith = { openDocumentWith(context, fileItem) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    when (sheet) {
        DocumentSheet.Details -> DocumentDetailsSheet(fileItem, pageCount, onDismiss = { sheet = null })
        DocumentSheet.More -> DocumentMoreSheet(
            title = fileItem.name,
            canCopyText = !document?.text.isNullOrBlank(),
            onCopyText = {
                sheet = null
                copyToClipboard(context, label = fileItem.name, text = document?.text.orEmpty())
                onAction(AppAction.ShowToast(context.getString(R.string.view_text_copied)))
            },
            onCopyPath = {
                sheet = null
                copyToClipboard(context, label = "File Path", text = fileItem.path)
                onAction(AppAction.ShowToast(context.getString(R.string.viewer_pdf_path_copied)))
            },
            onInspect = {
                sheet = null
                onClose()
                Hub.open(ToolsRoute.Inspect(fileItem.path))
            },
            onDismiss = { sheet = null }
        )
        null -> Unit
    }
}

// ── While there are no pages ──────────────────────────────────────────────────

@Composable
private fun ReadingNotice(format: DocumentFormat, modifier: Modifier) {
    val colors = KobbloDesign.colors
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KobbloLoader(color = colors.action)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                // Digging the words out of a Word file can take a while, so it says what it's doing.
                text = stringResource(if (format == DocumentFormat.Word) R.string.view_document_reading else R.string.tools_editor_loading),
                style = KobbloType.callout,
                color = colors.smoke
            )
        }
    }
}

/** The document can't be shown here, and another app might manage. */
@Composable
private fun UnreadableNotice(onOpenElsewhere: () -> Unit, modifier: Modifier) {
    val alarm = MaterialTheme.colorScheme.error
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(AppIcons.Alert, contentDescription = null, tint = alarm, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.view_document_unreadable),
                style = KobbloType.callout,
                color = alarm,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onOpenElsewhere) {
                Text(stringResource(R.string.view_open_in_external_app))
            }
        }
    }
}

// ── The bar along the bottom ──────────────────────────────────────────────────

/**
 * Share on one side, Open With on the other, and what can be done to the file here between them.
 * [favorite] is null for a file that can't be a favorite, which leaves the star out.
 */
@Composable
private fun DocumentToolbar(
    backdrop: Backdrop,
    favorite: Boolean?,
    editable: Boolean,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDetails: () -> Unit,
    onOpenWith: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KobbloDesign.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassCircle(
            icon = AppIcons.Share,
            description = stringResource(R.string.share),
            backdrop = backdrop,
            onClick = onShare,
            size = 52.dp
        )
        Row(
            modifier = Modifier
                .height(52.dp)
                .kobbloGlass(backdrop, CapsuleShape)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (favorite != null) {
                ToolbarAction(
                    icon = if (favorite) AppIcons.StarFill else AppIcons.Star,
                    description = stringResource(if (favorite) R.string.remove_from_favorites else R.string.add_to_favorites),
                    tint = if (favorite) colors.action else colors.ink,
                    onClick = onFavorite
                )
            }
            if (editable) {
                ToolbarAction(AppIcons.Pencil, stringResource(R.string.tools_edit), colors.ink, onEdit)
            }
            ToolbarAction(AppIcons.Info, stringResource(R.string.viewer_details), colors.ink, onDetails)
        }
        GlassCircle(
            icon = AppIcons.OpenExternal,
            description = stringResource(R.string.viewer_open_with),
            backdrop = backdrop,
            onClick = onOpenWith,
            size = 52.dp
        )
    }
}

// ── Sheets ────────────────────────────────────────────────────────────────────

@Composable
private fun DocumentDetailsSheet(fileItem: FileItem, pageCount: Int, onDismiss: () -> Unit) {
    val size = remember(fileItem.path) { FileSizes.label(File(fileItem.path).length()) }
    // A file that came without a date carries whatever its listing said instead.
    val modified = remember(fileItem.lastModified) {
        if (fileItem.lastModified > 0L) {
            DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(fileItem.lastModified))
        } else {
            fileItem.changed
        }
    }
    KobbloSheet(onDismissRequest = onDismiss) {
        ExpressiveSheetContent(title = fileItem.name) {
            KobbloGroup {
                DetailRow(stringResource(R.string.viewer_pdf_detail_modified), modified)
                DetailRow(stringResource(R.string.viewer_pdf_detail_kind), fileItem.name.substringAfterLast('.', "").uppercase())
                if (pageCount > 0) DetailRow(stringResource(R.string.viewer_pdf_detail_pages), "$pageCount")
                DetailRow(stringResource(R.string.viewer_pdf_detail_size), size)
                KobbloRow(title = stringResource(R.string.viewer_pdf_detail_location), subtitle = fileItem.path)
            }
        }
    }
}

@Composable
private fun DocumentMoreSheet(
    title: String,
    canCopyText: Boolean,
    onCopyText: () -> Unit,
    onCopyPath: () -> Unit,
    onInspect: () -> Unit,
    onDismiss: () -> Unit
) {
    val ink = KobbloDesign.colors.ink
    KobbloSheet(onDismissRequest = onDismiss) {
        ExpressiveSheetContent(title = title) {
            KobbloGroup {
                if (canCopyText) {
                    MoreRow(stringResource(R.string.view_copy_text), AppIcons.Copy, ink, showDivider = true, onClick = onCopyText)
                }
                MoreRow(stringResource(R.string.viewer_pdf_copy_path), AppIcons.Copy, ink, showDivider = true, onClick = onCopyPath)
                MoreRow(stringResource(R.string.tools_inspect), AppIcons.Info, ink, onClick = onInspect)
            }
        }
    }
}

// ── Out of the app ────────────────────────────────────────────────────────────

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** The file as other apps can reach it, through the app's own provider. */
private fun sharedUri(context: Context, fileItem: FileItem): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(fileItem.path))

private fun shareDocument(context: Context, fileItem: FileItem) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(documentMimeType(fileItem.name))
        .putExtra(Intent.EXTRA_STREAM, sharedUri(context, fileItem))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    start(context, Intent.createChooser(send, context.getString(R.string.view_share_document)))
}

private fun openDocumentWith(context: Context, fileItem: FileItem) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(sharedUri(context, fileItem), documentMimeType(fileItem.name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    start(context, OpenElsewhere.chooser(context, view, context.getString(R.string.view_open_with_chooser)))
}

/** Nothing to hand a file to isn't worth more than the tap doing nothing. */
private fun start(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}
