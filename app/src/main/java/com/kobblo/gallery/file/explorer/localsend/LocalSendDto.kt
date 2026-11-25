package com.kobblo.gallery.file.explorer.localsend

/*
 * The JSON bodies of LocalSend protocol version 2, exactly as they travel between devices.
 *
 * Gson reads and writes these classes by field name, in the order the fields are declared, and the release
 * build is told to leave every `*Dto` in this package alone. So a field here is a field on the wire: none of
 * them may be renamed, retyped, reordered or given a different default, and anything this app wants to remember
 * about a device or a file belongs in ShareModels.kt instead. A default is what a field reads as when a sender
 * leaves it out.
 */

/**
 * How a device describes itself: in the announcement it multicasts, in a register call and its reply, and as
 * the `info` of a prepare-upload.
 */
data class DeviceDto(
    val alias: String = "Unknown",
    val version: String = "2.0",
    val deviceModel: String? = null,
    /** One of `mobile`, `desktop`, `web`, `headless` or `server`. */
    val deviceType: String? = "mobile",
    val fingerprint: String = "",
    /** A device that doesn't say is taken to serve on the protocol's own port. */
    val port: Int = 53317,
    /** `https` or `http`. */
    val protocol: String = "https",
    val download: Boolean = false,
    /** In an announcement, whether the sender wants to hear back. Left out everywhere else. */
    val announce: Boolean? = null
)

/** The body of a prepare-upload: who is sending, and every file on offer by its id. */
data class PrepareUploadRequestDto(
    val info: DeviceDto? = null,
    val files: Map<String, FileDto>? = null
)

/** One file on offer. */
data class FileDto(
    val id: String = "",
    /** May hold folders, which is how a whole folder travels. */
    val fileName: String = "",
    val size: Long = 0L,
    val fileType: String? = null,
    val sha256: String? = null,
    /** For a text file, its whole content, which a receiver may show instead of saving anything. */
    val preview: String? = null,
    val metadata: FileMetadataDto? = null
)

/** A file's times, as ISO 8601 text. */
data class FileMetadataDto(
    val modified: String? = null,
    val accessed: String? = null
)

/**
 * The answer to an accepted prepare-upload. The protocol has [sessionId] and [files]. The rest are this app's
 * own additions, sent only to a sender that proved which device it is, and other apps never see them.
 */
data class PrepareUploadResponseDto(
    val sessionId: String = "",
    /** The token to upload each accepted file with, by file id. A file that isn't here wasn't accepted. */
    val files: Map<String, String> = emptyMap(),
    /** Ids of offered files that are already here from an earlier try, so they needn't travel again. */
    val alreadyHave: List<String>? = null,
    /** How many small files this app is happy to take at the same time. */
    val uploadSlots: Int? = null,
    /** How many bytes of a file are already here from a connection that dropped, by file id. */
    val resume: Map<String, Long>? = null
)
