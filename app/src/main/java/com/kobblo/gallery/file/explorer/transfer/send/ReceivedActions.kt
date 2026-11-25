package com.kobblo.gallery.file.explorer.transfer.send

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.tools.apps.BundleInstallResult
import com.kobblo.gallery.file.explorer.tools.apps.BundleInstaller
import java.io.File
import java.util.Locale

/** How handing an app to the system went. */
sealed interface InstallOutcome {
    /** A bundle goes through a session, which says when it's done. */
    data object Installed : InstallOutcome
    /** A single APK goes to the system's installer, which doesn't report back. */
    data object HandedOver : InstallOutcome
    data class Failed(val message: String) : InstallOutcome
}

/** What can be done with something that just arrived, each in one call. */
object ReceivedActions {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val bundles = setOf("apks", "xapk")

    fun canInstall(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT).let { it == "apk" || it in bundles }

    /** Hands an app to the system's installer, which always asks. */
    suspend fun install(context: Context, file: File): InstallOutcome {
        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension in bundles) {
            return when (val result = runCatching { BundleInstaller.install(context, file) }.getOrNull()) {
                BundleInstallResult.Installed -> InstallOutcome.Installed
                // Backing out of the system's question is an answer, not a failure. What the system says
                // otherwise is written for developers, so it isn't passed on.
                is BundleInstallResult.Failed ->
                    if (result.message.contains("ABORTED", ignoreCase = true)) InstallOutcome.HandedOver
                    else InstallOutcome.Failed(context.getString(com.kobblo.gallery.file.explorer.R.string.transfer_install_failed))
                null -> InstallOutcome.Failed(context.getString(com.kobblo.gallery.file.explorer.R.string.transfer_install_failed))
            }
        }
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            InstallOutcome.HandedOver
        } catch (e: Exception) {
            InstallOutcome.Failed(context.getString(com.kobblo.gallery.file.explorer.R.string.transfer_install_failed))
        }
    }

    fun share(context: Context, files: List<FileItem>) {
        val uris = files.mapNotNull { file ->
            runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(file.path)) }.getOrNull()
        }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = "*/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(com.kobblo.gallery.file.explorer.R.string.transfer_note_title), text))
    }

    /** Only ever from a tap. Only web addresses reach here, because that's all that's ever offered. */
    fun openLink(context: Context, link: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(link))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
