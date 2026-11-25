package com.kobblo.gallery.file.explorer.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Finds the memory card (or a USB drive, which Android treats the same way) and says how full it is.
 *
 * No one way of asking works on every phone, so several are tried, from the one meant for the purpose
 * down to looking through the system's folders. A way that throws just counts as not knowing.
 */
object StorageUtils {
    private val MountedStates = setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)

    /** The root of the card, such as `/storage/1A2B-3C4D`, or null when there's none in the phone. */
    fun getSdCardPath(context: Context): String? =
        orNull { fromVolumes(context) }
            ?: orNull { fromAppFolders(context) }
            ?: orNull { fromMountTable() }
            ?: orNull { fromStorageFolder() }

    /**
     * The card's size and free space, in gigabytes, in that order. [path] is the card if it's already
     * known, which saves finding it again. Both are zero when there's no card to measure.
     */
    fun getSdCardStats(context: Context, path: String?): Pair<Float, Float> {
        (path ?: getSdCardPath(context))?.let(::measure)?.let { return it }
        // The root can be unreadable where the app's own folder on the same card isn't.
        val throughAppFolder = orNull {
            val builtIn = Environment.getExternalStorageDirectory().absolutePath
            ContextCompat.getExternalFilesDirs(context, null).asSequence()
                .filterNotNull()
                .filter { SdCardPaths.isOffBuiltIn(it.path, builtIn) }
                .firstNotNullOfOrNull { measure(it.path) }
        }
        return throughAppFolder ?: Pair(0f, 0f)
    }

    /** Whether [location] is on the card, given where the card is if that's known. */
    fun isSdCardLocation(location: String, sdCardPath: String?): Boolean =
        SdCardPaths.isSdCardLocation(location, sdCardPath)

    /** What the system lists as a mounted volume that isn't the built-in one. */
    private fun fromVolumes(context: Context): String? {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
        return manager.storageVolumes.asSequence()
            .filter { !it.isPrimary && it.state in MountedStates }
            .firstNotNullOfOrNull(::pathOf)
    }

    private fun pathOf(volume: StorageVolume): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val folder = volume.directory?.absolutePath?.let(SdCardPaths::readable)
            if (folder != null && File(folder).let { it.exists() || it.canRead() }) return folder
        }
        // Before Android 11 a volume doesn't say where it is, but a card is mounted under its id.
        val id = volume.uuid
        if (!id.isNullOrEmpty()) return File("/storage/$id").absolutePath
        return hiddenPathOf(volume)
    }

    /** The volume's path from a method that has always been there but was never made public. */
    private fun hiddenPathOf(volume: StorageVolume): String? = orNull {
        (volume.javaClass.getMethod("getPath").invoke(volume) as? String)
            ?.takeUnless { it.isEmpty() }
            ?.let(SdCardPaths::readable)
    }

    /** Android gives the app a folder on every storage, so one that isn't on the built-in storage gives the card away. */
    private fun fromAppFolders(context: Context): String? {
        val builtIn = Environment.getExternalStorageDirectory().absolutePath
        return ContextCompat.getExternalFilesDirs(context, null).asSequence()
            .filterNotNull()
            .map { SdCardPaths.readable(it.absolutePath) }
            .firstOrNull { SdCardPaths.isOffBuiltIn(it, builtIn) }
            ?.let(SdCardPaths::rootOfAppFolder)
    }

    private fun fromMountTable(): String? {
        val mounts = File("/proc/mounts")
        if (!mounts.exists() || !mounts.canRead()) return null
        return mounts.useLines { SdCardPaths.mountedCard(it) }
    }

    private fun fromStorageFolder(): String? =
        File("/storage").listFiles()
            ?.firstOrNull { it.isDirectory && SdCardPaths.isCardFolderName(it.name) }
            ?.absolutePath

    /** Size and free space at [path], or null if it can't be measured or has no size. */
    private fun measure(path: String): Pair<Float, Float>? = orNull {
        val stat = StatFs(path)
        val total = SdCardPaths.gigabytes(stat.totalBytes)
        if (total > 0f) Pair(total, SdCardPaths.gigabytes(stat.availableBytes)) else null
    }

    private inline fun <T> orNull(ask: () -> T?): T? = try {
        ask()
    } catch (_: Exception) {
        null
    }
}
