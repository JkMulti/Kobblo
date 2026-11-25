package com.kobblo.gallery.file.explorer.tools.files

/**
 * MIME types from a file's extension. The platform's own table lags behind on newer and developer
 * formats, so this answers for those first and leaves the rest to [platform], which is passed in to
 * keep this testable off the device.
 */
object MimeGuess {
    private val known = mapOf(
        "apk" to "application/vnd.android.package-archive",
        "apks" to "application/octet-stream",
        "avif" to "image/avif",
        "bz2" to "application/x-bzip2",
        "c" to "text/x-c",
        "conf" to "text/plain",
        "cpp" to "text/x-c++",
        "db" to "application/vnd.sqlite3",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "eml" to "message/rfc822",
        "epub" to "application/epub+zip",
        "m3u" to "audio/x-mpegurl",
        "m3u8" to "application/vnd.apple.mpegurl",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odt" to "application/vnd.oasis.opendocument.text",
        "pdf" to "application/pdf",
        "pls" to "audio/x-scpls",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "rtf" to "application/rtf",
        "sqlite" to "application/vnd.sqlite3",
        "torrent" to "application/x-bittorrent",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "css" to "text/css",
        "csv" to "text/csv",
        "dng" to "image/x-adobe-dng",
        "flac" to "audio/flac",
        "gradle" to "text/plain",
        "gz" to "application/gzip",
        "h" to "text/x-c",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "ics" to "text/calendar",
        "ini" to "text/plain",
        "java" to "text/x-java",
        "js" to "text/javascript",
        "json" to "application/json",
        "kt" to "text/x-kotlin",
        "kts" to "text/x-kotlin",
        "log" to "text/plain",
        "m4a" to "audio/mp4",
        "md" to "text/markdown",
        "mkv" to "video/x-matroska",
        "opus" to "audio/opus",
        "otf" to "font/otf",
        "properties" to "text/plain",
        "py" to "text/x-python",
        "rar" to "application/vnd.rar",
        "rs" to "text/x-rust",
        "sh" to "text/x-shellscript",
        "srt" to "application/x-subrip",
        "tar" to "application/x-tar",
        "tgz" to "application/gzip",
        "toml" to "text/plain",
        "ts" to "video/mp2t",
        "tsv" to "text/tab-separated-values",
        "ttf" to "font/ttf",
        "txt" to "text/plain",
        "vcf" to "text/vcard",
        "webm" to "video/webm",
        "webp" to "image/webp",
        "xml" to "text/xml",
        "xz" to "application/x-xz",
        "yaml" to "text/yaml",
        "yml" to "text/yaml",
        "zip" to "application/zip",
        "7z" to "application/x-7z-compressed"
    )

    const val FALLBACK = "application/octet-stream"

    fun forName(name: String, platform: (String) -> String? = { null }): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return FALLBACK
        return known[ext] ?: platform(ext) ?: FALLBACK
    }
}
