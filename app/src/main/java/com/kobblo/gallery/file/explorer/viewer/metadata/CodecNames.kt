package com.kobblo.gallery.file.explorer.viewer.metadata

/** What people call a codec, rather than its MIME type. */
object CodecNames {
    private val Names = mapOf(
        "video/avc" to "H.264",
        "video/hevc" to "HEVC",
        "video/dolby-vision" to "Dolby Vision",
        "video/av01" to "AV1",
        "video/x-vnd.on2.vp8" to "VP8",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/mp4v-es" to "MPEG-4",
        "video/3gpp" to "H.263",
        "video/mpeg2" to "MPEG-2",
        "video/mpeg" to "MPEG-1",
        "video/wvc1" to "VC-1",
        "video/wmv3" to "WMV3",
        "video/x-flv" to "FLV",
        "audio/mp4a-latm" to "AAC",
        "audio/mpeg" to "MP3",
        "audio/mpeg-L1" to "MP1",
        "audio/mpeg-L2" to "MP2",
        "audio/opus" to "Opus",
        "audio/vorbis" to "Vorbis",
        "audio/flac" to "FLAC",
        "audio/ac3" to "AC-3",
        "audio/eac3" to "E-AC-3",
        "audio/eac3-joc" to "Dolby Atmos",
        "audio/ac4" to "AC-4",
        "audio/vnd.dts" to "DTS",
        "audio/vnd.dts.hd" to "DTS-HD",
        "audio/3gpp" to "AMR-NB",
        "audio/amr-wb" to "AMR-WB",
        "audio/raw" to "PCM",
        "audio/g711-alaw" to "A-law",
        "audio/g711-mlaw" to "μ-law",
        "audio/alac" to "ALAC",
        "application/x-subrip" to "SubRip",
        "text/vtt" to "WebVTT",
        "text/x-ssa" to "SSA/ASS"
    )

    fun label(mime: String?): String? {
        if (mime.isNullOrEmpty()) return null
        return Names[mime] ?: mime.substringAfter('/').uppercase()
    }
}
