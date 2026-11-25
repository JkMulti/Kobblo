package com.kobblo.gallery.file.explorer.ui

import androidx.annotation.StringRes
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R

/*
 * What can be done with an APK, worked out apart from the popups that show it.
 *
 * There are three ways to install. The ordinary one hands the file to the system's installer. A silent
 * install skips the system's dialogs, and a downgrade puts an older version over a newer one; Android
 * allows an ordinary app neither, so both go through Shizuku and are held back without it.
 */

/** Which app a package is and at what version: the one in an APK file, or the one already installed. */
data class ApkVersionInfo(val packageName: String, val versionCode: Long, val versionName: String)

/** The three ways to install. */
enum class InstallMode { NORMAL, DOWNGRADE, SILENT }

/** What holds a way of installing back, and the line that says so. */
enum class InstallBlock(@StringRes val message: Int) {
    /** There is no installed copy to go back from. */
    NotInstalled(R.string.view_apk_not_installed),

    /** The file is the installed version or a newer one, so installing it wouldn't be going back. */
    NotOlder(R.string.view_apk_not_older),

    NoShizuku(R.string.view_apk_shizuku_not_connected)
}

/** One way to install, for one file: whether it's offered at all, and what holds it back if anything does. */
data class InstallChoice(val offered: Boolean, val block: InstallBlock?) {
    val usable: Boolean get() = offered && block == null
}

/** How each way to install stands for one APK. Comparable by value, so a popup given the same answer again stays as it is. */
data class InstallModesResult(val normal: InstallChoice, val downgrade: InstallChoice, val silent: InstallChoice) {
    /**
     * The one thing worth saying about what can't be chosen. Shizuku comes first, since connecting it
     * is something the user can do; failing that, why the downgrade they can see is out of reach.
     */
    val notice: InstallBlock?
        get() = when {
            silent.block == InstallBlock.NoShizuku -> InstallBlock.NoShizuku
            downgrade.offered -> downgrade.block
            else -> null
        }
}

/**
 * Works out which ways [selectedApk] can be installed. [installedApp] is the copy of the same app
 * already on the phone, if there is one. A downgrade is only offered when there is, and only possible
 * when the file is the older of the two.
 */
fun evaluateInstallModes(
    selectedApk: ApkVersionInfo,
    installedApp: ApkVersionInfo?,
    shizukuConnected: Boolean
): InstallModesResult {
    val withoutShizuku = InstallBlock.NoShizuku.takeUnless { shizukuConnected }
    val downgrade = when {
        installedApp == null -> InstallChoice(offered = false, block = InstallBlock.NotInstalled)
        selectedApk.versionCode >= installedApp.versionCode -> InstallChoice(offered = true, block = InstallBlock.NotOlder)
        else -> InstallChoice(offered = true, block = withoutShizuku)
    }
    return InstallModesResult(
        normal = InstallChoice(offered = true, block = null),
        downgrade = downgrade,
        silent = InstallChoice(offered = true, block = withoutShizuku)
    )
}

/** The name a way of installing goes by in the list of choices. */
@get:StringRes
internal val InstallMode.title: Int
    get() = when (this) {
        InstallMode.NORMAL -> R.string.view_apk_normal_install
        InstallMode.DOWNGRADE -> R.string.view_apk_downgrade
        InstallMode.SILENT -> R.string.view_apk_silent_install
    }

/** What a way of installing does, in a few words under its name. */
@get:StringRes
internal val InstallMode.explanation: Int
    get() = when (this) {
        InstallMode.NORMAL -> R.string.view_apk_normal_install_subtitle
        InstallMode.DOWNGRADE -> R.string.view_apk_downgrade_subtitle
        InstallMode.SILENT -> R.string.view_apk_silent_install_subtitle
    }

/** The heading of the sheet that confirms a batch. A downgrade is spelled out there, where it stands alone. */
@get:StringRes
internal val InstallMode.sheetTitle: Int
    get() = if (this == InstallMode.DOWNGRADE) R.string.view_apk_downgrade_install else title

/** One of several selected APKs, with what was read from it and from the copy already installed. */
data class ParsedApkItem(
    val file: FileItem,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    /** The file is older than the installed app. */
    val isDowngrade: Boolean,
    val installedVersionName: String?,
    val installedVersionCode: Long?
)

/** A row of the sheet that confirms a batch. */
data class BatchApkItem(
    val name: String,
    val initial: String,
    /** The version change the install makes, already worded. */
    val versionTransition: String,
    val note: String? = null,
    val isNoteWarning: Boolean = false,
    val overrideMode: String? = null
)

/** The line under an app's name: its version change, and its note when it has one. */
internal val BatchApkItem.summary: String
    get() = if (note != null) "$versionTransition · $note" else versionTransition
