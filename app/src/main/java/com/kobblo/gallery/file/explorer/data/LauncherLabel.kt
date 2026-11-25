package com.kobblo.gallery.file.explorer.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Choosing the launcher's label was removed from Settings. Installs that had picked another label
 * launch through that alias, so its manifest entry has to stay until they've been moved back.
 */
object LauncherLabel {
    private const val PrefKey = "launcher_preset"

    fun resetIfChanged(context: Context) {
        val prefs = context.getSharedPreferences("kobblo_prefs", Context.MODE_PRIVATE)
        if (!prefs.contains(PrefKey)) return
        val aliases = listOf(".LauncherAliasDefault", ".LauncherAliasFiles", ".LauncherAliasFileManager")
            .map { "com.kobblo.gallery.file.explorer.alias$it" }
        val pm = context.packageManager
        try {
            aliases.forEachIndexed { index, alias ->
                val enabled = if (index == 0) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                pm.setComponentEnabledSetting(ComponentName(context, alias), enabled, PackageManager.DONT_KILL_APP)
            }
            prefs.edit().remove(PrefKey).apply()
        } catch (e: Exception) {
            android.util.Log.e("LauncherLabel", "Could not restore the launcher label", e)
        }
    }
}
