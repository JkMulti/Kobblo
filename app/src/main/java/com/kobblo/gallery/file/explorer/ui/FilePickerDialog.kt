package com.kobblo.gallery.file.explorer.ui

import android.os.Environment
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.KobbloActionPair
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoading
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheetTitle
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveChip
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveFileThumbnail
import com.kobblo.gallery.file.explorer.ui.expressive.SelectionCircle
import com.kobblo.gallery.file.explorer.ui.expressive.formatFileDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * What a folder holds, as the picker offers it: the folders to go into, then the files to tick, each
 * in order of name whatever its case.
 */
internal class PickerListing private constructor(val folders: List<FileItem>, val files: List<FileItem>) {
    val isEmpty: Boolean get() = folders.isEmpty() && files.isEmpty()

    companion object {
        val Empty = PickerListing(emptyList(), emptyList())

        /**
         * Sorts out what a folder was read to hold. Hidden items are left out as they are everywhere,
         * and so are locked ones: picked into a new Vault, a locked file would have got past its lock.
         */
        fun of(items: List<FileItem>): PickerListing {
            val (folders, files) = items
                .filterNot { it.isHidden || it.isLocked }
                .sortedBy { it.name.lowercase() }
                .partition { it.type == "folder" || it.kind == "folder" }
            return PickerListing(folders, files)
        }
    }
}

/** [path] with the long root of the phone's storage, [storageRoot], swapped for [storageName]. */
internal fun pickerPathLabel(path: String, storageRoot: String, storageName: String): String =
    path.replace(storageRoot, storageName)

/**
 * A picker's working memory: the folder it's in, what's there, and what has been ticked. Ticks are
 * kept by path, so they hold while other folders are looked through.
 */
@Stable
private class PickerBrowser(
    private val repository: FileRepository,
    private val scope: CoroutineScope,
    startIn: String
) {
    /** The folder on show. It only becomes another once that one has been read. */
    var folder by mutableStateOf(startIn)
        private set

    var listing by mutableStateOf(PickerListing.Empty)
        private set

    /** True from asking for a folder until its answer, so the first thing shown is a spinner, not "empty". */
    var reading by mutableStateOf(true)
        private set

    val ticked = mutableStateMapOf<String, FileItem>()

    /** Goes to [path]. A folder that can't be read shows as empty, under the name of the one before. */
    fun open(path: String) {
        reading = true
        scope.launch {
            try {
                listing = PickerListing.of(repository.getFiles(path))
                folder = path
            } catch (e: Exception) {
                listing = PickerListing.Empty
            } finally {
                reading = false
            }
        }
    }

    fun toggle(file: FileItem) {
        if (ticked.remove(file.path) == null) ticked[file.path] = file
    }

    fun tickAllHere() = listing.files.forEach { ticked[it.path] = it }
}

/** A browser that lasts as long as its sheet, and starts reading [startIn] as soon as the sheet is up. */
@Composable
private fun rememberPickerBrowser(startIn: String): PickerBrowser {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browser = remember { PickerBrowser(FileRepository(context), scope, startIn) }
    LaunchedEffect(Unit) { browser.open(startIn) }
    return browser
}

/**
 * A sheet for choosing files from the phone's storage, any number and from any folders. It opens in
 * [initialPath] and gives what was ticked to [onFilesSelected]; closing the sheet afterwards is the
 * caller's to do. With [onOpenSystemPicker] it also offers a way out to Android's own picker, for
 * files this one can't reach.
 */
@Composable
fun FilePickerDialog(
    title: String = stringResource(R.string.tools_picker_title_many),
    confirmText: String = stringResource(R.string.select_action),
    initialPath: String = Environment.getExternalStorageDirectory().path,
    onDismiss: () -> Unit,
    onFilesSelected: (List<FileItem>) -> Unit,
    onOpenSystemPicker: (() -> Unit)? = null,
    cornerRoundness: Float = 0.5f
) {
    val browser = rememberPickerBrowser(startIn = initialPath)
    val haptics = LocalHaptics.current
    val tickedCount = browser.ticked.size
    val goTo = { path: String ->
        haptics.tap()
        browser.open(path)
    }

    KobbloSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(bottom = KobbloSpace.sm)
        ) {
            KobbloSheetTitle(
                text = title,
                subtitle = if (tickedCount == 0) {
                    stringResource(R.string.view_picker_tap_to_select)
                } else {
                    pluralStringResource(R.plurals.view_selected_count, tickedCount, tickedCount)
                }
            )
            ShortcutChips(current = browser.folder, onOpen = goTo)
            WhereAndAll(browser = browser, onOpen = goTo)

            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = KobbloSpace.screen)) {
                when {
                    browser.reading -> KobbloLoading()
                    browser.listing.isEmpty -> KobbloMessage(icon = AppIcons.FolderOpen, title = stringResource(R.string.this_folder_empty))
                    else -> FolderContents(browser = browser, onOpen = goTo)
                }
            }

            if (onOpenSystemPicker != null) {
                TextButton(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    onClick = {
                        haptics.tap()
                        // This sheet goes first, so Android's picker isn't left with it underneath.
                        onDismiss()
                        onOpenSystemPicker()
                    }
                ) {
                    Text(stringResource(R.string.polish_choose_from_other_app), style = KobbloType.footnote, color = KobbloDesign.colors.action)
                }
            } else {
                Spacer(Modifier.height(KobbloSpace.xs))
            }

            KobbloActionPair(
                confirmLabel = if (tickedCount == 0) confirmText else stringResource(R.string.view_label_with_count, confirmText, tickedCount),
                enabled = tickedCount > 0,
                onConfirm = {
                    haptics.tap()
                    onFilesSelected(browser.ticked.values.toList())
                },
                onCancel = {
                    haptics.tap()
                    onDismiss()
                }
            )
        }
    }
}

/**
 * The standard folders the picker has a shortcut to, by the name Android keeps each under, with what
 * the shortcut is called. The camera's folder is called DCIM in every language, so it has no word of
 * its own and goes by its name.
 */
private val ShortcutFolders: List<Pair<String, Int?>> = listOf(
    Environment.DIRECTORY_DOWNLOADS to R.string.view_picker_downloads,
    Environment.DIRECTORY_DOCUMENTS to R.string.view_picker_documents,
    Environment.DIRECTORY_PICTURES to R.string.view_picker_pictures,
    Environment.DIRECTORY_DCIM to null,
    Environment.DIRECTORY_MUSIC to R.string.view_picker_music
)

/**
 * The usual places files are kept, one tap away: the whole of the phone's storage, then the standard
 * folders. A place this phone doesn't have isn't offered.
 */
@Composable
private fun ShortcutChips(current: String, onOpen: (String) -> Unit) {
    val names = listOf(stringResource(R.string.view_picker_storage)) +
        ShortcutFolders.map { (folder, label) -> if (label != null) stringResource(label) else folder }
    val places = remember(names) {
        val folders = listOf(Environment.getExternalStorageDirectory()) +
            ShortcutFolders.map { (folder, _) -> Environment.getExternalStoragePublicDirectory(folder) }
        names.zip(folders).filter { (_, folder) -> folder.exists() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = KobbloSpace.screen),
        horizontalArrangement = Arrangement.spacedBy(KobbloSpace.xs)
    ) {
        for ((name, place) in places) {
            ExpressiveChip(
                label = name,
                selected = current.equals(place.path, ignoreCase = true),
                onClick = { if (current != place.path) onOpen(place.path) }
            )
        }
    }
}

/**
 * The line above the list: the way up, where this is, and the two buttons that act on every file in
 * sight. At the top of the phone's storage there is nowhere further up to go.
 */
@Composable
private fun WhereAndAll(browser: PickerBrowser, onOpen: (String) -> Unit) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val storageRoot = Environment.getExternalStorageDirectory().path
    val atTop = browser.folder == storageRoot

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = KobbloSpace.xs, top = 6.dp, end = KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!atTop) {
            KobbloIconButton(
                icon = AppIcons.Back,
                description = stringResource(R.string.back),
                onClick = { File(browser.folder).parent?.let(onOpen) },
                size = 36.dp,
                background = Color.Transparent
            )
        }
        Text(
            text = pickerPathLabel(browser.folder, storageRoot, stringResource(R.string.internal_storage)),
            style = KobbloType.footnote,
            color = colors.smoke,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // With no button before it, the text keeps its own distance from the sheet's edge.
            modifier = Modifier.weight(1f).padding(start = if (atTop) KobbloSpace.sm else 0.dp)
        )
        if (browser.listing.files.isNotEmpty()) {
            TextButton(
                onClick = {
                    haptics.tap()
                    browser.tickAllHere()
                }
            ) {
                Text(stringResource(R.string.select_all), style = KobbloType.footnote, color = colors.action)
            }
        }
        if (browser.ticked.isNotEmpty()) {
            TextButton(
                onClick = {
                    haptics.tap()
                    browser.ticked.clear()
                }
            ) {
                Text(stringResource(R.string.select_none), style = KobbloType.footnote, color = colors.smoke)
            }
        }
    }
}

/**
 * The folder's contents as one group of rows: folders, which open, then files, which tick. The rows
 * are separate items of a lazy list, so each is given the piece of the group's outline that falls to
 * its place in it.
 */
@Composable
private fun FolderContents(browser: PickerBrowser, onOpen: (String) -> Unit) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val folders = browser.listing.folders
    val files = browser.listing.files
    val rows = folders.size + files.size

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = KobbloSpace.xxs)) {
        itemsIndexed(folders, key = { _, folder -> "folder_${folder.path}" }) { index, folder ->
            Box(modifier = Modifier.clip(KobbloShapes.groupItem(index, rows)).background(colors.snow)) {
                KobbloRow(
                    title = folder.name,
                    leading = { Icon(AppIcons.Folder, contentDescription = null, tint = colors.action, modifier = Modifier.size(26.dp)) },
                    trailing = { KobbloChevron() },
                    showDivider = index < rows - 1,
                    onClick = { onOpen(folder.path) }
                )
            }
        }
        itemsIndexed(files, key = { _, file -> "file_${file.path}" }) { index, file ->
            val place = folders.size + index
            Box(modifier = Modifier.clip(KobbloShapes.groupItem(place, rows)).background(colors.snow)) {
                KobbloRow(
                    title = file.name,
                    subtitle = "${file.size} · ${formatFileDate(file)}",
                    leading = { ExpressiveFileThumbnail(file = file, isSelected = false, size = 30.dp) },
                    trailing = {
                        SelectionCircle(selected = file.path in browser.ticked, modifier = Modifier.size(24.dp), onSurface = true)
                    },
                    showDivider = place < rows - 1,
                    onClick = {
                        haptics.tap()
                        browser.toggle(file)
                    }
                )
            }
        }
    }
}
