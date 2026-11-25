package com.kobblo.gallery.file.explorer.gallery

import com.kobblo.gallery.file.explorer.FileItem
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Albums made from what a file is, rather than where it lives. */
enum class SmartKind(
    /**
     * Whether this is another view of the same stream as Home, so an album kept off Home stays out of it too.
     * Asking for Screenshots or GIFs by name is asking for all of them.
     */
    val followsHome: Boolean = false
) {
    FAVORITES,
    RECENTLY_ADDED(followsHome = true),
    ON_THIS_DAY(followsHome = true),
    CAMERA,
    SCREENSHOTS,
    VIDEOS,
    GIFS,
    RAW,
    PANORAMAS,
    MOTION_PHOTOS
}

/** What the system's index knows about a file beyond the feed's own fields. Zero means unknown. */
class MediaFacts(val width: Int, val height: Int, val addedMillis: Long, val mime: String?, val takenMillis: Long = 0L)

object ProbeFlags {
    const val PANORAMA = 1
    const val MOTION_PHOTO = 2
}

private val RawExtensions = setOf("dng", "raw", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "orf", "rw2", "raf", "pef", "srw", "x3f", "erf", "kdc", "mrw", "3fr")
private val DcfFolder = Regex("^\\d{3}[A-Za-z0-9_]{5}$")
private const val RecentDays = 30
private const val DayMillis = 24L * 60 * 60 * 1000

/** Wide enough that no camera's normal frame qualifies, and big enough to skip web banners. */
private const val PanoramaRatio = 2.5f
private const val PanoramaMinWidth = 1200

fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

fun folderNameOf(path: String): String = path.substringBeforeLast('/', "").substringAfterLast('/')

fun isScreenshotFile(path: String, name: String): Boolean {
    val folder = folderNameOf(path).lowercase(Locale.ROOT)
    if (folder.contains("screenshot") || folder.contains("screen_shot") || folder.contains("screencapture")) return true
    val lower = name.lowercase(Locale.ROOT)
    return lower.startsWith("screenshot") || lower.startsWith("screen_shot") || lower.startsWith("screencapture")
}

/** The camera's own folder, under any of the names phones and cameras use for it. */
fun isCameraFile(path: String, name: String): Boolean {
    if (isScreenshotFile(path, name)) return false
    val folder = folderNameOf(path)
    if (folder.equals("Camera", ignoreCase = true)) return true
    val parent = folderNameOf(path.substringBeforeLast('/', ""))
    return when {
        folder.equals("DCIM", ignoreCase = true) -> true
        parent.equals("DCIM", ignoreCase = true) -> DcfFolder.matches(folder)
        else -> false
    }
}

fun isGifFile(name: String, mime: String?): Boolean =
    mime.equals("image/gif", ignoreCase = true) || extensionOf(name) == "gif"

fun isRawFile(name: String, mime: String?): Boolean {
    if (extensionOf(name) in RawExtensions) return true
    val lower = mime?.lowercase(Locale.ROOT) ?: return false
    return lower == "image/x-adobe-dng" || lower == "image/dng" || lower.startsWith("image/x-raw") ||
        lower.startsWith("image/x-canon") || lower.startsWith("image/x-nikon") || lower.startsWith("image/x-sony")
}

fun isPanorama(width: Int, height: Int, flags: Int): Boolean {
    if (flags and ProbeFlags.PANORAMA != 0) return true
    if (width < PanoramaMinWidth || height <= 0) return false
    return width.toFloat() / height >= PanoramaRatio
}

/** The two common camera naming schemes give it away. Everything else needs a look inside. */
fun isMotionPhotoName(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return lower.startsWith("mvimg_") || lower.endsWith(".mp.jpg") || lower.endsWith(".mp.jpeg") || lower.endsWith(".mp.heic")
}

fun isRecentlyAdded(addedMillis: Long, nowMillis: Long): Boolean =
    addedMillis > 0L && nowMillis - addedMillis <= RecentDays * DayMillis

/**
 * "Now", kept ready for testing a whole library against it. One calendar is reused, so this belongs to
 * a single thread and a single pass.
 */
class SmartClock(val nowMillis: Long, zone: TimeZone = TimeZone.getDefault()) {
    private val calendar = Calendar.getInstance(zone, Locale.ROOT).apply { timeInMillis = nowMillis }
    private val year = calendar.get(Calendar.YEAR)
    private val month = calendar.get(Calendar.MONTH)
    private val day = calendar.get(Calendar.DAY_OF_MONTH)

    /** The same date in an earlier year. */
    fun isOnThisDay(moment: Long): Boolean {
        calendar.timeInMillis = moment
        return calendar.get(Calendar.YEAR) < year &&
            calendar.get(Calendar.MONTH) == month &&
            calendar.get(Calendar.DAY_OF_MONTH) == day
    }
}

/** Whether a file belongs in a smart album. */
fun matchesSmartAlbum(
    kind: SmartKind,
    file: FileItem,
    facts: MediaFacts?,
    flags: Int,
    clock: SmartClock
): Boolean = when (kind) {
    SmartKind.FAVORITES -> file.isPinned
    SmartKind.RECENTLY_ADDED -> isRecentlyAdded(facts?.addedMillis ?: 0L, clock.nowMillis)
    SmartKind.ON_THIS_DAY -> clock.isOnThisDay(file.lastModified)
    SmartKind.CAMERA -> isCameraFile(file.path, file.name)
    SmartKind.SCREENSHOTS -> isScreenshotFile(file.path, file.name)
    SmartKind.VIDEOS -> file.type == "video"
    SmartKind.GIFS -> file.type == "image" && isGifFile(file.name, facts?.mime)
    SmartKind.RAW -> file.type == "image" && isRawFile(file.name, facts?.mime)
    SmartKind.PANORAMAS -> file.type == "image" && !isScreenshotFile(file.path, file.name) &&
        isPanorama(facts?.width ?: 0, facts?.height ?: 0, flags)
    SmartKind.MOTION_PHOTOS -> file.type == "image" && (flags and ProbeFlags.MOTION_PHOTO != 0 || isMotionPhotoName(file.name))
}

/**
 * Reads the markers cameras leave in a photo's first and last bytes. The Google and most other
 * phones' formats declare themselves in XMP near the start, and Samsung's older one ends the file with
 * a tag. A brand-new container is the only thing this can miss, and then the photo is simply not
 * listed.
 */
object MediaProbe {
    private val PanoramaMarkers = listOf("GPano:", "http://ns.google.com/photos/1.0/panorama/").map { it.toByteArray(Charsets.US_ASCII) }
    private val MotionMarkers = listOf(
        "MotionPhoto=\"1\"",
        "MotionPhoto>1<",
        "MicroVideo=\"1\"",
        "MicroVideo>1<",
        "MotionPhoto_Data"
    ).map { it.toByteArray(Charsets.US_ASCII) }
    private val TailMotionMarker = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)

    fun inspect(head: ByteArray, headLength: Int, tail: ByteArray, tailLength: Int): Int {
        var flags = 0
        if (PanoramaMarkers.any { indexOf(head, headLength, it) >= 0 }) flags = flags or ProbeFlags.PANORAMA
        if (MotionMarkers.any { indexOf(head, headLength, it) >= 0 } || indexOf(tail, tailLength, TailMotionMarker) >= 0) {
            flags = flags or ProbeFlags.MOTION_PHOTO
        }
        return flags
    }

    /** Only these containers carry the markers. */
    fun isProbeable(name: String): Boolean = when (extensionOf(name)) {
        "jpg", "jpeg", "heic", "heif" -> true
        else -> false
    }

    internal fun indexOf(data: ByteArray, length: Int, marker: ByteArray): Int {
        val last = length - marker.size
        var i = 0
        while (i <= last) {
            if (data[i] == marker[0]) {
                var j = 1
                while (j < marker.size && data[i + j] == marker[j]) j++
                if (j == marker.size) return i
            }
            i++
        }
        return -1
    }
}
