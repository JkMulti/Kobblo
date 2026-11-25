package com.kobblo.gallery.file.explorer.tools.files

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.MainActivity
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Deciding what the app does with a file that has no viewer of its own. */
object ToolsFiles {
    const val ACTION_OPEN_FOLDER = "com.kobblo.gallery.file.explorer.action.OPEN_FOLDER"
    const val EXTRA_FOLDER_PATH = "folder_path"

    /** Formats with a viewer of their own, which a file's name alone is enough to send it to. */
    private val mediaAndContainers = FileKinds.Images + FileKinds.Videos + FileKinds.Audio + FileKinds.Archives + setOf("pdf")

    private val archiveRequests = MutableSharedFlow<FileItem>(extraBufferCapacity = 1)

    /** A file whose bytes turned out to be an archive whatever its name says. `KobbloRoot` owns that dialog. */
    val archives: SharedFlow<FileItem> = archiveRequests

    fun openAsArchive(file: FileItem) {
        archiveRequests.tryEmit(file)
    }

    /** Every extension known to be text. Anything else is judged by its content when opened. */
    private val textExtensions = setOf(
        "txt", "md", "markdown", "log", "json", "jsonc", "xml", "html", "htm", "xhtml", "css", "scss", "less", "js", "jsx", "mjs",
        "ts", "tsx", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "swift", "py", "rb", "php", "pl", "lua",
        "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "sql", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "env",
        "gradle", "cmake", "mk", "dart", "r", "vue", "svelte", "tex", "srt", "vtt", "lrc", "nfo", "diff", "patch", "gitignore",
        "dockerfile", "csv", "tsv", "vcf", "ics"
    )

    fun isEditableText(name: String): Boolean = extensionOf(name) in textExtensions

    /**
     * Opens a file on a tools screen, if one suits it better than what the app did before. Anything that
     * isn't plainly a photo, a video, a song, an archive or a PDF goes to the route that looks at its
     * bytes, so a misnamed file gets its real viewer and nothing shows as garbage text.
     */
    fun tryOpen(file: FileItem): Boolean {
        val ext = extensionOf(file.name)
        if (file.type == "video" || file.type == "image" || file.type == "audio") return false
        if (ext in mediaAndContainers && file.type != "doc") return false
        if (ext == "pdf") return false
        if (ext == "apk") return false
        Hub.open(if (ext == "apks" || ext == "xapk") ToolsRoute.Inspect(file.path) else ToolsRoute.OpenUnknown(file.path))
        return true
    }

    /** The last resort for a file nothing else claimed, so a tap never just ends. */
    fun openUnknown(file: FileItem) {
        Hub.open(ToolsRoute.OpenUnknown(file.path))
    }

    /** A folder's shortcut on the home screen. Returns false where the launcher doesn't offer it. */
    fun pinFolderShortcut(context: Context, path: String): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val folder = File(path)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_FOLDER
            putExtra(EXTRA_FOLDER_PATH, path)
        }
        val info = ShortcutInfoCompat.Builder(context, "folder:$path")
            .setShortLabel(folder.name.ifEmpty { path })
            .setLongLabel(path)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /**
     * Where a shortcut's intent may lead. Any app can send one, so it only ever opens a folder on
     * shared storage, and a locked folder goes through its lock from the folder above it.
     */
    fun resolveShortcutTarget(context: Context, path: String): String? {
        val folder = try {
            File(path).canonicalFile
        } catch (_: Exception) {
            return null
        }
        com.kobblo.gallery.file.explorer.tools.storage.StorageVolumes.start(context)
        if (com.kobblo.gallery.file.explorer.tools.storage.StorageVolumes.forPath(folder.path) == null || !folder.isDirectory) return null
        val locked = com.kobblo.gallery.file.explorer.FileRepository(context).getLockedFiles()
        var target = folder
        var current: File? = folder
        while (current != null) {
            if (current.path in locked) target = current.parentFile ?: target
            current = current.parentFile
        }
        return target.path
    }

    private fun extensionOf(name: String): String {
        val lower = name.lowercase()
        // Dotfiles such as ".gitignore" are named after their format.
        if (lower.startsWith(".") && lower.indexOf('.', 1) < 0) return lower.drop(1)
        if ('.' !in lower) return if (lower == "dockerfile" || lower == "makefile") "dockerfile" else ""
        return lower.substringAfterLast('.')
    }
}

/**
 * The rows a file's menu gains from the tools. They sit in the menu's first group, so the common
 * actions stay where they were and everything rarer is one step further inside Inspect.
 */
@Composable
fun FileToolRows(file: FileItem, onDismiss: () -> Unit) {
    val colors = KobbloDesign.colors
    val local = file.path.startsWith("/")
    if (!local) return
    if (file.type != "folder" && ToolsFiles.isEditableText(file.name)) {
        KobbloRow(
            title = stringResource(R.string.tools_edit),
            trailing = { Icon(com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons.Pencil, contentDescription = null, tint = colors.ink, modifier = Modifier.size(22.dp)) },
            showDivider = true,
            onClick = {
                onDismiss()
                Hub.open(ToolsRoute.TextFile(file.path, edit = true))
            }
        )
    }
    KobbloRow(
        title = stringResource(R.string.tools_inspect),
        trailing = { Icon(com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons.Info, contentDescription = null, tint = colors.ink, modifier = Modifier.size(22.dp)) },
        showDivider = true,
        onClick = {
            onDismiss()
            Hub.open(ToolsRoute.Inspect(file.path))
        }
    )
}

private sealed interface ChecksumState {
    data object Idle : ChecksumState
    data class Running(val percent: Int) : ChecksumState
    data class Done(val digests: Map<String, String>) : ChecksumState
    data object Failed : ChecksumState
}

/**
 * MD5, SHA-1 and SHA-256 on request. A big file takes a while to read, so it only starts when asked,
 * and tapping again or leaving the screen stops it.
 */
@Composable
fun ChecksumRows(path: String) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val scope = rememberCoroutineScope()
    var state by remember(path) { mutableStateOf<ChecksumState>(ChecksumState.Idle) }
    var job by remember(path) { mutableStateOf<Job?>(null) }
    val copiedMessage = stringResource(R.string.tools_copied)

    fun start() {
        state = ChecksumState.Running(0)
        job = scope.launch {
            state = try {
                val file = File(path)
                val total = file.length().coerceAtLeast(1)
                val digests = withContext(Dispatchers.IO) {
                    val running = coroutineContext
                    var lastPercent = -1
                    file.inputStream().use { input ->
                        Checksums.compute(
                            input,
                            onProgress = { read ->
                                val percent = (read * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    state = ChecksumState.Running(percent)
                                }
                            },
                            checkCancelled = { running.ensureActive() }
                        )
                    }
                }
                ChecksumState.Done(digests)
            } catch (e: kotlinx.coroutines.CancellationException) {
                ChecksumState.Idle
            } catch (_: Exception) {
                ChecksumState.Failed
            }
        }
    }

    when (val current = state) {
        ChecksumState.Idle -> KobbloRow(
            title = stringResource(R.string.tools_checksums),
            trailing = { Text(stringResource(R.string.tools_checksums_calculate), style = KobbloType.callout, color = colors.action) },
            onClick = ::start
        )
        is ChecksumState.Running -> KobbloRow(
            title = stringResource(R.string.tools_checksums),
            subtitle = stringResource(R.string.tools_checksums_running, current.percent),
            trailing = { Text(stringResource(R.string.cancel), style = KobbloType.callout, color = colors.action) },
            onClick = {
                job?.cancel()
                state = ChecksumState.Idle
            }
        )
        ChecksumState.Failed -> KobbloRow(
            title = stringResource(R.string.tools_checksums),
            subtitle = stringResource(R.string.tools_checksums_failed),
            onClick = ::start
        )
        is ChecksumState.Done -> Column(Modifier.fillMaxWidth()) {
            val entries = current.digests.entries.toList()
            entries.forEachIndexed { index, (name, digest) ->
                KobbloRow(
                    title = name,
                    below = {
                        Text(
                            text = digest,
                            style = KobbloType.footnote.copy(fontFamily = FontFamily.Monospace),
                            color = colors.smoke,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    },
                    showDivider = index < entries.lastIndex,
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(name, digest))
                        android.widget.Toast.makeText(context, copiedMessage, android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}
