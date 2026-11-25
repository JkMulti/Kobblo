package com.kobblo.gallery.file.explorer

/** What a file's details sheet shows, as text. What doesn't apply to a kind of file is null. */
data class FileDetails(
    val name: String,
    val path: String,
    val isFolder: Boolean,
    /** Nothing for a folder, whose real size takes a walk to find. The sheet gets that from FolderSizes. */
    val size: String,
    /** The day and the time. */
    val changed: String,
    val owner: String,
    /** Everything directly in a folder, hidden or not. */
    val itemCount: Int? = null,
    /** A picture's or a video's, as "4032 x 3024". */
    val resolution: String? = null,
    val duration: String? = null,
    /** The file name's extension in capitals. */
    val format: String? = null
)
