package com.kobblo.gallery.file.explorer.tools.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.kobblo.gallery.file.explorer.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** A mounted storage volume: the phone's own, an SD card, or a USB drive. */
data class VolumeInfo(
    /** "primary", or the volume's UUID. Stays the same across mounts. */
    val id: String,
    val name: String,
    val path: String,
    val isPrimary: Boolean,
    val isUsb: Boolean,
    val isReadOnly: Boolean,
    val totalBytes: Long,
    val freeBytes: Long
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val totalGb: Float get() = totalBytes.toFloat() / GIB
    val freeGb: Float get() = freeBytes.toFloat() / GIB

    private companion object {
        const val GIB = 1024f * 1024f * 1024f
    }
}

/**
 * Every mounted volume, kept current as cards and drives come and go. The list is process-wide so
 * Browse, the picker and the document provider all agree on it.
 */
object StorageVolumes {
    private val _volumes = MutableStateFlow<List<VolumeInfo>>(emptyList())
    val volumes: StateFlow<List<VolumeInfo>> = _volumes.asStateFlow()

    private var watching = false

    /** The latest list, for code outside composition. Empty until [start] has run. */
    val snapshot: List<VolumeInfo> get() = _volumes.value

    /** Reads the volumes now and keeps them updated from here on. Safe to call repeatedly. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        refresh(app)
        if (watching) return
        watching = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) = refresh(ctx)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        androidx.core.content.ContextCompat.registerReceiver(app, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Newer versions also report state changes here, including ones the broadcasts miss.
            val manager = app.getSystemService(StorageManager::class.java)
            manager?.registerStorageVolumeCallback(androidx.core.content.ContextCompat.getMainExecutor(app), object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) = refresh(app)
            })
        }
    }

    fun refresh(context: Context) {
        _volumes.value = read(context.applicationContext)
    }

    /** The volume holding [path], if any. */
    fun forPath(path: String): VolumeInfo? =
        snapshot.firstOrNull { path == it.path || path.startsWith(it.path + "/") }

    /** The root of the removable volume holding [path], or null when it's on the phone's storage. */
    fun removableRootOf(path: String): String? = forPath(path)?.takeIf { !it.isPrimary }?.path

    private fun read(context: Context): List<VolumeInfo> {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val volumes = try {
            manager?.storageVolumes.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val found = volumes.mapNotNull { toInfo(context, it) }
        if (found.any { it.isPrimary }) return found.sortedByDescending { it.isPrimary }
        // Some devices report nothing useful: the phone's own storage must always be listed.
        val primary = Environment.getExternalStorageDirectory()
        return listOf(info("primary", context.getString(R.string.tools_internal_storage), primary.absolutePath, isPrimary = true, isUsb = false, readOnly = false)) + found
    }

    private fun toInfo(context: Context, volume: StorageVolume): VolumeInfo? {
        val state = volume.state
        if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return null
        val path = volumePath(volume) ?: return null
        val description = try { volume.getDescription(context) } catch (_: Exception) { null }
        val isPrimary = volume.isPrimary
        val id = if (isPrimary) "primary" else volume.uuid ?: File(path).name
        return info(
            id = id,
            name = if (isPrimary) context.getString(R.string.tools_internal_storage) else description?.takeIf { it.isNotBlank() } ?: context.getString(R.string.tools_sd_card),
            path = path,
            isPrimary = isPrimary,
            isUsb = !isPrimary && description.orEmpty().contains("usb", ignoreCase = true),
            readOnly = state == Environment.MEDIA_MOUNTED_READ_ONLY
        )
    }

    private fun volumePath(volume: StorageVolume): String? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory?.absolutePath
        } else {
            try {
                volume.javaClass.getMethod("getPath").invoke(volume) as? String
            } catch (_: Exception) {
                null
            } ?: volume.uuid?.let { "/storage/$it" }
        }
        return raw?.replace("/mnt/media_rw/", "/storage/")
    }

    private fun info(id: String, name: String, path: String, isPrimary: Boolean, isUsb: Boolean, readOnly: Boolean): VolumeInfo {
        val (total, free) = try {
            val stat = StatFs(path)
            stat.totalBytes to stat.availableBytes
        } catch (_: Exception) {
            0L to 0L
        }
        return VolumeInfo(id, name, path, isPrimary, isUsb, readOnly, total, free)
    }
}
