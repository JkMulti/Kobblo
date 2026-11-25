package com.kobblo.gallery.file.explorer.ui.root

import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet

internal const val PhoneRoot = "/storage/emulated/0"

/** The app's state with nothing in it. Its own default for the browse location asks Android where storage is. */
internal fun emptyState() = AppState(lastBrowseLocation = PhoneRoot)

internal fun AppState.holding(files: List<FileItem>, selected: List<FileItem> = emptyList()) = copy(
    files = files.toImmutableList(),
    selectedFiles = selected.map { it.id }.toPersistentSet(),
    isSelectionMode = selected.isNotEmpty()
)

internal fun testFile(
    name: String,
    id: Int = name.hashCode(),
    path: String = "$PhoneRoot/$name",
    type: String = "file",
    locked: Boolean = false,
    emptyFolder: Boolean = false
) = FileItem(
    id = id, path = path, name = name, type = type, kind = "", size = "", changed = "", owner = "",
    isLocked = locked, isEmptyFolder = emptyFolder
)
