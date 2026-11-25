package com.kobblo.gallery.file.explorer.viewer

import java.io.File
import java.util.Locale

/**
 * The one place that decides what a file is from its name. `FileItem.type` used to be worked out
 * by a dozen slightly different lists, so a TIFF was a photo in one screen and a mystery in the next.
 */
object FileKinds {
    val Raw: Set<String> = setOf("dng", "cr2", "cr3", "nef", "nrw", "arw", "orf", "rw2", "raf", "srw", "pef")

    val Images: Set<String> = setOf(
        "jpg", "jpeg", "jpe", "jfif", "png", "apng", "gif", "webp", "bmp", "dib", "wbmp",
        "heic", "heif", "avif", "svg", "ico", "tif", "tiff", "psd", "psb", "tga", "ppm", "pgm", "pbm", "pnm"
    ) + Raw

    val Videos: Set<String> = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "3gp", "3g2", "ts", "m2ts", "mts", "flv", "f4v",
        "mpg", "mpeg", "vob", "ogv", "wmv", "asf", "rmvb", "rm"
    )

    val Audio: Set<String> = com.kobblo.gallery.file.explorer.music.AudioExtensions

    val Archives: Set<String> = setOf(
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "tbz2", "tbz", "txz",
        // ZIPs under another name, which the archive handlers recognize by their bytes.
        "jar", "aar", "war", "ear", "cbz", "cbr", "ipa"
    )

    val Docs: Set<String> = setOf(
        "pdf", "doc", "docx", "txt", "json", "xls", "xlsx", "ppt", "pptx", "xml", "html", "htm", "md", "csv", "log",
        "rtf", "odt", "ods", "odp", "epub", "tsv", "markdown"
    )

    val Apps: Set<String> = setOf("apk", "xapk", "apks", "apkm", "aab")

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** "image", "video", "audio", "archive", "doc", "apk" or "file": the values of `FileItem.type`. */
    fun typeOf(name: String): String {
        val ext = extensionOf(name)
        return when {
            ext in Images -> "image"
            ext in Videos -> "video"
            ext in Audio -> "audio"
            ext in Archives -> "archive"
            ext in Docs -> "doc"
            ext in Apps -> "apk"
            else -> "file"
        }
    }

    /**
     * [typeOf] for a real file, where a `.ts` can be looked at. It is either an MPEG transport stream
     * or TypeScript, and only the bytes tell them apart.
     */
    fun typeOf(file: File): String {
        val type = typeOf(file.name)
        if (type == "video" && extensionOf(file.name) == "ts" && !looksLikeTransportStream(file)) return "doc"
        return type
    }

    fun isImage(name: String): Boolean = extensionOf(name) in Images
    fun isVideo(name: String): Boolean = extensionOf(name) in Videos
    fun isRaw(name: String): Boolean = extensionOf(name) in Raw

    /** A transport stream carries a sync byte every 188 bytes, which no source text does. */
    fun looksLikeTransportStream(head: ByteArray): Boolean {
        if (head.size <= TsPacket) return false
        var position = 0
        var seen = 0
        while (position < head.size) {
            if (head[position] != TsSync) return false
            seen++
            position += TsPacket
        }
        return seen >= 2
    }

    private fun looksLikeTransportStream(file: File): Boolean = try {
        val head = ByteArray(TsPacket * 3)
        val read = file.inputStream().use { it.read(head) }
        // An empty or tiny file has no video in it either way.
        read > 0 && looksLikeTransportStream(head.copyOf(read))
    } catch (_: Exception) {
        // Unreadable says nothing about its contents, so the name stands.
        true
    }

    /** The MIME type to hand other apps. `MimeTypeMap` doesn't know most of these formats. */
    fun mimeTypeOf(name: String): String {
        val ext = extensionOf(name)
        Mimes[ext]?.let { return it }
        return when (typeOf(name)) {
            "image" -> "image/*"
            "video" -> "video/*"
            "audio" -> "audio/*"
            else -> "*/*"
        }
    }

    private const val TsPacket = 188
    private const val TsSync: Byte = 0x47

    private val Mimes = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg", "jfif" to "image/jpeg",
        "png" to "image/png", "apng" to "image/apng", "gif" to "image/gif", "webp" to "image/webp",
        "bmp" to "image/bmp", "dib" to "image/bmp", "wbmp" to "image/vnd.wap.wbmp",
        "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif", "svg" to "image/svg+xml",
        "ico" to "image/x-icon", "tif" to "image/tiff", "tiff" to "image/tiff",
        "psd" to "image/vnd.adobe.photoshop", "psb" to "image/vnd.adobe.photoshop",
        "tga" to "image/x-tga", "ppm" to "image/x-portable-pixmap", "pgm" to "image/x-portable-graymap",
        "pbm" to "image/x-portable-bitmap", "pnm" to "image/x-portable-anymap",
        "dng" to "image/x-adobe-dng", "cr2" to "image/x-canon-cr2", "cr3" to "image/x-canon-cr3",
        "nef" to "image/x-nikon-nef", "nrw" to "image/x-nikon-nrw", "arw" to "image/x-sony-arw",
        "orf" to "image/x-olympus-orf", "rw2" to "image/x-panasonic-rw2", "raf" to "image/x-fuji-raf",
        "srw" to "image/x-samsung-srw", "pef" to "image/x-pentax-pef",
        "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "avi" to "video/x-msvideo", "mov" to "video/quicktime", "3gp" to "video/3gpp", "3g2" to "video/3gpp2",
        "ts" to "video/mp2t", "m2ts" to "video/mp2t", "mts" to "video/mp2t", "flv" to "video/x-flv",
        "f4v" to "video/x-f4v", "mpg" to "video/mpeg", "mpeg" to "video/mpeg", "vob" to "video/mpeg",
        "ogv" to "video/ogg", "wmv" to "video/x-ms-wmv", "asf" to "video/x-ms-asf",
        "rmvb" to "application/vnd.rn-realmedia-vbr", "rm" to "application/vnd.rn-realmedia"
    )
}
