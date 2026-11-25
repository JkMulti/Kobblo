package com.kobblo.gallery.file.explorer.files

import android.content.pm.PackageManager
import com.kobblo.gallery.file.explorer.fileops.ShellCommand
import java.io.File

/** Installs and removes apps without the system's own screens, which only the elevated shell may do. */
internal class ApkInstaller(private val packages: PackageManager, private val shell: ElevatedShell) {

    fun packageNameIn(apkPath: String): String? = packages.getPackageArchiveInfo(apkPath, 0)?.packageName

    /**
     * The shell reads the APK and pipes it to the installer. It can read shared storage where the installer
     * itself isn't let in, and no copy of the APK has to be written anywhere first.
     */
    fun install(apkPath: String, downgrade: Boolean): Pair<Boolean, String> = withShell {
        val bytes = File(apkPath).length()
        if (bytes == 0L) false to "File not found or empty"
        else shell.runForOutput(ShellCommand.installApk(apkPath, bytes, downgrade))
    }

    fun uninstall(packageName: String): Pair<Boolean, String> = withShell {
        when (val command = ShellCommand.uninstallApk(packageName)) {
            null -> false to "Not a package name"
            else -> shell.runForOutput(command)
        }
    }

    private inline fun withShell(work: () -> Pair<Boolean, String>): Pair<Boolean, String> =
        if (shell.isReady) work() else false to "No Shizuku"
}
