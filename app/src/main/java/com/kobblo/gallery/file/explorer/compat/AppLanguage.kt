package com.kobblo.gallery.file.explorer.compat

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * The app's chosen language. From Android 13 the system keeps it and applies it to the whole process, and it's the
 * same choice as the one in Android's own settings. Before that nothing does, so each context has to be told, or only
 * the main screen would change while messages and notifications stayed in the phone's language.
 *
 * AppCompatDelegate.setApplicationLocales looks like the way to do this, but it finds the system service through an
 * AppCompatActivity, and this app has none, so it quietly did nothing on every version.
 */
internal object AppLanguage {
    private const val PREFS = "kobblo_prefs"
    private const val KEY = "app_language"
    const val DEVICE_DEFAULT = "device_default"

    private fun saved(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEVICE_DEFAULT).orEmpty().ifBlank { DEVICE_DEFAULT }

    /** What to build an app component's context on. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = saved(base)
        if (tag == DEVICE_DEFAULT) return base
        return try {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocales(LocaleList(locale))
            base.createConfigurationContext(config)
        } catch (e: Exception) {
            base
        }
    }

    /** Someone chose a language in the app. */
    fun set(application: Context, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setInSystem(application, tag)
        } else {
            updateApplicationContext(application, tag)
        }
    }

    /**
     * Android's settings can change this while the app isn't looking, and they win. A choice from before the
     * system was told about it goes the other way, once. Call at the process's start, before anything reads it.
     */
    fun reconcile(application: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val system = application.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return
            val saved = saved(application)
            if (!system.isEmpty) {
                // Compared as locales, since the system can hand "in" back as "id".
                val tag = system[0].toLanguageTag()
                if (tag != Locale.forLanguageTag(saved).toLanguageTag()) {
                    application.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
                }
            } else if (saved != DEVICE_DEFAULT) {
                setInSystem(application, saved)
            }
        } catch (e: Exception) {
            android.util.Log.w("AppLanguage", "Couldn't compare the language with the system's")
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setInSystem(context: Context, tag: String) {
        context.getSystemService(LocaleManager::class.java)?.applicationLocales =
            if (tag.isBlank() || tag == DEVICE_DEFAULT) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    @Suppress("DEPRECATION")
    private fun updateApplicationContext(application: Context, tag: String) {
        val resources = application.resources
        val config = Configuration(resources.configuration)
        if (tag.isBlank() || tag == DEVICE_DEFAULT) {
            config.setLocales(Resources.getSystem().configuration.locales)
        } else {
            config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        }
        // Text made without a context goes by this, such as the language names under each language in Settings.
        // [wrap] sets it for a chosen language, but going back to the phone's own would otherwise keep the last.
        Locale.setDefault(config.locales[0])
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}
