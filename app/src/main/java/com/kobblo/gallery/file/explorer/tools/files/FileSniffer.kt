package com.kobblo.gallery.file.explorer.tools.files

import androidx.annotation.StringRes
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.editor.TextCodec

/**
 * What a file really is, judged from its first bytes rather than its name. [label] is a fixed English
 * name that code matches on; [labelRes] is what the screen shows.
 */
data class SniffedKind(val label: String, @StringRes val labelRes: Int, val isText: Boolean = false)

object FileSniffer {
    /** How much of a file [sniff] looks at. A disc image's mark sits 32 KB in, past its boot area. */
    const val SAMPLE_SIZE = 0x8006

    private class Magic(val bytes: IntArray, val label: String, @StringRes val labelRes: Int, val at: Int = 0, val strong: Boolean = false)

    private fun ascii(text: String) = text.map { it.code }.toIntArray()

    private val magics = listOf(
        Magic(intArrayOf(0x89, 0x50, 0x4E, 0x47), "PNG image", R.string.tool_kind_png),
        Magic(intArrayOf(0xFF, 0xD8, 0xFF), "JPEG image", R.string.tool_kind_jpeg),
        Magic(ascii("GIF8"), "GIF image", R.string.tool_kind_gif),
        Magic(ascii("BM"), "BMP image", R.string.tool_kind_bmp),
        Magic(intArrayOf(0x49, 0x49, 0x2A, 0x00), "TIFF image", R.string.tool_kind_tiff),
        Magic(intArrayOf(0x4D, 0x4D, 0x00, 0x2A), "TIFF image", R.string.tool_kind_tiff),
        Magic(ascii("8BPS"), "Photoshop document", R.string.tool_kind_photoshop),
        // A PDF's first pages can pass for text, so this one mark has to outrank the check for it.
        Magic(ascii("%PDF"), "PDF document", R.string.tool_kind_pdf, strong = true),
        Magic(intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1), "Microsoft Office document (older format)", R.string.tool_kind_office_legacy),
        Magic(ascii("{\\rtf"), "Rich Text document", R.string.tool_kind_rtf),
        Magic(ascii("bplist"), "Apple property list (binary)", R.string.tool_kind_plist),
        Magic(ascii("!<arch>\ndebian"), "Debian package", R.string.tool_kind_deb),
        Magic(ascii("!<arch>"), "Unix archive", R.string.tool_kind_ar),
        Magic(intArrayOf(0xED, 0xAB, 0xEE, 0xDB), "RPM package", R.string.tool_kind_rpm),
        Magic(ascii("CD001"), "Disc image (ISO)", R.string.tool_kind_iso, at = 0x8001),
        Magic(ascii("Rar!"), "RAR archive", R.string.archive_kind_rar),
        Magic(intArrayOf(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C), "7-Zip archive", R.string.tool_kind_7z),
        Magic(intArrayOf(0x1F, 0x8B), "Gzip archive", R.string.tool_kind_gzip),
        Magic(ascii("BZh"), "Bzip2 archive", R.string.tool_kind_bzip2),
        Magic(intArrayOf(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00), "XZ archive", R.string.tool_kind_xz),
        Magic(intArrayOf(0x28, 0xB5, 0x2F, 0xFD), "Zstandard archive", R.string.tool_kind_zstd),
        Magic(ascii("ustar"), "Tar archive", R.string.tool_kind_tar, at = 257),
        Magic(intArrayOf(0x7F, 0x45, 0x4C, 0x46), "Linux or Android program (ELF)", R.string.tool_kind_elf),
        Magic(intArrayOf(0xCA, 0xFE, 0xBA, 0xBE), "Java class file", R.string.tool_kind_class),
        Magic(ascii("dex\n"), "Android bytecode (DEX)", R.string.tool_kind_dex),
        Magic(ascii("MZ"), "Windows program", R.string.tool_kind_exe),
        Magic(ascii("SQLite format 3"), "SQLite database", R.string.tool_kind_sqlite),
        Magic(ascii("ID3"), "MP3 audio", R.string.tool_kind_mp3),
        Magic(ascii("fLaC"), "FLAC audio", R.string.tool_kind_flac),
        Magic(ascii("OggS"), "Ogg media", R.string.tool_kind_ogg),
        Magic(intArrayOf(0x1A, 0x45, 0xDF, 0xA3), "Matroska or WebM video", R.string.tool_kind_mkv),
        Magic(intArrayOf(0x00, 0x61, 0x73, 0x6D), "WebAssembly module", R.string.tool_kind_wasm),
        Magic(ascii("OTTO"), "OpenType font", R.string.tool_kind_otf),
        Magic(intArrayOf(0x00, 0x01, 0x00, 0x00), "TrueType font", R.string.tool_kind_ttf),
        Magic(ascii("ttcf"), "Font collection", R.string.tool_kind_ttc),
        Magic(ascii("wOFF"), "Web font (WOFF)", R.string.tool_kind_woff),
        Magic(ascii("wOF2"), "Web font (WOFF2)", R.string.tool_kind_woff2)
    )

    /** Formats with no mark at their start, which only their name can suggest. */
    @StringRes
    fun labelForName(name: String): Int? = when (name.substringAfterLast('.', "").lowercase()) {
        "dmg" -> R.string.tool_kind_dmg
        "img" -> R.string.tool_kind_img
        "bin" -> R.string.tool_kind_bin
        "dat" -> R.string.tool_kind_dat
        else -> null
    }

    fun sniff(sample: ByteArray): SniffedKind? {
        if (sample.isEmpty()) return null
        container(sample)?.let { return it }
        // Plenty of marks are ordinary letters, and "BMW" starts a lot of notes. Only real text is
        // free of control bytes, while the formats those marks belong to are full of them.
        val isText = TextCodec.looksLikeText(sample)
        magics.firstOrNull { matches(sample, it) && (it.strong || !isText || !isPrintable(it)) }?.let { return SniffedKind(it.label, it.labelRes) }
        if (isText) return SniffedKind("Text", R.string.tool_kind_text, isText = true)
        return null
    }

    /** Formats that share a generic wrapper, so they need more than their first four bytes. */
    private fun container(sample: ByteArray): SniffedKind? {
        val isRiff = startsWith(sample, 0, "RIFF")
        if (isRiff) {
            return when {
                startsWith(sample, 8, "WEBP") -> SniffedKind("WebP image", R.string.tool_kind_webp)
                startsWith(sample, 8, "WAVE") -> SniffedKind("WAV audio", R.string.tool_kind_wav)
                startsWith(sample, 8, "AVI ") -> SniffedKind("AVI video", R.string.tool_kind_avi)
                else -> SniffedKind("RIFF container", R.string.tool_kind_riff)
            }
        }
        if (startsWith(sample, 4, "ftyp")) {
            return when {
                startsWith(sample, 8, "heic") || startsWith(sample, 8, "mif1") -> SniffedKind("HEIF image", R.string.tool_kind_heif)
                startsWith(sample, 8, "avif") -> SniffedKind("AVIF image", R.string.tool_kind_avif)
                startsWith(sample, 8, "M4A") -> SniffedKind("MPEG-4 audio", R.string.tool_kind_m4a)
                else -> SniffedKind("MPEG-4 video", R.string.tool_kind_mp4)
            }
        }
        if (sample.size >= 4 && sample[0] == 0x50.toByte() && sample[1] == 0x4B.toByte() && sample[2] == 0x03.toByte()) {
            val text = String(sample, Charsets.ISO_8859_1)
            return when {
                "AndroidManifest.xml" in text || "classes.dex" in text -> SniffedKind("Android app (APK)", R.string.tool_kind_apk)
                "word/" in text -> SniffedKind("Word document", R.string.tool_kind_docx)
                "xl/" in text -> SniffedKind("Excel workbook", R.string.tool_kind_xlsx)
                "ppt/" in text -> SniffedKind("PowerPoint presentation", R.string.tool_kind_pptx)
                "mimetypeapplication/epub+zip" in text -> SniffedKind("EPUB book", R.string.tool_kind_epub)
                "application/vnd.oasis.opendocument.text" in text -> SniffedKind("OpenDocument text", R.string.tool_kind_odt)
                "application/vnd.oasis.opendocument.spreadsheet" in text -> SniffedKind("OpenDocument spreadsheet", R.string.tool_kind_ods)
                "application/vnd.oasis.opendocument.presentation" in text -> SniffedKind("OpenDocument presentation", R.string.tool_kind_odp)
                "META-INF/" in text -> SniffedKind("Java archive (JAR)", R.string.tool_kind_jar)
                else -> SniffedKind("ZIP archive", R.string.tool_kind_zip)
            }
        }
        return null
    }

    private fun isPrintable(magic: Magic) = magic.bytes.all { it in 0x20..0x7E || it == 0x0A }

    private fun startsWith(sample: ByteArray, at: Int, text: String): Boolean {
        if (sample.size < at + text.length) return false
        return text.indices.all { sample[at + it].toInt() == text[it].code }
    }

    private fun matches(sample: ByteArray, magic: Magic): Boolean {
        if (sample.size < magic.at + magic.bytes.size) return false
        return magic.bytes.indices.all { (sample[magic.at + it].toInt() and 0xFF) == magic.bytes[it] }
    }
}
