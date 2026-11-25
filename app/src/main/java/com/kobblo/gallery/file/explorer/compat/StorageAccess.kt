package com.kobblo.gallery.file.explorer.compat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat

/** The installed version's number. PackageInfo only has this itself from Android 9. */
internal val PackageInfo.versionCodeLong: Long get() = PackageInfoCompat.getLongVersionCode(this)

/** Whether the app can see storage, which is a different permission from Android 11 on. */
internal object StorageAccess {
    val RuntimePermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    private const val PREFS = "kobblo_prefs"
    private const val ASKED_KEY = "storage_dialog_asked"

    fun has(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** Android has no way to tell "never asked" from "don't ask again", so that part has to be remembered here. */
    fun route(activity: Activity): VersionRules.StorageRoute = VersionRules.storageRoute(
        sdk = Build.VERSION.SDK_INT,
        askedBefore = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED_KEY, false),
        systemWouldExplain = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
    )

    fun rememberAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED_KEY, true).apply()
    }

    fun openAppSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
}
