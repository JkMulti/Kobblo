package com.kobblo.gallery.file.explorer.transfer.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.kobblo.gallery.file.explorer.R
import java.util.Locale

/**
 * What other devices call this phone. The name its owner sees in the phone's own settings comes first,
 * because "Galaxy A56" is much easier to pick out of a list than "samsung SM-A566E".
 */
object DeviceName {
    /** The name in use: the one chosen in Kobblo, or else the phone's own. */
    fun current(context: Context): String {
        val chosen = context.getSharedPreferences("kobblo_prefs", Context.MODE_PRIVATE).getString("device_name", null)
        return chosen?.takeIf { it.isNotBlank() } ?: default(context)
    }

    fun default(context: Context): String {
        // A manufacturer's build can refuse this read as well as leave it empty.
        val own = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        return choose(own, Build.MANUFACTURER, Build.MODEL) ?: context.getString(R.string.share_device_fallback)
    }

    /** The name to go by, or null when there's nothing at all to go by. */
    internal fun choose(ownName: String?, manufacturer: String?, model: String?): String? {
        ownName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val maker = manufacturer?.trim().orEmpty().takeUnless { it.equals("unknown", ignoreCase = true) }.orEmpty()
            .replaceFirstChar { it.titlecase(Locale.ROOT) }
        val product = model?.trim().orEmpty()
        return when {
            product.isEmpty() -> maker.ifEmpty { null }
            maker.isEmpty() || product.startsWith(maker, ignoreCase = true) -> product
            else -> "$maker $product"
        }
    }
}
