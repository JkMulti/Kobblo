package com.kobblo.gallery.file.explorer.explorer

import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import android.os.Environment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSheetContent
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveTheme
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTextField
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Jumps to a typed path, with the folders under what's been typed so far offered to finish it. A path to
 * a file opens its folder with the file in view, so a path pasted from anywhere just works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoToPathSheet(from: String, onAction: (AppAction) -> Unit, onDismiss: () -> Unit) {
    val colors = KobbloDesign.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val primaryRoot = remember { Environment.getExternalStorageDirectory().absolutePath }
    val startFolder = from.takeIf { it.startsWith("/") }
    // Starts empty, with the current folder as the hint: a name typed here goes on from that folder, and a
    // whole path pasted in needs nothing cleared first.
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val typed = field.text
    var problem by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val suggestions by produceState(emptyList<String>(), typed) {
        delay(120)
        value = withContext(Dispatchers.IO) {
            folderSuggestions(context, typed.ifBlank { startFolder?.let { "$it/" } ?: "$primaryRoot/" }, startFolder, primaryRoot)
        }
    }

    fun go(input: String) {
        val path = PathTools.resolve(input, startFolder, primaryRoot)
        val target = path?.let(::File)
        if (target == null || !target.exists()) {
            problem = R.string.explorer_path_missing
            return
        }
        val folder = if (target.isDirectory) target else target.parentFile
        // The same limit a home screen shortcut has: shared storage only, so this can't reach the app's own
        // private files, and a locked folder stops at the folder above it rather than opening without asking.
        val allowed = folder?.let { com.kobblo.gallery.file.explorer.tools.files.ToolsFiles.resolveShortcutTarget(context, it.path) }
        when {
            allowed == null || folder.list() == null -> problem = R.string.explorer_path_denied
            target.isDirectory || allowed != folder.canonicalPath -> {
                onDismiss()
                onAction(AppAction.SetLocation(allowed, File(allowed).name))
            }
            else -> {
                onDismiss()
                ExplorerUi.showInFolder(target.path, onAction)
            }
        }
    }

    KobbloSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        ExpressiveSheetContent(
            title = stringResource(R.string.explorer_go_to_path),
            modifier = Modifier.imePadding().verticalScroll(rememberScrollState())
        ) {
            KobbloTextField(
                value = field,
                onValueChange = {
                    field = it
                    problem = null
                },
                placeholder = startFolder ?: primaryRoot,
                modifier = Modifier.padding(horizontal = KobbloSpace.screen).focusRequester(focus),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { go(typed) })
            )
            problem?.let {
                Text(
                    text = stringResource(it),
                    style = KobbloType.footnote,
                    color = colors.danger,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.content, vertical = 6.dp)
                )
            }
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                KobbloGroup {
                    suggestions.forEachIndexed { index, folder ->
                        KobbloRow(
                            title = File(folder).name,
                            leading = { Icon(AppIcons.Folder, contentDescription = null, tint = colors.action, modifier = Modifier.size(24.dp)) },
                            trailing = { KobbloChevron() },
                            showDivider = index < suggestions.lastIndex,
                            onClick = {
                                // Finished with a slash and the cursor at the end, ready for the next folder down.
                                val next = "$folder/"
                                field = TextFieldValue(next, TextRange(next.length))
                                problem = null
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { go(typed) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen)
            ) {
                Text(stringResource(R.string.explorer_go), style = KobbloType.headline.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            }
        }
    }
}

/** The folders that could finish what's typed: what's in the folder so far, starting with the last part. */
private fun folderSuggestions(context: android.content.Context, typed: String, currentFolder: String?, primaryRoot: String): List<String> {
    val trimmed = typed.trim()
    if (trimmed.isEmpty()) return emptyList()
    val endsAtFolder = trimmed.endsWith("/")
    val resolved = PathTools.resolve(trimmed, currentFolder, primaryRoot) ?: return emptyList()
    val folder = if (endsAtFolder) File(resolved) else File(resolved).parentFile ?: return emptyList()
    // Nothing is offered from places Go wouldn't open.
    if (com.kobblo.gallery.file.explorer.tools.files.ToolsFiles.resolveShortcutTarget(context, folder.path) != folder.canonicalPath) return emptyList()
    val prefix = if (endsAtFolder) "" else File(resolved).name
    val names = folder.list() ?: return emptyList()
    return names.asSequence()
        .filter { !it.startsWith(".") && it.startsWith(prefix, ignoreCase = true) }
        .sortedWith(NaturalOrder)
        .map { File(folder, it) }
        .filter { it.isDirectory }
        .take(6)
        .map { it.path }
        .toList()
}

/** More ways to choose than one at a time: everything, nothing, the opposite, or everything of one kind. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectOptionsSheet(
    files: List<FileItem>,
    selected: Set<Int>,
    onSelect: (Set<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    val kinds = remember(files) { SelectionOps.kindCounts(files) }
    val chosenBytes = remember(files, selected) { SelectionOps.totalBytes(files, selected) }
KobbloSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        ExpressiveSheetContent(
            title = stringResource(R.string.explorer_select_title),
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            if (chosenBytes > 0) {
                Text(
                    text = stringResource(R.string.explorer_selected_size, com.kobblo.gallery.file.explorer.ui.expressive.formatFileSize(chosenBytes)),
                    style = KobbloType.footnote,
                    color = KobbloDesign.colors.smoke,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            KobbloGroup {
                KobbloRow(
                    title = stringResource(R.string.select_all),
                    showDivider = true,
                    onClick = {
                        onSelect(SelectionOps.allSelectable(files))
                        onDismiss()
                    }
                )
                KobbloRow(
                    title = stringResource(R.string.explorer_select_none),
                    showDivider = true,
                    onClick = {
                        onSelect(emptySet())
                        onDismiss()
                    }
                )
                KobbloRow(
                    title = stringResource(R.string.explorer_select_invert),
                    onClick = {
                        onSelect(SelectionOps.invert(files, selected))
                        onDismiss()
                    }
                )
            }
            // One kind has nothing to tell apart: Select All already does that.
            if (kinds.size > 1) {
                KobbloGroupHeader(stringResource(R.string.explorer_select_by_kind))
                KobbloGroup {
                    val entries = kinds.entries.toList()
                    entries.forEachIndexed { index, (kind, count) ->
                        KobbloRow(
                            title = stringResource(selectKindLabel(kind)),
                            trailing = {
                                Text(count.toString(), style = KobbloType.callout, color = KobbloDesign.colors.smoke)
                            },
                            showDivider = index < entries.lastIndex,
                            onClick = {
                                onSelect(SelectionOps.addKind(files, selected, kind))
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun selectKindLabel(kind: SelectKind): Int = when (kind) {
    SelectKind.Folders -> R.string.explorer_kind_folders
    SelectKind.Photos -> R.string.explorer_kind_photos
    SelectKind.Videos -> R.string.explorer_kind_videos
    SelectKind.Audio -> R.string.explorer_kind_audio
    SelectKind.Documents -> R.string.explorer_kind_documents
    SelectKind.Archives -> R.string.explorer_kind_archives
    SelectKind.Apps -> R.string.explorer_kind_apps
    SelectKind.Other -> R.string.explorer_kind_other
}
