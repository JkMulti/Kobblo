package com.kobblo.gallery.file.explorer.session

import android.content.SharedPreferences
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.DarkSurfaceStyle
import com.kobblo.gallery.file.explorer.HapticsSettings
import com.kobblo.gallery.file.explorer.IconShapeType
import com.kobblo.gallery.file.explorer.ThemeMode
import com.kobblo.gallery.file.explorer.TintPref
import com.kobblo.gallery.file.explorer.ui.design.KobbloAccent
import kotlinx.collections.immutable.toImmutableSet
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** What a setting falls back on when that depends on the phone rather than on the app. */
internal class SettingsDefaults(
    /** The phone's Downloads folder, which the folders below are in until someone chooses others. */
    val downloads: String,
    val deviceName: () -> String
)

/**
 * What the main screen remembers between runs, in the preferences named [FILE_NAME]. Earlier builds wrote
 * these too, and other parts of the app read some of them for themselves, so each key, its type and its
 * default are fixed. Other settings share the file and are none of this one's business.
 */
internal class SettingsStore(private val prefs: SharedPreferences, private val defaults: SettingsDefaults) {

    // How the app looks. Three of these are saved under a second, older name as well.

    var themeMode: ThemeMode
        get() {
            val key = if (prefs.contains(THEME)) THEME else OLD_THEME
            return named(prefs.getString(key, null), ThemeMode.SYSTEM)
        }
        set(mode) = edit { putString(THEME, mode.name).putString(OLD_THEME, mode.name) }

    var darkSurfaceStyle: DarkSurfaceStyle
        get() = named(prefs.getString(DARK_SURFACE, null), DarkSurfaceStyle.STANDARD)
        set(style) = edit { putString(DARK_SURFACE, style.name) }

    var tint: KobbloAccent
        get() = KobbloAccent.fromName(prefs.getString(TintPref, null))
        set(accent) = edit { putString(TintPref, accent.name) }

    /** Picking a hue by hand also turns the phone's own colors off. */
    var themeHue: Float
        get() {
            val saved = prefs.getFloat(if (prefs.contains(HUE)) HUE else OLD_HUE, DEFAULT_HUE)
            // Saved without a choice having been made, this one isn't anybody's.
            return if (saved == UNCHOSEN_HUE && !prefs.contains(HUE_CHOSEN)) DEFAULT_HUE else saved
        }
        set(hue) = edit {
            putFloat(HUE, hue).putFloat(OLD_HUE, hue).putBoolean(HUE_CHOSEN, true)
            putBoolean(DYNAMIC, false).putBoolean(OLD_DYNAMIC, false)
        }

    /** Only counts once it has been chosen. What's saved before that isn't anybody's choice either. */
    var dynamicTheme: Boolean
        get() = prefs.contains(DYNAMIC_CHOSEN) && prefs.getBoolean(DYNAMIC, false)
        set(dynamic) = edit {
            putBoolean(DYNAMIC, dynamic).putBoolean(OLD_DYNAMIC, dynamic).putBoolean(DYNAMIC_CHOSEN, true)
        }

    var iconShape: IconShapeType
        get() = IconShapeType.fromString(prefs.getString("icon_shape", null))
        set(shape) = edit { putString("icon_shape", shape.name) }

    var textDecorations: Set<String>
        get() = prefs.getStringSet("text_decorations", null).orEmpty().toImmutableSet()
        set(decorations) = edit { putStringSet("text_decorations", decorations) }

    var themeLightness by number("theme_lightness", 0f)
    var fontStyle by text("font_style", "Outfit")
    var mainTextScale by number("main_text_scale", 1f)
    var subTextScale by number("sub_text_scale", 1f)
    var invertText by flag("invert_text", false)
    var cornerRoundness by number("corner_roundness", 0.5f)
    var gridColumns by count("grid_columns", 3)
    var language by text("app_language", "device_default")

    // Folders and files.

    /** The one choice between a list and a grid, for everywhere that doesn't remember its own. */
    var listMode by flag("is_list_mode", false)
    var showHiddenFiles by flag("show_hidden_files", false)
    var hasSeenHideWarning by flag("has_seen_hide_warning", false)
    var vaultAlwaysShown by flag("vault_always_shown", true)

    // Recently Deleted.

    val binEnabled by flag(BIN, true)
    val binRetentionValue by count(BIN_VALUE, 7)
    val binRetentionUnit by text(BIN_UNIT, "Days")

    fun setBin(enabled: Boolean, retentionValue: Int, retentionUnit: String) = edit {
        putBoolean(BIN, enabled).putInt(BIN_VALUE, retentionValue).putString(BIN_UNIT, retentionUnit)
    }

    /** Where Organize Downloads files each kind, by the name the kind goes by in Settings. */
    enum class OrganizerFolder(val label: String, val key: String, val defaultName: String) {
        Docs("Docs", "org_dest_docs", "Docs"),
        Images("Images", "org_dest_images", "Images"),
        Apks("Apks", "org_dest_apks", "Apk"),
        Music("Music", "org_dest_music", "Music"),
        Videos("Videos", "org_dest_videos", "Videos")
    }

    fun organizerFolder(kind: OrganizerFolder): String =
        prefs.getString(kind.key, null) ?: "${defaults.downloads}/${kind.defaultName}"

    fun setOrganizerFolder(kind: OrganizerFolder, path: String) = edit { putString(kind.key, path) }

    /** What each kind of file opens in, by the name the kind goes by in Settings. */
    enum class Viewer(val label: String, val key: String) {
        TextAndPdf("Text/PDF", "viewer_text_pdf"),
        Music("Music", "viewer_music"),
        Image("Image", "viewer_image")
    }

    fun viewer(kind: Viewer): String = prefs.getString(kind.key, null) ?: "In-app"

    fun setViewer(kind: Viewer, choice: String) = edit { putString(kind.key, choice) }

    // Nearby Share.

    var deviceName: String
        get() = prefs.getString("device_name", null) ?: defaults.deviceName()
        set(name) = edit { putString("device_name", name) }

    var receivePath: String
        get() = prefs.getString("nearby_receive_path", null) ?: "${defaults.downloads}/KobbloReceived"
        set(path) = edit { putString("nearby_receive_path", path) }

    var askBeforeReceiving by flag("nearby_ask_before_receiving", true)

    /** Set once a service that an older build had left listening on the port it used has been stopped. */
    var leftOldNearbyPort by flag("nearby_migrated_to_port_53318", false)

    // Haptics. Every switch starts on.

    var hapticsEnabled by flag(HAPTICS, true)

    val haptics: HapticsSettings
        get() = HapticsSettings(
            masterEnabled = hapticsEnabled,
            fileOpen = haptic("file_open"),
            copyPaste = haptic("copy_paste"),
            delete = haptic("delete"),
            cleaner = haptic("cleaner"),
            settings = haptic("settings"),
            fab = haptic("fab")
        )

    private fun haptic(moment: String): Boolean = prefs.getBoolean("haptics_$moment", true)

    /** Settings names a moment's switch by the key it's saved under. */
    fun setHaptic(key: String, enabled: Boolean) = edit { putBoolean(key, enabled) }

    // The server of each kind that was connected, so it still is the next time.

    fun activeServer(kind: ServerKind): String? = prefs.getString(activeServerKey(kind), null)

    fun setActiveServer(kind: ServerKind, connectionId: String?) = edit {
        val key = activeServerKey(kind)
        if (connectionId == null) remove(key) else putString(key, connectionId)
    }

    private fun activeServerKey(kind: ServerKind) = "active_${kind.savedAs}_connection"

    /**
     * [state] with everything saved put back into it.
     *
     * @param autoIconShape the shape that choosing by the phone comes to now.
     */
    fun restore(state: AppState, autoIconShape: () -> IconShapeType): AppState {
        val shape = iconShape
        return state.copy(
            themeMode = themeMode,
            darkSurfaceStyle = darkSurfaceStyle,
            tint = tint,
            themeHue = themeHue,
            themeLightnessOffset = themeLightness,
            useDynamicSystemTheme = dynamicTheme,
            iconShapeSetting = shape,
            activeIconShape = if (shape == IconShapeType.AUTO) autoIconShape() else shape,
            fontStyle = fontStyle,
            textDecorations = textDecorations.toImmutableSet(),
            mainTextScale = mainTextScale,
            subTextScale = subTextScale,
            invertText = invertText,
            cornerRoundness = cornerRoundness,
            gridColumns = gridColumns,
            currentLanguage = language,
            isListMode = listMode,
            showHiddenFiles = showHiddenFiles,
            hasSeenHideWarning = hasSeenHideWarning,
            isVaultAlwaysShown = vaultAlwaysShown,
            isRecycleBinEnabled = binEnabled,
            recycleBinRetentionValue = binRetentionValue,
            recycleBinRetentionUnit = binRetentionUnit,
            orgDestDocs = organizerFolder(OrganizerFolder.Docs),
            orgDestImages = organizerFolder(OrganizerFolder.Images),
            orgDestApks = organizerFolder(OrganizerFolder.Apks),
            orgDestMusic = organizerFolder(OrganizerFolder.Music),
            orgDestVideos = organizerFolder(OrganizerFolder.Videos),
            viewerTextPdf = viewer(Viewer.TextAndPdf),
            viewerMusic = viewer(Viewer.Music),
            viewerImage = viewer(Viewer.Image),
            nearbyDeviceName = deviceName,
            nearbyReceivePath = receivePath,
            nearbyAskBeforeReceiving = askBeforeReceiving,
            haptics = haptics
        )
    }

    private fun edit(change: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(change).apply()
    }

    private inline fun <reified E : Enum<E>> named(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private fun flag(key: String, default: Boolean) =
        Saved({ getBoolean(key, default) }, { putBoolean(key, it) })

    private fun count(key: String, default: Int) =
        Saved({ getInt(key, default) }, { putInt(key, it) })

    private fun number(key: String, default: Float) =
        Saved({ getFloat(key, default) }, { putFloat(key, it) })

    private fun text(key: String, default: String) =
        Saved({ getString(key, null) ?: default }, { putString(key, it) })

    /** One value under one key, read afresh each time, since other parts of the app write here as well. */
    private inner class Saved<T>(
        private val read: SharedPreferences.() -> T,
        private val write: SharedPreferences.Editor.(T) -> Unit
    ) : ReadWriteProperty<SettingsStore, T> {
        override fun getValue(thisRef: SettingsStore, property: KProperty<*>): T = prefs.read()
        override fun setValue(thisRef: SettingsStore, property: KProperty<*>, value: T) = edit { write(value) }
    }

    companion object {
        const val FILE_NAME = "kobblo_prefs"

        private const val THEME = "theme_mode"
        private const val OLD_THEME = "themeMode"
        private const val DARK_SURFACE = "dark_surface_style"
        private const val HUE = "theme_hue"
        private const val OLD_HUE = "themeHue"
        private const val HUE_CHOSEN = "theme_hue_explicit"
        private const val DYNAMIC = "theme_dynamic"
        private const val OLD_DYNAMIC = "useDynamicSystemTheme"
        private const val DYNAMIC_CHOSEN = "theme_dynamic_explicit"
        private const val BIN = "recycle_bin_enabled"
        private const val BIN_VALUE = "recycle_bin_retention_value"
        private const val BIN_UNIT = "recycle_bin_retention_unit"
        private const val HAPTICS = "haptics_master"

        private const val DEFAULT_HUE = 14f
        private const val UNCHOSEN_HUE = 262f
    }
}
