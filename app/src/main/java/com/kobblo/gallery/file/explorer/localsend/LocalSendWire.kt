package com.kobblo.gallery.file.explorer.localsend

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.kobblo.gallery.file.explorer.transfer.core.PeerNames
import java.time.Instant

/**
 * The fixed parts of LocalSend protocol version 2: where devices find each other, the paths they call, and how
 * the JSON they exchange is written and read. Discovery, the server and the client all go through here, so
 * there is one place that decides what leaves this phone and what it makes of what arrives.
 *
 * Other apps and older builds of this one are on the other end, so nothing here can change without them.
 */
internal object LocalSendWire {
    /** The multicast group every device announces itself to and listens on. */
    const val GROUP_ADDRESS = "224.0.0.167"
    const val GROUP_PORT = 53317

    const val INFO_PATH = "/api/localsend/v2/info"
    const val REGISTER_PATH = "/api/localsend/v2/register"
    const val PREPARE_PATH = "/api/localsend/v2/prepare-upload"
    const val UPLOAD_PATH = "/api/localsend/v2/upload"
    const val CANCEL_PATH = "/api/localsend/v2/cancel"

    /** The query parameters of an upload and a cancel. The last two are this app's own, for carrying a file on. */
    const val SESSION_PARAM = "sessionId"
    const val FILE_PARAM = "fileId"
    const val TOKEN_PARAM = "token"
    const val OFFSET_PARAM = "offset"
    const val TAIL_PARAM = "tail"

    const val JSON_TYPE = "application/json"
    const val JSON_UTF8_TYPE = "application/json; charset=utf-8"
    const val BINARY_TYPE = "application/octet-stream"

    const val VERSION = "2.0"
    const val HTTPS = "https"
    const val HTTP = "http"
    const val MOBILE = "mobile"

    /**
     * Said in the body of a refusal, where the protocol's status codes run out: a 500 that means the storage is
     * full, and a 403 that means nobody answered rather than somebody said no. Only this app reads them.
     */
    const val STORAGE_FULL = "insufficient-storage"
    const val NOT_ANSWERED = "not-answered"

    private val gson = Gson()

    fun write(device: DeviceDto): String = gson.toJson(device)

    fun write(offer: PrepareUploadRequestDto): String = gson.toJson(offer)

    fun write(answer: PrepareUploadResponseDto): String = gson.toJson(answer)

    /** Null for an empty body. A body that isn't this JSON at all throws [JsonParseException]. */
    @Throws(JsonParseException::class)
    fun readDevice(json: String?): DeviceDto? = gson.fromJson(json, DeviceDto::class.java)

    @Throws(JsonParseException::class)
    fun readOffer(json: String?): PrepareUploadRequestDto? = gson.fromJson(json, PrepareUploadRequestDto::class.java)

    @Throws(JsonParseException::class)
    fun readAnswer(json: String?): PrepareUploadResponseDto? = gson.fromJson(json, PrepareUploadResponseDto::class.java)

    /** One datagram: this device, saying whether it wants to hear back. */
    fun announcement(device: DeviceDto, wantsReply: Boolean): ByteArray =
        write(device.copy(announce = wantsReply)).toByteArray(Charsets.UTF_8)

    /** The only two schemes a device can be reached by. Anything that isn't plainly `http` is treated as TLS. */
    fun scheme(stated: String): String = if (stated == HTTP) HTTP else HTTPS

    /**
     * What goes in a prepare-upload for [items], from [sender]. A file's modified time travels so that the same
     * file can be recognised on a later try, and a note travels whole, in its preview.
     */
    fun offer(sender: DeviceDto, items: List<TransferFileItem>): PrepareUploadRequestDto = PrepareUploadRequestDto(
        info = sender.copy(announce = null),
        files = items.associate { item ->
            item.fileId to FileDto(
                id = item.fileId,
                fileName = item.fileName,
                size = item.size,
                fileType = item.fileType,
                preview = item.preview,
                metadata = if (item.modifiedMs > 0) FileMetadataDto(modified = Instant.ofEpochMilli(item.modifiedMs).toString()) else null
            )
        }
    )

    /**
     * [device] as the list of nearby devices shows it, found at [ip]. Its name and model are whatever it chose to
     * send, so both are cleaned first. Where it's reached and what it's held to default to what it said, and can
     * be given instead when something more trustworthy is known, such as the address a connection came from.
     *
     * Null when there's no [protocol] or [fingerprint] to go by, which is what a body with a JSON `null` in
     * their place comes to.
     */
    fun peer(
        device: DeviceDto,
        ip: String,
        unknownName: String,
        nowMs: Long,
        port: Int = device.port,
        protocol: String? = device.statedProtocol,
        fingerprint: String? = device.statedFingerprint
    ): NearbyPeer? {
        if (protocol == null || fingerprint == null) return null
        return NearbyPeer(
            ip = ip,
            port = port,
            alias = PeerNames.clean(device.alias, unknownName),
            deviceModel = device.deviceModel?.let { PeerNames.clean(it, unknownName) },
            deviceType = device.deviceType ?: MOBILE,
            protocol = protocol,
            fingerprint = fingerprint,
            lastSeenMs = nowMs
        )
    }
}

/*
 * Gson fills a DTO's fields by reflection, so a sender that writes `"protocol": null` gets a real null into a
 * field Kotlin calls non-null. These read such fields as what they may really be, so that the null is met here
 * and not wherever the value happens to be used first.
 */

internal val DeviceDto.statedProtocol: String? get() = protocol

internal val DeviceDto.statedFingerprint: String? get() = fingerprint

internal val DeviceDto.statedVersion: String? get() = version

internal val PrepareUploadResponseDto.statedSessionId: String? get() = sessionId

internal val PrepareUploadResponseDto.statedFiles: Map<String, String>? get() = files
