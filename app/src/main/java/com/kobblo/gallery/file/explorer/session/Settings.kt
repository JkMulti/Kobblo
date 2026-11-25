package com.kobblo.gallery.file.explorer.session

import android.app.Application
import android.util.Log
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.DarkSurfaceStyle
import com.kobblo.gallery.file.explorer.IconShapeType
import com.kobblo.gallery.file.explorer.ThemeMode
import com.kobblo.gallery.file.explorer.compat.AppLanguage
import com.kobblo.gallery.file.explorer.session.SettingsStore.OrganizerFolder
import com.kobblo.gallery.file.explorer.session.SettingsStore.Viewer
import com.kobblo.gallery.file.explorer.ui.design.KobbloAccent
import com.kobblo.gallery.file.explorer.ui.expressive.AutoIconShapeSession
import kotlinx.collections.immutable.toImmutableSet
import kotlin.reflect.KMutableProperty0

/** Choices made in Settings: each is saved and shown at once. */
internal class Settings(
    private val app: Application,
    private val live: LiveState,
    private val store: SettingsStore
) {
    fun chooseThemeMode(mode: ThemeMode) = choose(store::themeMode, mode) { copy(themeMode = it) }

    fun chooseDarkSurface(style: DarkSurfaceStyle) = choose(store::darkSurfaceStyle, style) { copy(darkSurfaceStyle = it) }

    fun chooseTint(accent: KobbloAccent) = choose(store::tint, accent) { copy(tint = it) }

    fun chooseHue(hue: Float) = choose(store::themeHue, hue) { copy(themeHue = it, useDynamicSystemTheme = false) }

    fun chooseLightness(offset: Float) = choose(store::themeLightness, offset) { copy(themeLightnessOffset = it) }

    fun useDynamicTheme(dynamic: Boolean) = choose(store::dynamicTheme, dynamic) { copy(useDynamicSystemTheme = it) }

    fun chooseFont(style: String) = choose(store::fontStyle, style) { copy(fontStyle = it) }

    fun scaleMainText(scale: Float) = choose(store::mainTextScale, scale) { copy(mainTextScale = it) }

    fun scaleSubText(scale: Float) = choose(store::subTextScale, scale) { copy(subTextScale = it) }

    fun invertText(invert: Boolean) = choose(store::invertText, invert) { copy(invertText = it) }

    fun chooseColumns(columns: Int) = choose(store::gridColumns, columns) { copy(gridColumns = it) }

    fun chooseRoundness(roundness: Float) = choose(store::cornerRoundness, roundness) { copy(cornerRoundness = it) }

    fun toggleDecoration(decoration: String) {
        val worn = live.now.textDecorations
        val next = if (decoration in worn) worn - decoration else worn + decoration
        choose(store::textDecorations, next) { copy(textDecorations = it.toImmutableSet()) }
    }

    fun chooseIconShape(shape: IconShapeType) {
        val active = if (shape == IconShapeType.AUTO) autoIconShape() else shape
        choose(store::iconShape, shape) { copy(iconShapeSetting = it, activeIconShape = active) }
    }

    /** A shape that follows the phone is worked out again each time the app is opened. */
    fun refreshIconShape() {
        if (live.now.iconShapeSetting != IconShapeType.AUTO) return
        val active = autoIconShape()
        live.update { it.copy(activeIconShape = active) }
    }

    fun autoIconShape(): IconShapeType = AutoIconShapeSession.refresh(app)

    fun chooseLanguage(tag: String) {
        choose(store::language, tag) { copy(currentLanguage = it) }
        try {
            AppLanguage.set(app, tag)
        } catch (e: Exception) {
            // It's saved, so at worst the app changes language the next time it starts.
            Log.e(Tag, "Couldn't change the language", e)
        }
    }

    fun hideWarningSeen(seen: Boolean) = choose(store::hasSeenHideWarning, seen) { copy(hasSeenHideWarning = it) }

    fun alwaysShowVault(always: Boolean) = choose(store::vaultAlwaysShown, always) { copy(isVaultAlwaysShown = it) }

    fun enableHaptics(enabled: Boolean) = choose(store::hapticsEnabled, enabled) { copy(haptics = haptics.copy(masterEnabled = it)) }

    fun setHaptic(key: String, enabled: Boolean) {
        store.setHaptic(key, enabled)
        live.update { it.copy(haptics = store.haptics) }
    }

    fun setBin(enabled: Boolean, retentionValue: Int, retentionUnit: String) {
        store.setBin(enabled, retentionValue, retentionUnit)
        live.update {
            it.copy(
                isRecycleBinEnabled = enabled,
                recycleBinRetentionValue = retentionValue,
                recycleBinRetentionUnit = retentionUnit
            )
        }
    }

    fun setOrganizerFolder(label: String, path: String) {
        val kind = OrganizerFolder.entries.firstOrNull { it.label == label } ?: return
        store.setOrganizerFolder(kind, path)
        live.update {
            when (kind) {
                OrganizerFolder.Docs -> it.copy(orgDestDocs = path)
                OrganizerFolder.Images -> it.copy(orgDestImages = path)
                OrganizerFolder.Apks -> it.copy(orgDestApks = path)
                OrganizerFolder.Music -> it.copy(orgDestMusic = path)
                OrganizerFolder.Videos -> it.copy(orgDestVideos = path)
            }
        }
    }

    fun setViewer(label: String, choice: String) {
        val kind = Viewer.entries.firstOrNull { it.label == label } ?: return
        store.setViewer(kind, choice)
        live.update {
            when (kind) {
                Viewer.TextAndPdf -> it.copy(viewerTextPdf = choice)
                Viewer.Music -> it.copy(viewerMusic = choice)
                Viewer.Image -> it.copy(viewerImage = choice)
            }
        }
    }

    private fun <T> choose(saved: KMutableProperty0<T>, value: T, show: AppState.(T) -> AppState) {
        saved.set(value)
        live.update { it.show(value) }
    }

    private companion object {
        const val Tag = "Settings"
    }
}
