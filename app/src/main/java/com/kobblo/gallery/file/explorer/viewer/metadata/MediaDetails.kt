package com.kobblo.gallery.file.explorer.viewer.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.TrackGroupArray
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import com.kobblo.gallery.file.explorer.viewer.decoder.ImageHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/** One titled block of "Show All". The rows are label and value, already formatted. */
class DetailGroup(@StringRes val title: Int, val titleSuffix: String? = null, val rows: List<Pair<String, String>>)

/** Everything the details panel knows about a file: a short summary up top, and the rest behind Show All. */
class MediaDetails(
    val dimensions: String? = null,
    val duration: String? = null,
    val camera: String? = null,
    val exposure: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val groups: List<DetailGroup> = emptyList(),
    /** The nearest town, from the table that ships with the app. */
    val place: String? = null
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null

    companion object {
        val Empty = MediaDetails()
    }
}

object MediaDetailsLoader {
    suspend fun load(context: Context, file: FileItem): MediaDetails = withContext(Dispatchers.IO) {
        try {
            if (file.type == "video") loadVideo(context, file) else loadImage(context, file)
        } catch (_: Exception) {
            // Details are a bonus. Whatever a strange file does to the readers, the viewer carries on.
            MediaDetails(groups = listOf(DetailGroup(R.string.viewer_group_file, rows = fileRows(context, file, null))))
        }
    }

    private fun isStream(file: FileItem) = file.path.startsWith("content://")

    private fun fileRows(context: Context, file: FileItem, dimensions: String?): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        rows.add(context.getString(R.string.media_detail_name) to file.name)
        if (!isStream(file)) {
            val real = File(file.path)
            real.parent?.let { rows.add(context.getString(R.string.media_detail_folder) to it) }
            if (real.exists()) {
                rows.add(context.getString(R.string.viewer_pdf_detail_size) to com.kobblo.gallery.file.explorer.data.core.FileSizes.label(real.length()))
                rows.add(context.getString(R.string.viewer_pdf_detail_modified) to DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(real.lastModified())))
            }
        }
        rows.add(context.getString(R.string.media_detail_type) to FileKinds.mimeTypeOf(file.name))
        dimensions?.let { rows.add(context.getString(R.string.dimensions_label) to it) }
        return rows
    }

    // ── Pictures ──────────────────────────────────────────────────────────────

    private fun loadImage(context: Context, file: FileItem): MediaDetails {
        val exif = openExif(context, file)
        val dimensions = imageDimensions(context, file, exif)
        val groups = ArrayList<DetailGroup>()
        groups.add(DetailGroup(R.string.viewer_group_file, rows = fileRows(context, file, dimensions)))

        val camera = MediaFormatting.cameraName(exif?.getAttribute(ExifInterface.TAG_MAKE), exif?.getAttribute(ExifInterface.TAG_MODEL))

        var exposure: String? = null
        var latitude: Double? = null
        var longitude: Double? = null
        if (exif != null) {
            val cameraRows = buildList {
                camera?.let { add(context.getString(R.string.viewer_group_camera) to it) }
                exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.ifEmpty { null }?.let { add(context.getString(R.string.media_detail_lens) to it) }
                exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim()?.ifEmpty { null }?.let { add(context.getString(R.string.media_detail_software) to it) }
                (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))
                    ?.let { add(context.getString(R.string.media_detail_taken) to it) }
            }
            if (cameraRows.isNotEmpty()) groups.add(DetailGroup(R.string.viewer_group_camera, rows = cameraRows))

            val aperture = MediaFormatting.aperture(exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0))
            val shutter = MediaFormatting.exposure(exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0))
            val iso = MediaFormatting.iso(exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0))
            val focal = MediaFormatting.focalLength(exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0))
            exposure = listOfNotNull(aperture, shutter, iso, focal).joinToString("  ").ifEmpty { null }
            val exposureRows = buildList {
                aperture?.let { add(context.getString(R.string.media_detail_aperture) to it) }
                shutter?.let { add(context.getString(R.string.media_detail_shutter) to it) }
                iso?.let { add(context.getString(R.string.media_detail_sensitivity) to it) }
                focal?.let { add(context.getString(R.string.media_detail_focal_length) to it) }
                MediaFormatting.focalLength(exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0.0))
                    ?.let { add(context.getString(R.string.media_detail_focal_length_35mm) to it) }
                MediaFormatting.exposureBias(exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, 0.0))?.let { add(context.getString(R.string.media_detail_bias) to it) }
                exif.getAttribute(ExifInterface.TAG_FLASH)?.toIntOrNull()?.let {
                    add(context.getString(R.string.media_detail_flash) to context.getString(if (it and 1 == 1) R.string.media_detail_flash_fired else R.string.media_detail_flash_not_fired))
                }
                exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE)?.toIntOrNull()?.let {
                    add(context.getString(R.string.media_detail_white_balance) to context.getString(if (it == 1) R.string.media_detail_white_balance_manual else R.string.media_detail_white_balance_auto))
                }
            }
            if (exposureRows.isNotEmpty()) groups.add(DetailGroup(R.string.viewer_group_exposure, rows = exposureRows))

            exif.latLong?.let { (lat, lon) ->
                latitude = lat
                longitude = lon
                val rows = buildList {
                    add(context.getString(R.string.media_detail_coordinates) to MediaFormatting.coordinate(lat, lon))
                    val altitude = exif.getAltitude(Double.NaN)
                    if (!altitude.isNaN()) add(context.getString(R.string.media_detail_altitude) to "%.0f m".format(altitude))
                }
                groups.add(DetailGroup(R.string.viewer_group_location, rows = rows))
            }
        }

        addRawMetadata(context, file, exif, groups)
        return MediaDetails(
            dimensions = dimensions,
            camera = camera,
            exposure = exposure,
            latitude = latitude,
            longitude = longitude,
            groups = groups,
            place = placeLabel(context, latitude, longitude)
        )
    }

    private fun placeLabel(context: Context, latitude: Double?, longitude: Double?): String? {
        if (latitude == null || longitude == null) return null
        return try {
            val table = com.kobblo.gallery.file.explorer.gallery.places.PlaceTable.get(context)
            val place = table.nearest(latitude, longitude)
            if (place < 0) null else context.getString(
                R.string.viewer_near_place,
                table.name(place),
                com.kobblo.gallery.file.explorer.gallery.places.countryName(table.countryCode(place))
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun addRawMetadata(context: Context, file: FileItem, exif: ExifInterface?, groups: MutableList<DetailGroup>) {
        val isJpegName = FileKinds.extensionOf(file.name) in setOf("jpg", "jpeg", "jpe", "jfif")
        var xmp: String? = null
        var iptc: ByteArray? = null
        if (isJpegName) {
            withSource(context, file) { source ->
                xmp = JpegSegments.xmp(source)
                iptc = JpegSegments.iptc(source)
            }
        }
        if (xmp == null) xmp = exif?.getAttribute(ExifInterface.TAG_XMP)
        xmp?.let { packet ->
            val rows = Xmp.properties(packet)
            if (rows.isNotEmpty()) groups.add(DetailGroup(R.string.viewer_group_xmp, rows = rows))
        }
        iptc?.let { block ->
            // The data sets come back by their English names, which the index also looks them up by.
            val rows = Iptc.parse(block).map { (name, value) -> (Iptc.labelOf(name)?.let { context.getString(it) } ?: name) to value }
            if (rows.isNotEmpty()) groups.add(DetailGroup(R.string.viewer_group_iptc, rows = rows))
        }
    }

    private inline fun withSource(context: Context, file: FileItem, block: (ByteSource) -> Unit) {
        if (isStream(file)) {
            // A stream can't seek, and everything wanted here sits at the front of the file anyway.
            val head = context.contentResolver.openInputStream(Uri.parse(file.path))?.use { input ->
                val buffer = ByteArray(StreamHeadBytes)
                var filled = 0
                while (filled < buffer.size) {
                    val got = input.read(buffer, filled, buffer.size - filled)
                    if (got < 0) break
                    filled += got
                }
                buffer.copyOf(filled)
            } ?: return
            block(ArrayByteSource(head))
        } else {
            FileByteSource(File(file.path)).use { block(it) }
        }
    }

    private fun openExif(context: Context, file: FileItem): ExifInterface? = try {
        if (isStream(file)) {
            context.contentResolver.openInputStream(Uri.parse(file.path))?.use { ExifInterface(it) }
        } else {
            ExifInterface(File(file.path))
        }
    } catch (_: Exception) {
        null
    }

    private fun imageDimensions(context: Context, file: FileItem, exif: ExifInterface?): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            if (isStream(file)) {
                context.contentResolver.openInputStream(Uri.parse(file.path))?.use { BitmapFactory.decodeStream(it, null, bounds) }
            } else {
                BitmapFactory.decodeFile(file.path, bounds)
            }
        } catch (_: Exception) {
        }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) return "${bounds.outWidth} × ${bounds.outHeight}"
        if (!isStream(file)) {
            ImageHeaders.dimensions(File(file.path))?.let { return "${it.first} × ${it.second}" }
        }
        val width = exif?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) ?: 0
        val height = exif?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) ?: 0
        return if (width > 0 && height > 0) "$width × $height" else null
    }

    // ── Videos ────────────────────────────────────────────────────────────────

    private fun loadVideo(context: Context, file: FileItem): MediaDetails {
        val uri = if (isStream(file)) Uri.parse(file.path) else Uri.fromFile(File(file.path))
        val retriever = MediaMetadataRetriever()
        var duration: String? = null
        var width = 0
        var height = 0
        var created: String? = null
        var location: Pair<Double, Double>? = null
        var bitRate: Long? = null
        try {
            retriever.setDataSource(context, uri)
            duration = MediaFormatting.duration(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            // The tracks report their unrotated size, so a portrait clip needs its sides swapped.
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val swap = width
                width = height
                height = swap
            }
            created = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            location = MediaFormatting.parseIso6709(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
                ?: if (isStream(file)) null else keyedLocation(File(file.path))
            bitRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        } catch (_: Exception) {
            // The platform can't open every container the player can. The tracks below may still read.
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        val tracks = readTracksWithExtractor(context, uri).ifEmpty { readTracksWithPlayer(context, uri) }
        val firstVideo = tracks.firstOrNull { it.kind == TrackKind.Video }
        if (width == 0 && firstVideo != null) {
            width = firstVideo.width
            height = firstVideo.height
        }
        val dimensions = if (width > 0 && height > 0) "$width × $height" else null

        val groups = ArrayList<DetailGroup>()
        val fileRows = fileRows(context, file, dimensions).toMutableList()
        duration?.let { fileRows.add(context.getString(R.string.media_detail_duration) to it) }
        MediaFormatting.bitRate(bitRate)?.let { fileRows.add(context.getString(R.string.media_detail_bitrate) to it) }
        created?.let { fileRows.add(context.getString(R.string.media_detail_recorded) to it) }
        groups.add(DetailGroup(R.string.viewer_group_file, rows = fileRows))

        location?.let { (lat, lon) ->
            groups.add(DetailGroup(R.string.viewer_group_location, rows = listOf(context.getString(R.string.media_detail_coordinates) to MediaFormatting.coordinate(lat, lon))))
        }
        tracks.forEachIndexed { index, track ->
            val sameKind = tracks.count { it.kind == track.kind }
            val suffix = if (sameKind > 1) " ${tracks.take(index + 1).count { it.kind == track.kind }}" else null
            val title = when (track.kind) {
                TrackKind.Video -> R.string.viewer_group_video
                TrackKind.Audio -> R.string.viewer_group_audio
                TrackKind.Text -> R.string.viewer_group_subtitle
            }
            groups.add(DetailGroup(title, suffix, track.rows(context)))
        }

        val summary = firstVideo?.let { listOfNotNull(CodecNames.label(it.mime), MediaFormatting.frameRate(it.frameRate)).joinToString("  ") }?.ifEmpty { null }
        return MediaDetails(
            dimensions = dimensions,
            duration = duration,
            exposure = summary,
            latitude = location?.first,
            longitude = location?.second,
            groups = groups
        )
    }

    /** iPhones and others keep it as a keyed tag, which the platform's retriever doesn't always surface. */
    private fun keyedLocation(file: File): Pair<Double, Double>? = try {
        java.io.RandomAccessFile(file, "r").use { handle ->
            if (Mp4MetadataStripper.isMp4(handle)) MediaFormatting.parseIso6709(Mp4MetadataStripper.findKeyedLocation(handle)) else null
        }
    } catch (_: Exception) {
        null
    }

    private enum class TrackKind { Video, Audio, Text }

    private class Track(
        val kind: TrackKind,
        val mime: String?,
        val width: Int = 0,
        val height: Int = 0,
        val frameRate: Float? = null,
        val bitRate: Long? = null,
        val channels: Int? = null,
        val sampleRate: Int? = null,
        val language: String? = null
    ) {
        fun rows(context: Context): List<Pair<String, String>> = buildList {
            CodecNames.label(mime)?.let { add(context.getString(R.string.media_detail_codec) to it) }
            if (width > 0 && height > 0) add(context.getString(R.string.media_detail_resolution) to "$width × $height")
            MediaFormatting.frameRate(frameRate)?.let { add(context.getString(R.string.media_detail_frame_rate) to it) }
            MediaFormatting.bitRate(bitRate)?.let { add(context.getString(R.string.media_detail_bitrate) to it) }
            MediaFormatting.channels(context, channels)?.let { add(context.getString(R.string.media_detail_channels) to it) }
            MediaFormatting.sampleRate(sampleRate)?.let { add(context.getString(R.string.media_detail_sample_rate) to it) }
            language?.takeIf { it.isNotEmpty() && it != "und" }?.let { add(context.getString(R.string.media_detail_language) to it) }
        }
    }

    private fun readTracksWithExtractor(context: Context, uri: Uri): List<Track> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).mapNotNull { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@mapNotNull null
                val kind = kindOf(mime) ?: return@mapNotNull null
                Track(
                    kind = kind,
                    mime = mime,
                    width = format.intOrZero(MediaFormat.KEY_WIDTH),
                    height = format.intOrZero(MediaFormat.KEY_HEIGHT),
                    frameRate = format.numberOrNull(MediaFormat.KEY_FRAME_RATE),
                    bitRate = format.intOrZero(MediaFormat.KEY_BIT_RATE).takeIf { it > 0 }?.toLong(),
                    channels = format.intOrZero(MediaFormat.KEY_CHANNEL_COUNT).takeIf { it > 0 },
                    sampleRate = format.intOrZero(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 },
                    language = format.getString(MediaFormat.KEY_LANGUAGE)
                )
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** ExoPlayer's parsers read AVI and FLV, which `MediaExtractor` won't open. */
    @OptIn(UnstableApi::class)
    private fun readTracksWithPlayer(context: Context, uri: Uri): List<Track> = try {
        val groups: TrackGroupArray = MetadataRetriever
            .retrieveMetadata(context.applicationContext, MediaItem.fromUri(uri))
            .get(PlayerProbeSeconds, TimeUnit.SECONDS)
        (0 until groups.length).mapNotNull { index ->
            val format = groups.get(index).getFormat(0)
            val mime = format.sampleMimeType ?: return@mapNotNull null
            val kind = kindOf(mime) ?: return@mapNotNull null
            Track(
                kind = kind,
                mime = mime,
                width = format.width.takeIf { it > 0 } ?: 0,
                height = format.height.takeIf { it > 0 } ?: 0,
                frameRate = format.frameRate.takeIf { it > 0f },
                bitRate = format.bitrate.takeIf { it > 0 }?.toLong(),
                channels = format.channelCount.takeIf { it > 0 },
                sampleRate = format.sampleRate.takeIf { it > 0 },
                language = format.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun kindOf(mime: String): TrackKind? = when {
        mime.startsWith("video/") -> TrackKind.Video
        mime.startsWith("audio/") -> TrackKind.Audio
        mime.startsWith("text/") || mime.startsWith("application/x-subrip") -> TrackKind.Text
        else -> null
    }

    private fun MediaFormat.intOrZero(key: String): Int = try {
        if (containsKey(key)) getInteger(key) else 0
    } catch (_: Exception) {
        0
    }

    private fun MediaFormat.numberOrNull(key: String): Float? = try {
        if (!containsKey(key)) null
        else try { getInteger(key).toFloat() } catch (_: ClassCastException) { getFloat(key) }
    } catch (_: Exception) {
        null
    }

    private const val StreamHeadBytes = 2 * 1024 * 1024
    private const val PlayerProbeSeconds = 5L
}
