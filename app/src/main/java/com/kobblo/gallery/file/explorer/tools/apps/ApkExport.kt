package com.kobblo.gallery.file.explorer.tools.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.kobblo.gallery.file.explorer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

internal data class ExportProgress(val appLabel: String, val done: Int, val total: Int)

internal data class BackupResult(val saved: List<File>, val failed: List<String>)

/**
 * Copies an app's APKs out of the system's folder. A split app can't be installed from its base APK
 * alone, so its parts travel together as one `.apks` bundle.
 */
internal object ApkExport {

    suspend fun backUp(
        apps: List<InstalledApp>,
        destDir: File,
        onProgress: (ExportProgress) -> Unit
    ): BackupResult = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val saved = mutableListOf<File>()
        val failed = mutableListOf<String>()
        apps.forEachIndexed { index, app ->
            onProgress(ExportProgress(app.label, index, apps.size))
            val target = uniqueFile(destDir, AppQuery.backupFileName(app))
            try {
                exportTo(app, target)
                saved += target
            } catch (e: kotlinx.coroutines.CancellationException) {
                target.delete()
                throw e
            } catch (_: Exception) {
                target.delete()
                failed += app.label
            }
        }
        BackupResult(saved, failed)
    }

    /** Prepares readable copies to share. The cache holds only the latest share, so it can't grow. */
    suspend fun forSharing(
        context: Context,
        apps: List<InstalledApp>,
        onProgress: (ExportProgress) -> Unit
    ): List<Uri> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared_apks")
        dir.deleteRecursively()
        dir.mkdirs()
        apps.mapIndexed { index, app ->
            onProgress(ExportProgress(app.label, index, apps.size))
            val target = uniqueFile(dir, AppQuery.backupFileName(app))
            exportTo(app, target)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        }
    }

    private suspend fun exportTo(app: InstalledApp, target: File) {
        if (app.isSplit) {
            writeBundle(app.allApkPaths.map(::File), target)
        } else {
            File(app.apkPath).inputStream().use { input ->
                target.outputStream().use { output -> copyCancellable(input, output) }
            }
        }
    }

    /** APKs are already compressed, so the fastest level is plenty. */
    suspend fun writeBundle(parts: List<File>, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            parts.forEach { part ->
                zip.putNextEntry(ZipEntry(part.name))
                part.inputStream().use { input -> copyCancellable(input, zip) }
                zip.closeEntry()
            }
        }
    }

    private suspend fun copyCancellable(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(256 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$stem ($counter).$ext")
            counter++
        }
        return candidate
    }
}

/** What happened when the system was handed a bundle to install. */
internal sealed interface BundleInstallResult {
    data object Installed : BundleInstallResult
    data class Failed(val message: String) : BundleInstallResult
}

/**
 * Installs a `.apks` bundle through a PackageInstaller session, which is the only way to install
 * several APKs as one app. The system still asks the user to confirm.
 */
internal object BundleInstaller {
    private const val ACTION = "com.kobblo.gallery.file.explorer.tools.BUNDLE_INSTALL_STATUS"

    suspend fun install(context: Context, bundle: File): BundleInstallResult {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val sessionId = withContext(Dispatchers.IO) { writeSession(installer, bundle) }
            ?: return BundleInstallResult.Failed(app.getString(R.string.tool_bundle_no_apks))

        return suspendCancellableCoroutine { continuation ->
            val action = "$ACTION.$sessionId"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            val confirm = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            confirm?.let(ctx::startActivity)
                        }
                        else -> {
                            try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                            if (!continuation.isActive) return
                            continuation.resume(
                                if (status == PackageInstaller.STATUS_SUCCESS) BundleInstallResult.Installed
                                else BundleInstallResult.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ctx.getString(R.string.tool_bundle_refused))
                            )
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            continuation.invokeOnCancellation { try { app.unregisterReceiver(receiver) } catch (_: Exception) {} }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(app, sessionId, Intent(action).setPackage(app.packageName), flags)
            installer.openSession(sessionId).use { it.commit(pending.intentSender) }
        }
    }

    private fun writeSession(installer: PackageInstaller, bundle: File): Int? {
        val sessionId = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        var wroteAny = false
        try {
            installer.openSession(sessionId).use { session ->
                ZipFile(bundle).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                        .forEach { entry ->
                            // A flat name: session parts can't hold folders, and a bundle may nest them.
                            val name = entry.name.substringAfterLast('/')
                            session.openWrite(name, 0, entry.size).use { out ->
                                zip.getInputStream(entry).use { it.copyTo(out, 256 * 1024) }
                                session.fsync(out)
                            }
                            wroteAny = true
                        }
                }
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
        if (!wroteAny) {
            installer.abandonSession(sessionId)
            return null
        }
        return sessionId
    }
}
