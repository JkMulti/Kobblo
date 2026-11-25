package com.kobblo.gallery.file.explorer.tools.files

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.ToolLoading
import com.kobblo.gallery.file.explorer.tools.ToolMenuRow
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.tools.apps.BundleInstallResult
import com.kobblo.gallery.file.explorer.tools.apps.BundleInstaller
import com.kobblo.gallery.file.explorer.tools.editor.EditorToolbar
import com.kobblo.gallery.file.explorer.tools.editor.openElsewhere
import com.kobblo.gallery.file.explorer.tools.picker.StorageDocumentsProvider
import com.kobblo.gallery.file.explorer.tools.storage.rememberFolderSize
import com.kobblo.gallery.file.explorer.ui.expressive.DetailRow
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveFileThumbnail
import com.kobblo.gallery.file.explorer.ui.expressive.formatFileSize
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.DateFormat
import java.util.Date

private fun readSample(path: String): ByteArray? = try {
    File(path).inputStream().use { input ->
        val buffer = ByteArray(FileSniffer.SAMPLE_SIZE)
        val read = input.read(buffer)
        if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    }
} catch (_: Exception) {
    null
}

/** Everything about one file or folder, and every way to look inside it. */
@Composable
internal fun InspectScreen(path: String, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val file = remember(path) { File(path) }
    val isFolder = remember(path) { file.isDirectory }
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }

    val item by produceState<FileItem?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { FileRepository(context).itemFor(file) }
    }
    val sniffed by produceState<SniffedKind?>(initialValue = null, path) {
        value = if (isFolder) null else withContext(Dispatchers.IO) { readSample(path)?.let(FileSniffer::sniff) }
    }
    val attributes by produceState<BasicFileAttributes?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            } catch (_: Exception) {
                null
            }
        }
    }
    val dates = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val extension = file.extension.lowercase()

    Column(Modifier.fillMaxSize().background(colors.canvas).statusBarsPadding()) {
        EditorToolbar(title = stringResource(R.string.tools_inspect), subtitle = null, onBack = { Hub.back() }) {}
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item?.let { ExpressiveFileThumbnail(file = it, isSelected = false, size = 88.dp) }
            Spacer(Modifier.height(12.dp))
            Text(
                text = file.name,
                style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = KobbloSpace.xl)
            )
            Text(
                text = when {
                    isFolder -> stringResource(R.string.tools_inspect_folder)
                    file.length() == 0L -> stringResource(R.string.tools_inspect_empty)
                    else -> (sniffed?.labelRes ?: FileSniffer.labelForName(file.name))?.let { stringResource(it) }
                        ?: stringResource(R.string.tools_inspect_unknown_kind)
                },
                style = KobbloType.footnote,
                color = colors.smoke,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = KobbloSpace.xl, vertical = 2.dp)
            )
            Spacer(Modifier.height(16.dp))

            if (isFolder) {
                KobbloGroup {
                    ToolMenuRow(stringResource(R.string.tools_storage_analyzer), AppIcons.PieChart, showDivider = true) {
                        Hub.open(ToolsRoute.Analyzer(path))
                    }
                    ToolMenuRow(stringResource(R.string.tools_search_in_files), AppIcons.Search, showDivider = true) {
                        Hub.open(ToolsRoute.ContentSearch(path))
                    }
                    val pinFailed = stringResource(R.string.tools_shortcut_unsupported)
                    ToolMenuRow(stringResource(R.string.tools_add_to_home), AppIcons.Home) {
                        if (!ToolsFiles.pinFolderShortcut(context, path)) onAction(AppAction.ShowToast(pinFailed))
                    }
                }
            } else {
                KobbloGroup {
                    if (extension == "apks" || extension == "xapk") {
                        val installed = stringResource(R.string.tools_bundle_installed)
                        ToolMenuRow(
                            title = stringResource(if (installing) R.string.tools_bundle_installing else R.string.tools_bundle_install),
                            icon = AppIcons.Android,
                            enabled = !installing,
                            tint = colors.action,
                            showDivider = true
                        ) {
                            installing = true
                            scope.launch {
                                when (val result = BundleInstaller.install(context, file)) {
                                    BundleInstallResult.Installed -> onAction(AppAction.ShowToast(installed))
                                    is BundleInstallResult.Failed -> onAction(AppAction.ShowToast(result.message))
                                }
                                installing = false
                            }
                        }
                    }
                    ToolMenuRow(stringResource(R.string.tools_open_with), AppIcons.OpenExternal, showDivider = true) {
                        openElsewhere(context, path)
                    }
                    ToolMenuRow(stringResource(R.string.tools_open_as_text), AppIcons.FileText, showDivider = true) {
                        Hub.open(ToolsRoute.TextFile(path, forceText = true))
                    }
                    ToolMenuRow(stringResource(R.string.tools_view_hex), AppIcons.Hash, showDivider = true) {
                        Hub.open(ToolsRoute.Hex(path))
                    }
                    ToolMenuRow(stringResource(R.string.tools_compare_with), AppIcons.Compare, showDivider = true) {
                        Hub.open(ToolsRoute.Compare(path))
                    }
                    ToolMenuRow(stringResource(R.string.share), AppIcons.Share) {
                        shareFile(context, file)
                    }
                }
            }

            KobbloGroupHeader(stringResource(R.string.tools_details))
            KobbloGroup {
                if (isFolder) {
                    val size by rememberFolderSize(path)
                    val measured = size
                    DetailRow(
                        stringResource(R.string.sort_size_label),
                        measured?.let { formatFileSize(it.bytes) } ?: stringResource(R.string.tools_calculating)
                    )
                    measured?.let {
                        DetailRow(stringResource(R.string.tools_inspect_contains), pluralStringResource(R.plurals.tools_files_and_folders, it.files, it.files, it.folders))
                    }
                } else {
                    DetailRow(stringResource(R.string.sort_size_label), detailedSize(file.length()))
                    DetailRow(stringResource(R.string.tools_inspect_type), remember(path) { StorageDocumentsProvider.mimeOf(file.name) })
                }
                DetailRow(stringResource(R.string.tools_inspect_modified), dates.format(Date(file.lastModified())))
                attributes?.let { DetailRow(stringResource(R.string.tools_inspect_created), dates.format(Date(it.creationTime().toMillis()))) }
                DetailRow(
                    stringResource(R.string.tools_inspect_access),
                    stringResource(
                        when {
                            file.canRead() && file.canWrite() -> R.string.tools_inspect_read_write
                            file.canRead() -> R.string.tools_inspect_read_only
                            else -> R.string.tools_inspect_no_access
                        }
                    )
                )
                KobbloRow(
                    title = stringResource(R.string.tools_inspect_location),
                    subtitle = path,
                    trailing = { Icon(AppIcons.Copy, contentDescription = stringResource(R.string.explorer_copy_path), tint = colors.smoke, modifier = Modifier.size(18.dp)) },
                    onClick = { com.kobblo.gallery.file.explorer.explorer.ExplorerUi.copyPath(context, path) }
                )
            }

            if (!isFolder) {
                KobbloGroupHeader(stringResource(R.string.tools_checksums))
                KobbloGroup { ChecksumRows(path) }
            }
        }
    }
}

/** A readable size with the exact count beside it, since that's what someone comparing files needs. */
@Composable
private fun detailedSize(bytes: Long): String {
    val short = formatFileSize(bytes)
    return if (bytes < 1024) short else pluralStringResource(R.plurals.tool_size_exact, bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), short, bytes)
}

internal fun shareFile(context: android.content.Context, file: File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = StorageDocumentsProvider.mimeOf(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name))
    } catch (_: Exception) {
    }
}
