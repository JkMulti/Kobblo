package com.kobblo.gallery.file.explorer.ui.root

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.archive.ArchiveCreateConfig
import com.kobblo.gallery.file.explorer.archive.ArchiveProgress
import com.kobblo.gallery.file.explorer.archive.ArchiveViewModel
import com.kobblo.gallery.file.explorer.fileops.FileJobSpecs
import com.kobblo.gallery.file.explorer.fileops.FileJobs
import com.kobblo.gallery.file.explorer.fileops.JobKind
import com.kobblo.gallery.file.explorer.fileops.JobOutcome
import com.kobblo.gallery.file.explorer.fileops.JobSurface
import com.kobblo.gallery.file.explorer.tools.ToolFolderPicker
import com.kobblo.gallery.file.explorer.tools.archive.ArchiveFormats
import com.kobblo.gallery.file.explorer.tools.files.ToolsFiles
import com.kobblo.gallery.file.explorer.ui.ArchiveExtractDialog
import com.kobblo.gallery.file.explorer.ui.ArchiveFailedDialog
import com.kobblo.gallery.file.explorer.ui.ArchivePasswordDialog
import com.kobblo.gallery.file.explorer.ui.ArchiveViewerDialog
import com.kobblo.gallery.file.explorer.ui.expressive.ArchiveCompletedDialog
import com.kobblo.gallery.file.explorer.ui.expressive.ArchiveCreateBottomSheet
import com.kobblo.gallery.file.explorer.ui.expressive.ArchiveProgressDialog
import com.kobblo.gallery.file.explorer.ui.formatSize
import com.kobblo.gallery.file.explorer.viewer.ExternalMedia
import com.kobblo.gallery.file.explorer.viewer.findActivity
import java.io.File
import kotlinx.coroutines.delay

/** How long a finished extraction stays on the bar before the folder behind it is read again. */
private const val DoneShownMillis = 1500L

/** The archives on their way in or out, each at its own step. */
@Stable
internal class ArchiveDesk {
    /** An archive that was opened, with what to do about it still to be said. */
    var offered by mutableStateOf<FileItem?>(null)
        private set

    /** An archive being looked through. */
    var browsed by mutableStateOf<FileItem?>(null)
        private set

    /** An archive waiting for somewhere to be extracted to. */
    var homeless by mutableStateOf<FileItem?>(null)
        private set

    /** The password [homeless] opened with, if it took one to look inside. */
    var password: String? = null
        private set

    /** Files about to be put in an archive, with its name and kind still to be said. */
    var packing by mutableStateOf<List<FileItem>?>(null)
        private set

    /** An archive that has just been made, to be said. */
    var made by mutableStateOf<File?>(null)
        private set

    fun offer(archive: FileItem) {
        offered = archive
    }

    fun endOffer() {
        offered = null
    }

    fun browse(archive: FileItem) {
        browsed = archive
        offered = null
    }

    fun endBrowsing() {
        browsed = null
    }

    fun findHome(archive: FileItem) {
        homeless = archive
    }

    /** From looking inside an archive straight to choosing where it goes. */
    fun extractBrowsed(password: String?) {
        this.password = password
        homeless = browsed
        browsed = null
    }

    fun endFindingHome() {
        homeless = null
        password = null
    }

    fun pack(files: List<FileItem>) {
        packing = files
    }

    fun endPacking() {
        packing = null
    }

    fun announce(archive: File) {
        made = archive
    }

    fun endAnnouncing() {
        made = null
    }
}

/** Takes in archives that turn up from outside the lists, and archives a job has finished making. */
@Composable
internal fun ArchiveNews(desk: ArchiveDesk, onAction: (AppAction) -> Unit) {
    LaunchedEffect(desk) { ToolsFiles.archives.collect { desk.offer(it) } }
    LaunchedEffect(desk) {
        FileJobs.finished.collect { job ->
            if (job.kind != JobKind.COMPRESS) return@collect
            val archive = job.result.payload as? File
            if (archive != null) {
                // With nobody here, the notification has it. A dialog waiting on return would only say it twice.
                if (FileJobs.isAppVisible) desk.announce(archive)
            } else if (job.result.outcome == JobOutcome.PROBLEM) {
                job.result.message?.let { onAction(AppAction.ShowToast(it)) }
            }
        }
    }
}

/** The dialogs between opening an archive and having what's in it. */
@Composable
internal fun ExtractDialogs(root: RootMemory, engine: ArchiveViewModel, state: AppState, onAction: (AppAction) -> Unit) {
    val desk = root.archives
    desk.browsed?.let { archive ->
        ArchiveViewerDialog(archive, onDismiss = desk::endBrowsing, onExtractRequest = desk::extractBrowsed)
    }
    desk.offered?.let { ExtractOffer(it, root, engine, state, onAction) }
    desk.homeless?.let { ExtractDestination(it, desk, engine) }
    ExtractOutcome(engine, onAction)
}

private val FileItem.isFromOutside: Boolean get() = kind == ExternalMedia.External

private val FileItem.isLink: Boolean get() = path.startsWith("content://")

private val FileItem.source: Uri get() = if (isLink) Uri.parse(path) else Uri.fromFile(File(path))

private fun downloads(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

/** What to do with an archive: extract it here, extract it elsewhere, look inside or delete it. */
@Composable
private fun ExtractOffer(archive: FileItem, root: RootMemory, engine: ArchiveViewModel, state: AppState, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val desk = root.archives
    // Its name is the answer until its bytes have been looked at.
    var isRar by remember { mutableStateOf(looksLikeRar(archive.name)) }
    LaunchedEffect(archive.path) { isRar = engine.detectIsRar(archive.source) }

    val hereMeans = if (archive.isFromOutside) R.string.archive_extract_here_downloads else R.string.archive_extract_here_subtitle
    ArchiveExtractDialog(
        archiveName = archive.name,
        itemCount = archive.size,
        isRar = isRar,
        extractHereSubtitle = stringResource(hereMeans),
        onExtractHere = {
            // A link has no folder around it to extract into.
            if (archive.isLink) desk.findHome(archive) else extractBeside(archive, context, engine, state, onAction)
            desk.endOffer()
        },
        onExtractTo = { desk.findHome(archive) },
        onViewContents = { desk.browse(archive) },
        onDelete = if (archive.isFromOutside) null else ({
            root.deleting.ask(archive)
            desk.endOffer()
        }),
        onDismiss = {
            desk.endOffer()
            // Back belongs to the app it came from, as for the other viewers.
            if (archive.isFromOutside) context.findActivity()?.finish()
        }
    )
}

/** Extracts into a new folder next to the archive, named after it. */
private fun extractBeside(
    archive: FileItem,
    context: Context,
    engine: ArchiveViewModel,
    state: AppState,
    onAction: (AppAction) -> Unit
) {
    // Opened from another app it sits in the cache, which nobody can browse.
    val parent = if (archive.isFromOutside) downloads() else File(archive.path).parentFile ?: File(state.location)
    val name = ArchiveFormats.stem(archive.name).ifEmpty { context.getString(R.string.view_extracted_folder_name) }
    val folder = freeFolder(parent, name)
    folder.mkdirs()
    engine.extract(archive.source, Uri.fromFile(folder), archiveName = archive.name)
    // There's no folder on screen to see it arrive in.
    if (archive.isFromOutside) onAction(AppAction.ShowToast(context.getString(R.string.archive_extract_out_toast, folder.name)))
}

/** Asks where to extract to. The system's folder chooser refuses Download and the top of storage, so this is the app's own. */
@Composable
private fun ExtractDestination(archive: FileItem, desk: ArchiveDesk, engine: ArchiveViewModel) {
    val beside = if (archive.isFromOutside || archive.isLink) null else File(archive.path).parent
    ToolFolderPicker(
        title = stringResource(R.string.archive_extract_to),
        confirmLabel = stringResource(R.string.extract_action),
        initialPath = beside ?: downloads().path,
        onDismiss = desk::endFindingHome,
        onPick = { folder ->
            engine.extract(archive.source, Uri.fromFile(File(folder)), desk.password, archiveName = archive.name)
            desk.endFindingHome()
            desk.endOffer()
        }
    )
}

/** How the last extraction came out, where that needs an answer. Progress is the bar's to show. */
@Composable
private fun ExtractOutcome(engine: ArchiveViewModel, onAction: (AppAction) -> Unit) {
    val progress by engine.archiveProgress.collectAsState()
    when (val outcome = progress) {
        is ArchiveProgress.Complete -> LaunchedEffect(outcome) {
            delay(DoneShownMillis)
            engine.resetState()
            onAction(AppAction.Reload)
        }
        is ArchiveProgress.NeedsPassword -> ArchivePasswordDialog(
            archiveName = stringResource(R.string.archive_encrypted_name),
            attemptFailed = outcome.attemptFailed,
            onSubmit = engine::submitPassword,
            onDismiss = engine::cancelExtraction
        )
        is ArchiveProgress.Failed -> ArchiveFailedDialog(outcome.reason, onDismiss = engine::resetState)
        else -> Unit
    }
}

/** The sheets and dialogs between choosing files to compress and having the archive. */
@Composable
internal fun CompressDialogs(desk: ArchiveDesk, state: AppState, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    desk.packing?.let { files ->
        ArchiveCreateBottomSheet(
            selectedFiles = files,
            defaultName = remember(files) { archiveNameFor(files) },
            onDismissRequest = desk::endPacking,
            onConfirm = { config ->
                desk.endPacking()
                compress(files, config, context, state, onAction)
            }
        )
    }
    CompressProgress()
    desk.made?.let { MadeArchive(it, desk, onAction) }
}

/** Sets [files] going into an archive beside the first of them. */
private fun compress(files: List<FileItem>, config: ArchiveCreateConfig, context: Context, state: AppState, onAction: (AppAction) -> Unit) {
    val folder = File(files.first().path).parentFile ?: File(state.location)
    val job = FileJobSpecs.compress(
        context.applicationContext,
        files.map { File(it.path) }.filter { it.exists() },
        File(folder, config.name),
        config,
        watched = !FileJobs.state.value.busy
    )
    FileJobs.enqueueAndSay(job) { message -> onAction(AppAction.ShowToast(message)) }
    // The job has its own list now, and may finish with nobody here to close a dialog.
    onAction(AppAction.ClearSelection)
}

/** On its own so that progress many times a second only redraws this, and not the screen around it. */
@Composable
private fun CompressProgress() {
    val jobs by FileJobs.state.collectAsState()
    val job = jobs.running ?: return
    if (job.surface != JobSurface.COMPRESS_DIALOG) return
    val done = job.fraction ?: 0f
    ArchiveProgressDialog(job.target.orEmpty(), job.item.orEmpty(), percent = (done * 100).toInt(), onCancel = { FileJobs.cancel(job.id) })
}

/** Says an archive is made, and offers to open it or share it. */
@Composable
private fun MadeArchive(archive: File, desk: ArchiveDesk, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    // Whatever is done with it, the files that went into it needn't stay selected.
    fun ending(choice: () -> Unit = {}): () -> Unit = {
        desk.endAnnouncing()
        onAction(AppAction.ClearSelection)
        choice()
    }
    ArchiveCompletedDialog(
        archiveFile = archive,
        onOpenArchive = ending { desk.offer(madeArchive(archive, formatSize(archive.length()))) },
        onShare = {
            try {
                context.share(context.lend(archive), context.getString(R.string.view_share_archive))
            } catch (e: Exception) {
                Log.w("Archives", "Couldn't share an archive", e)
            }
            ending().invoke()
        },
        onClose = ending()
    )
}
