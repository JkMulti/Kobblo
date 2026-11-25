package com.kobblo.gallery.file.explorer.vault

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R

/**
 * The kinds the open Vault's list can be narrowed to, in the order their chips appear. [type] is the
 * `FileItem.type` a kind stands for; the two ends of the list stand for more than one.
 */
internal enum class VaultFilter(private val type: String?, @StringRes val label: Int) {
    All(null, R.string.filter_all),
    Images("image", R.string.cat_images),
    Videos("video", R.string.cat_videos),
    Audio("audio", R.string.cat_audio),
    Documents("doc", R.string.cat_docs),
    Apps("apk", R.string.cat_apps),
    Archives("archive", R.string.cat_archives),

    /** Whatever none of the named kinds claims. */
    Other(null, R.string.vault_category_other);

    fun accepts(file: FileItem): Boolean = when (this) {
        All -> true
        Other -> file.type !in NamedTypes
        else -> file.type == type
    }

    private companion object {
        val NamedTypes = setOf("image", "video", "audio", "doc", "apk", "archive")
    }
}

/**
 * The open Vault's list: its files, the kind it's narrowed to, and the file a sheet is open about.
 * It lives exactly as long as the list is on screen, so coming back from the Vault's settings reads
 * everything again.
 */
@Stable
internal class VaultBrowserState {
    var files by mutableStateOf(emptyList<FileItem>())
        private set

    /** True until the vault has been read once. Every sealed name is decrypted to list it, which takes a moment. */
    var isLoading by mutableStateOf(true)
        private set

    var filter by mutableStateOf(VaultFilter.All)
        private set

    /** The file whose actions are showing. */
    var menuFile by mutableStateOf<FileItem?>(null)
        private set

    /** The file being asked about before it's deleted for good. */
    var fileToDelete by mutableStateOf<FileItem?>(null)
        private set

    /** The files of the chosen kind. Empty with files in the vault means the last of that kind has just left. */
    val shownFiles: List<FileItem>
        get() = if (filter == VaultFilter.All) files else files.filter(filter::accepts)

    /**
     * The chips worth offering: All, then each kind the vault holds at least one of. A chip that could
     * only ever show an empty list isn't a filter.
     */
    val filters: List<VaultFilter>
        get() = VaultFilter.entries.filter { kind -> kind == VaultFilter.All || files.any(kind::accepts) }

    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** Shows a fresh reading of the vault. */
    fun show(listing: List<FileItem>) {
        files = listing
        isLoading = false
    }

    fun choose(kind: VaultFilter) {
        filter = kind
    }

    fun openMenu(file: FileItem) {
        menuFile = file
    }

    fun closeMenu() {
        menuFile = null
    }

    /** From the menu to the question it leads to: the menu closes, and [file] waits for an answer. */
    fun askToDelete(file: FileItem) {
        menuFile = null
        fileToDelete = file
    }

    fun doneDeleting() {
        fileToDelete = null
    }
}
