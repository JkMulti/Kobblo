package com.kobblo.gallery.file.explorer.session

import android.app.Application
import android.content.pm.PackageManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.compat.StorageAccess
import com.kobblo.gallery.file.explorer.data.StorageUtils
import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Date
import java.util.Locale

/** The phone's own storage: how much room there is, where the card is, and what the app is allowed to reach. */
internal class PhoneStorage(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val repository: FileRepository
) {
    /** Measures the free space. A card that has been seen once keeps its place if it can't be found again. */
    fun measure() {
        try {
            val phone = StatFs(Environment.getExternalStorageDirectory().path)
            val card = StorageUtils.getSdCardPath(app)
            val (cardTotal, cardFree) = StorageUtils.getSdCardStats(app, card)
            live.update {
                it.copy(
                    storageTotalGb = phone.totalBytes.inGigabytes(),
                    storageFreeGb = phone.availableBytes.inGigabytes(),
                    sdCardStorageTotalGb = cardTotal,
                    sdCardStorageFreeGb = cardFree,
                    sdCardPath = card ?: it.sdCardPath
                )
            }
        } catch (e: Exception) {
            Log.w(Tag, "Couldn't measure the storage", e)
        }
    }

    /** Decimal, like every file's label and the system's own storage screen. */
    private fun Long.inGigabytes() = this / 1_000_000_000f

    fun checkAccess() = setAccess(StorageAccess.has(app))

    fun setAccess(granted: Boolean) = live.update { it.copy(hasStoragePermission = granted) }

    /** Asks again whether Shizuku is there, rather than going by the last answer. */
    fun recheckShizuku() {
        scope.launch(Dispatchers.IO) {
            repository.invalidateShizukuCache()
            showShizuku()
        }
    }

    fun showShizuku() {
        val available = repository.hasShizuku()
        live.update { it.copy(hasShizuku = available) }
    }

    /** Asks for Shizuku's permission if it's running and hasn't given it. */
    fun askShizuku() {
        try {
            val running = Shizuku.pingBinder()
            if (running && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(0)
        } catch (_: Exception) {
            // It isn't installed, or has just stopped, and the folder says so on its own.
        }
    }

    private companion object {
        const val Tag = "PhoneStorage"
    }
}

/** A row for a file that isn't from any list, such as one that has just arrived. */
internal fun localFileItem(file: File): FileItem = FileItem(
    id = file.absolutePath.hashCode() and Int.MAX_VALUE,
    path = file.absolutePath,
    name = file.name,
    type = FileKinds.typeOf(file),
    kind = if (file.isDirectory) "folder" else "file",
    size = FileSizes.label(file.length()),
    changed = DisplayDates.date(Locale.getDefault()).format(Date(file.lastModified())),
    owner = "Me",
    sizeBytes = file.length(),
    lastModified = file.lastModified()
)
