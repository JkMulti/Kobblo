package com.kobblo.gallery.file.explorer.ui.root

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.compat.versionCodeLong
import com.kobblo.gallery.file.explorer.ui.ApkVersionInfo
import com.kobblo.gallery.file.explorer.ui.BatchApkItem
import com.kobblo.gallery.file.explorer.ui.InstallMode
import com.kobblo.gallery.file.explorer.ui.ParsedApkItem

/** A version, as its package gives it. Not every package names one. */
internal data class ApkVersion(val name: String?, val code: Long) {
    /** The version in words, with [unknown] for a name it doesn't have. */
    fun inWords(unknown: String): String = "${name ?: unknown} ($code)"
}

/** What was read from an APK: the app in it, and the copy of that app on the phone, if there is one. */
internal data class ApkReading(val packageName: String, val label: String?, val version: ApkVersion, val installed: ApkVersion?) {
    /** Whether installing it would be going back to an older version. */
    val isDowngrade: Boolean get() = installed != null && version.code < installed.code
}

/** Reads the APK at [file], or gives back null for one that's elsewhere or can't be read. Does its work where it's called. */
internal fun PackageManager.readApk(file: FileItem): ApkReading? {
    if (!file.path.startsWith("/")) return null
    val found = getPackageArchiveInfo(file.path, PackageManager.GET_ACTIVITIES) ?: return null
    // The label is a resource of the app's own, so it has to be told where those are.
    val app = found.applicationInfo?.apply {
        sourceDir = file.path
        publicSourceDir = file.path
    }
    return ApkReading(
        packageName = found.packageName,
        label = app?.loadLabel(this)?.toString(),
        version = found.version,
        installed = installedVersion(found.packageName)
    )
}

private val PackageInfo.version: ApkVersion get() = ApkVersion(versionName, versionCodeLong)

/** The version of an app on the phone, or null if it isn't on it. The system throws to say so. */
private fun PackageManager.installedVersion(packageName: String): ApkVersion? = try {
    getPackageInfo(packageName, 0).version
} catch (e: Exception) {
    null
}

/** The line under one app's name: the change of version it would be, or its version, or that it has none. */
internal fun versionLine(reading: ApkReading?, unknownName: String, unknownVersion: String): String {
    if (reading == null) return unknownVersion
    val version = reading.version.inWords(unknownName)
    val installed = reading.installed ?: return version
    return "${installed.inWords(unknownName)} → $version"
}

/** Whether there's a file to install and one to compare it with, as the ways of installing are worked out from. */
internal fun candidate(reading: ApkReading?, unknownName: String): ApkVersionInfo =
    if (reading == null) ApkVersionInfo("", 0, unknownName)
    else ApkVersionInfo(reading.packageName, reading.version.code, reading.version.name ?: unknownName)

internal fun installedCopy(reading: ApkReading?, unknownName: String): ApkVersionInfo? =
    reading?.installed?.let { ApkVersionInfo(reading.packageName, it.code, it.name ?: unknownName) }

/** One of several APKs to install together. One that couldn't be read goes by its file's name. */
internal fun batchEntry(file: FileItem, reading: ApkReading?, unknownName: String): ParsedApkItem = ParsedApkItem(
    file = file,
    appName = reading?.label ?: file.name,
    versionName = reading?.version?.name ?: unknownName,
    versionCode = reading?.version?.code ?: 0L,
    isDowngrade = reading?.isDowngrade ?: false,
    installedVersionName = reading?.installed?.name,
    installedVersionCode = reading?.installed?.code
)

/** The ones of a batch that [mode] can install. Only a downgrade takes the older ones, and it takes no others. */
internal fun List<ParsedApkItem>.installableBy(mode: InstallMode): List<ParsedApkItem> =
    filter { it.isDowngrade == (mode == InstallMode.DOWNGRADE) }

/** A batch entry as the sheet that confirms it lists it. */
internal fun ParsedApkItem.asRow(): BatchApkItem {
    val version = ApkVersion(versionName, versionCode).inWords(versionName)
    val from = installedVersionName?.let { "$it ($installedVersionCode) → " }.orEmpty()
    return BatchApkItem(name = appName, initial = appName.take(1).uppercase(), versionTransition = from + version)
}
