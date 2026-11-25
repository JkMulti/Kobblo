package com.kobblo.gallery.file.explorer.fileops

/** Why a rename didn't happen. */
enum class RenameProblem { BAD_NAME, NAME_TAKEN, FAILED }

/** A name that's one path segment and fits. Anything else would end up somewhere other than beside the file. */
internal fun isUsableFileName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\u0000' !in name &&
        SafeFiles.utf8Length(name) <= SafeFiles.MaxNameBytes

/** What can safely be done with a file on Google Drive. */
internal object DriveRules {
    /**
     * Google's own documents and its folders have no bytes to download. Treating one as an empty file used to
     * leave an empty file on the phone and, for a move, delete the original from Drive, folder contents and all.
     */
    fun isNativeGoogleType(mimeType: String?): Boolean = mimeType?.startsWith("application/vnd.google-apps.") == true

    /** Only a size that was reported and that matches proves the whole file arrived. */
    fun isComplete(expectedBytes: Long?, actualBytes: Long): Boolean = expectedBytes != null && expectedBytes == actualBytes
}

/**
 * Makes a path safe inside a SQL `LIKE`, where `_` and `%` match anything. It matters more than it looks:
 * deleting rows from the system's index deletes their files too, so `my_photos/%` would also take everything
 * in `my photos` and `my-photos`. Use it with `ESCAPE '\'`.
 */
internal fun escapeForLike(path: String): String =
    path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
