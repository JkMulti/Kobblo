package com.kobblo.gallery.file.explorer.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.ui.design.KobbloBackButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoader
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * The plain viewer, for a file the richer viewers don't claim: its name in a bar, and under it the
 * file's pages, its list of contents, or its text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(fileItem: FileItem, onClose: () -> Unit, cornerRoundness: Float = 0.5f) {
    val ink = KobbloDesign.colors.ink
    BackHandler(onBack = onClose)

    Scaffold(
        modifier = Modifier.fillMaxSize().appGradientBackground(),
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = fileItem.name,
                        style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                // With the bar's own 4dp this puts the button on the screen's 16dp margin.
                navigationIcon = { KobbloBackButton(onClick = onClose, modifier = Modifier.padding(start = 12.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = ink,
                    navigationIconContentColor = ink
                )
            )
        }
    ) { bars ->
        Box(modifier = Modifier.fillMaxSize().padding(bars)) {
            val file = remember(fileItem.path) { File(fileItem.path) }
            when (PlainContent.of(fileItem.name)) {
                PlainContent.Pdf -> PdfPages(file, cornerRoundness)
                PlainContent.Zip -> ZipListing(file)
                PlainContent.Text -> Words(file, listedAs = fileItem.name)
            }
        }
    }
}

/** What the plain viewer makes of a file, going by its name. */
internal enum class PlainContent {
    Pdf, Zip, Text;

    companion object {
        fun of(fileName: String): PlainContent = when {
            isPdfName(fileName) -> Pdf
            fileName.endsWith(".zip", ignoreCase = true) -> Zip
            else -> Text
        }
    }
}

@Composable
private fun CenteredLoader() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { KobbloLoader() }
}

@Composable
private fun CenteredError(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.error)
    }
}

// ── A PDF, page under page ────────────────────────────────────────────────────

/**
 * An open PDF. The platform's renderer allows one page open at a time and must not be closed while one
 * is, so every use of it goes through the same lock, closing included: leaving the viewer while a page
 * is still being drawn waits for that page rather than pulling the file out from under it.
 */
private class OpenPdf(file: File) {
    private val renderer: PdfRenderer
    private var closed = false

    init {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = try {
            PdfRenderer(descriptor)
        } catch (failure: Throwable) {
            // The renderer only takes charge of the descriptor once it has opened the file.
            descriptor.close()
            throw failure
        }
    }

    val pageCount: Int = renderer.pageCount

    /** Page [index] on white, or null if it can't be drawn or the file has been closed. */
    fun draw(index: Int): Bitmap? {
        synchronized(this) {
            if (closed) return null
            return try {
                val page = renderer.openPage(index)
                try {
                    // A page is measured in points, 72 to the inch. Two and a half pixels to each keeps small print sharp.
                    val bitmap = Bitmap.createBitmap((page.width * 2.5).toInt(), (page.height * 2.5).toInt(), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } finally {
                    page.close()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun close() {
        synchronized(this) {
            if (!closed) {
                closed = true
                renderer.close()
            }
        }
    }
}

/**
 * Getting a PDF open, through a password if it has one.
 *
 * The platform's renderer can't open a protected file, so the password is used to write an unprotected
 * copy into the cache, and that copy is what gets shown. The copy and the renderer both go when the
 * viewer does.
 */
private class PdfOpening(private val original: File, private val cacheDir: File, private val wrongPassword: String) {
    var pdf by mutableStateOf<OpenPdf?>(null)
        private set

    /** The file wants a password, and nothing is shown but the request for it. */
    var locked by mutableStateOf(false)
        private set

    /** What last went wrong, in words for the reader. */
    var problem by mutableStateOf<String?>(null)
        private set
    var unlocking by mutableStateOf(false)
        private set

    private var unlockedCopy: File? = null
    private var left = false

    suspend fun open() = open(original)

    private suspend fun open(file: File) = withContext(Dispatchers.IO) {
        try {
            show(OpenPdf(file))
            locked = false
            problem = null
        } catch (_: SecurityException) {
            locked = true
        } catch (failure: Exception) {
            // Not every version of the platform has an error of its own for a protected file. Some only say so.
            val said = failure.message
            if (said?.contains("password") == true || said?.contains("cannot create document") == true) {
                locked = true
            } else {
                problem = said
            }
        }
    }

    private fun show(opened: OpenPdf) {
        synchronized(this) {
            if (left) {
                opened.close()
            } else {
                pdf?.close()
                pdf = opened
            }
        }
    }

    /** Tries [password]. If it isn't the one, the request stays up and says so. */
    suspend fun unlock(password: String) {
        unlocking = true
        problem = null
        val copy = withContext(Dispatchers.IO) {
            val target = File(cacheDir, "decrypted_${System.currentTimeMillis()}.pdf")
            try {
                PDDocument.load(original, password).use { document ->
                    document.setAllSecurityToBeRemoved(true)
                    document.save(target)
                }
                // Taken charge of here, on this thread: if the viewer has gone, nothing after this block runs.
                target.takeIf(::keep)
            } catch (_: Exception) {
                target.delete()
                problem = wrongPassword
                null
            } finally {
                unlocking = false
            }
        }
        if (copy != null) open(copy)
    }

    /** Takes charge of a fresh unprotected copy. If the viewer has gone meanwhile, deletes it and says no. */
    private fun keep(copy: File): Boolean {
        synchronized(this) {
            if (left) {
                copy.delete()
                return false
            }
            unlockedCopy?.delete()
            unlockedCopy = copy
            return true
        }
    }

    fun leave() {
        synchronized(this) {
            left = true
            pdf?.close()
            unlockedCopy?.delete()
        }
    }
}

@Composable
private fun PdfPages(file: File, cornerRoundness: Float) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val opening = remember(file) {
        PdfOpening(file, context.cacheDir, wrongPassword = context.getString(R.string.viewer_pdf_password_wrong))
    }
    LaunchedEffect(opening) { opening.open() }
    DisposableEffect(opening) {
        onDispose { opening.leave() }
    }

    val pdf = opening.pdf
    val problem = opening.problem
    when {
        opening.locked -> PdfPasswordRequest(
            problem = problem,
            busy = opening.unlocking,
            cornerRoundness = cornerRoundness,
            // Started on the spot, so the button is already off if a second tap lands.
            onUnlock = { password -> scope.launch(start = CoroutineStart.UNDISPATCHED) { opening.unlock(password) } }
        )
        problem != null -> CenteredError(stringResource(R.string.error_opening_pdf, problem))
        pdf != null && pdf.pageCount > 0 -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(KobbloSpace.xs),
            contentPadding = PaddingValues(KobbloSpace.md)
        ) {
            items(pdf.pageCount) { index -> PdfPage(pdf, index) }
        }
        else -> CenteredLoader()
    }
}

/** Asks for the file's password. There's nothing behind it to go back to, so it can't be waved away. */
@Composable
private fun PdfPasswordRequest(
    problem: String?,
    busy: Boolean,
    cornerRoundness: Float,
    onUnlock: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    val colors = KobbloDesign.colors
    val error = MaterialTheme.colorScheme.error
    GradientAlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.archive_password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.pdf_password_protected), color = colors.ink)
                if (problem != null) {
                    Text(problem, Modifier.padding(vertical = KobbloSpace.xxs), error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.padding(top = KobbloSpace.xs),
                    label = { Text(stringResource(R.string.password_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = getDynamicCornerShape(12f, cornerRoundness)
                )
                if (busy) KobbloLoader(modifier = Modifier.padding(top = KobbloSpace.xs).size(24.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onUnlock(password) }, enabled = !busy && password.isNotEmpty()) {
                Text(stringResource(R.string.unlock_action), color = colors.action)
            }
        }
    )
}

@Composable
private fun PdfPage(pdf: OpenPdf, index: Int) {
    val drawing by produceState<Bitmap?>(initialValue = null, pdf, index) {
        withContext(Dispatchers.IO) { pdf.draw(index) }?.let { value = it }
    }
    val page = drawing
    if (page == null) {
        Box(modifier = Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
            KobbloLoader(modifier = Modifier.size(32.dp))
        }
    } else {
        Image(
            bitmap = page.asImageBitmap(),
            contentDescription = stringResource(R.string.page_number, index + 1),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── A zip's contents ──────────────────────────────────────────────────────────

/** A file inside a zip. [size] is what it unpacks to, and below zero when the zip doesn't say. */
internal data class ZippedFile(val name: String, val size: Long)

/** The files in a zip, in name order. Folders get no row of their own: their names are in their files' paths. */
internal fun zippedFiles(zip: File): List<ZippedFile> = ZipFile(zip).use { opened ->
    opened.entries().asSequence()
        .filterNot { it.isDirectory }
        .map { ZippedFile(it.name, it.size) }
        .sortedBy { it.name }
        .toList()
}

private sealed interface ZipLook {
    data object Reading : ZipLook
    class Listed(val files: List<ZippedFile>) : ZipLook
    class Failed(val reason: String?) : ZipLook
}

@Composable
private fun ZipListing(file: File) {
    val look by produceState<ZipLook>(initialValue = ZipLook.Reading, file) {
        value = withContext(Dispatchers.IO) {
            try {
                ZipLook.Listed(zippedFiles(file))
            } catch (failure: Exception) {
                ZipLook.Failed(failure.message)
            }
        }
    }
    when (val now = look) {
        ZipLook.Reading -> CenteredLoader()
        is ZipLook.Listed -> ZipRows(now.files)
        is ZipLook.Failed -> {
            val reason = now.reason
            // A failure with nothing to say for itself leaves the list empty.
            if (reason != null) CenteredError(stringResource(R.string.error_opening_zip, reason)) else ZipRows(emptyList())
        }
    }
}

@Composable
private fun ZipRows(files: List<ZippedFile>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(files) { file ->
            ListItem(
                headlineContent = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        if (file.size > 0) {
                            stringResource(R.string.view_zip_entry_size, FileSizes.label(file.size))
                        } else {
                            stringResource(R.string.view_zip_entry_size_unknown)
                        }
                    )
                },
                leadingContent = { Icon(AppIcons.File, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            )
            HorizontalDivider()
        }
    }
}

// ── Text ──────────────────────────────────────────────────────────────────────

/** The file's words, to scroll through and select. [listedAs] is the name the file goes by in the app. */
@Composable
private fun Words(file: File, listedAs: String) {
    val context = LocalContext.current
    val words by produceState<String?>(initialValue = null, file) {
        value = null
        // The name on disk can say Word where the listed one doesn't.
        val word = DocumentFormat.of(listedAs) == DocumentFormat.Word || DocumentFormat.of(file.name) == DocumentFormat.Word
        value = DocumentWords(context).forScrolling(file, if (word) DocumentFormat.Word else DocumentFormat.Text)
    }
    val text = words
    if (text == null) {
        CenteredLoader()
    } else {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = KobbloDesign.colors.ink,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            )
        }
    }
}
