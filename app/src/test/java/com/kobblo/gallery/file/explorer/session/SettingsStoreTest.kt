package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.DarkSurfaceStyle
import com.kobblo.gallery.file.explorer.HapticsSettings
import com.kobblo.gallery.file.explorer.IconShapeType
import com.kobblo.gallery.file.explorer.MegaAccount
import com.kobblo.gallery.file.explorer.ThemeMode
import com.kobblo.gallery.file.explorer.ui.design.KobbloAccent
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreTest {
    private fun restored(vararg saved: Pair<String, Any>): AppState =
        settingsIn(MemoryPreferences(*saved)).restore(appState()) { IconShapeType.IOS }

    @Test
    fun `a new install gets every default`() {
        val state = restored()
        assertEquals(
            appState().copy(
                // What the state can't know by itself, since it depends on the phone.
                orgDestDocs = "/downloads/Docs",
                orgDestImages = "/downloads/Images",
                orgDestApks = "/downloads/Apk",
                orgDestMusic = "/downloads/Music",
                orgDestVideos = "/downloads/Videos",
                nearbyDeviceName = "Phone",
                nearbyReceivePath = "/downloads/KobbloReceived",
                haptics = HapticsSettings(settings = true)
            ),
            state
        )
        assertEquals("device_default", state.currentLanguage)
        assertEquals(14f, state.themeHue)
        assertEquals(7, state.recycleBinRetentionValue)
    }

    @Test
    fun `everything earlier builds saved reads back`() {
        val state = restored(
            "theme_mode" to "DARK",
            "dark_surface_style" to "AMOLED",
            "tint_color" to "Teal",
            "theme_hue" to 200f,
            "theme_lightness" to 0.2f,
            "theme_dynamic" to true,
            "theme_dynamic_explicit" to true,
            "icon_shape" to "CIRCLE",
            "font_style" to "Inter",
            "text_decorations" to setOf("bold", "italic"),
            "main_text_scale" to 1.2f,
            "sub_text_scale" to 0.9f,
            "invert_text" to true,
            "corner_roundness" to 0.8f,
            "grid_columns" to 5,
            "app_language" to "de",
            "is_list_mode" to true,
            "show_hidden_files" to true,
            "has_seen_hide_warning" to true,
            "vault_always_shown" to false,
            "recycle_bin_enabled" to false,
            "recycle_bin_retention_value" to 30,
            "recycle_bin_retention_unit" to "Weeks",
            "org_dest_docs" to "/a",
            "org_dest_images" to "/b",
            "org_dest_apks" to "/c",
            "org_dest_music" to "/d",
            "org_dest_videos" to "/e",
            "viewer_text_pdf" to "External",
            "viewer_music" to "Ask",
            "viewer_image" to "External",
            "device_name" to "Galaxy",
            "nearby_receive_path" to "/in",
            "nearby_ask_before_receiving" to false,
            "haptics_master" to false,
            "haptics_file_open" to false,
            "haptics_copy_paste" to false,
            "haptics_delete" to false,
            "haptics_cleaner" to false,
            "haptics_settings" to false,
            "haptics_fab" to false
        )
        val expected = appState().copy(
            themeMode = ThemeMode.DARK,
            darkSurfaceStyle = DarkSurfaceStyle.AMOLED,
            tint = KobbloAccent.Teal,
            themeHue = 200f,
            themeLightnessOffset = 0.2f,
            useDynamicSystemTheme = true,
            iconShapeSetting = IconShapeType.CIRCLE,
            activeIconShape = IconShapeType.CIRCLE,
            fontStyle = "Inter",
            textDecorations = persistentSetOf("bold", "italic"),
            mainTextScale = 1.2f,
            subTextScale = 0.9f,
            invertText = true,
            cornerRoundness = 0.8f,
            gridColumns = 5,
            currentLanguage = "de",
            isListMode = true,
            showHiddenFiles = true,
            hasSeenHideWarning = true,
            isVaultAlwaysShown = false,
            isRecycleBinEnabled = false,
            recycleBinRetentionValue = 30,
            recycleBinRetentionUnit = "Weeks",
            orgDestDocs = "/a",
            orgDestImages = "/b",
            orgDestApks = "/c",
            orgDestMusic = "/d",
            orgDestVideos = "/e",
            viewerTextPdf = "External",
            viewerMusic = "Ask",
            viewerImage = "External",
            nearbyDeviceName = "Galaxy",
            nearbyReceivePath = "/in",
            nearbyAskBeforeReceiving = false,
            haptics = HapticsSettings(false, false, false, false, false, false, false)
        )
        assertEquals(expected, state)
    }

    @Test
    fun `what isn't a setting is left as it was`() {
        val before = appState().copy(location = "/x", isLoading = true, query = "q")
        val after = settingsIn(MemoryPreferences()).restore(before) { IconShapeType.IOS }
        assertEquals("/x", after.location)
        assertEquals("q", after.query)
        assertTrue(after.isLoading)
    }

    @Test
    fun `a theme saved under the older name counts until there is a newer one`() {
        assertEquals(ThemeMode.LIGHT, restored("themeMode" to "LIGHT").themeMode)
        assertEquals(ThemeMode.DARK, restored("themeMode" to "LIGHT", "theme_mode" to "DARK").themeMode)
        assertEquals(ThemeMode.SYSTEM, restored("theme_mode" to "NEON").themeMode)
        assertEquals(DarkSurfaceStyle.STANDARD, restored("dark_surface_style" to "GLOSS").darkSurfaceStyle)
    }

    @Test
    fun `the hue nobody picked is the default, unless somebody did pick it`() {
        assertEquals(14f, restored("theme_hue" to 262f).themeHue)
        assertEquals(14f, restored("themeHue" to 262f).themeHue)
        assertEquals(262f, restored("theme_hue" to 262f, "theme_hue_explicit" to true).themeHue)
        assertEquals(90f, restored("themeHue" to 90f).themeHue)
    }

    @Test
    fun `the phone's colors only count once they were chosen`() {
        assertFalse(restored("theme_dynamic" to true).useDynamicSystemTheme)
        assertTrue(restored("theme_dynamic" to true, "theme_dynamic_explicit" to true).useDynamicSystemTheme)
    }

    @Test
    fun `a shape that follows the phone is worked out`() {
        val state = restored("icon_shape" to "AUTO")
        assertEquals(IconShapeType.AUTO, state.iconShapeSetting)
        assertEquals(IconShapeType.IOS, state.activeIconShape)
    }

    @Test
    fun `choices are written under the keys they are read from`() {
        val prefs = MemoryPreferences()
        val store = settingsIn(prefs)
        store.themeMode = ThemeMode.DARK
        assertEquals(mapOf("theme_mode" to "DARK", "themeMode" to "DARK"), prefs.values)

        prefs.values.clear()
        store.themeHue = 120f
        assertEquals(
            mapOf(
                "theme_hue" to 120f,
                "themeHue" to 120f,
                "theme_hue_explicit" to true,
                "theme_dynamic" to false,
                "useDynamicSystemTheme" to false
            ),
            prefs.values
        )

        prefs.values.clear()
        store.dynamicTheme = true
        assertEquals(
            mapOf("theme_dynamic" to true, "useDynamicSystemTheme" to true, "theme_dynamic_explicit" to true),
            prefs.values
        )

        prefs.values.clear()
        store.darkSurfaceStyle = DarkSurfaceStyle.AMOLED
        store.tint = KobbloAccent.Pink
        store.iconShape = IconShapeType.SQUARE
        store.textDecorations = setOf("bold")
        store.themeLightness = 0.5f
        store.fontStyle = "Inter"
        store.mainTextScale = 1.5f
        store.subTextScale = 0.5f
        store.invertText = true
        store.cornerRoundness = 1f
        store.gridColumns = 4
        store.language = "fr"
        store.listMode = true
        store.showHiddenFiles = true
        store.hasSeenHideWarning = true
        store.vaultAlwaysShown = false
        store.deviceName = "Mine"
        store.receivePath = "/r"
        store.askBeforeReceiving = false
        store.leftOldNearbyPort = true
        store.hapticsEnabled = false
        store.setHaptic("haptics_fab", false)
        store.setBin(false, 14, "Days")
        store.setOrganizerFolder(SettingsStore.OrganizerFolder.Apks, "/apks")
        store.setViewer(SettingsStore.Viewer.TextAndPdf, "External")
        assertEquals(
            mapOf(
                "dark_surface_style" to "AMOLED",
                "tint_color" to "Pink",
                "icon_shape" to "SQUARE",
                "text_decorations" to setOf("bold"),
                "theme_lightness" to 0.5f,
                "font_style" to "Inter",
                "main_text_scale" to 1.5f,
                "sub_text_scale" to 0.5f,
                "invert_text" to true,
                "corner_roundness" to 1f,
                "grid_columns" to 4,
                "app_language" to "fr",
                "is_list_mode" to true,
                "show_hidden_files" to true,
                "has_seen_hide_warning" to true,
                "vault_always_shown" to false,
                "device_name" to "Mine",
                "nearby_receive_path" to "/r",
                "nearby_ask_before_receiving" to false,
                "nearby_migrated_to_port_53318" to true,
                "haptics_master" to false,
                "haptics_fab" to false,
                "recycle_bin_enabled" to false,
                "recycle_bin_retention_value" to 14,
                "recycle_bin_retention_unit" to "Days",
                "org_dest_apks" to "/apks",
                "viewer_text_pdf" to "External"
            ),
            prefs.values
        )
    }

    @Test
    fun `the connected server of each kind is kept apart, and forgotten when it's let go of`() {
        val prefs = MemoryPreferences()
        val store = settingsIn(prefs)
        assertNull(store.activeServer(ServerKind.Sftp))
        ServerKind.entries.forEach { store.setActiveServer(it, "id-${it.name}") }
        assertEquals(
            mapOf(
                "active_smb_connection" to "id-Smb",
                "active_ftp_connection" to "id-Ftp",
                "active_sftp_connection" to "id-Sftp",
                "active_webdav_connection" to "id-WebDav"
            ),
            prefs.values
        )
        store.setActiveServer(ServerKind.Ftp, null)
        assertNull(store.activeServer(ServerKind.Ftp))
        assertEquals("id-Smb", store.activeServer(ServerKind.Smb))
    }

    @Test
    fun `mega accounts are saved as a list, and the first one by itself as well`() {
        val prefs = MemoryPreferences()
        val store = MegaAccountStore(prefs)
        assertEquals(emptyList<MegaAccount>(), store.load())
        store.save(listOf(MegaAccount("a@x.org", "s1"), MegaAccount("b@x.org", "s2")))
        assertEquals(
            mapOf(
                "mega_accounts" to """[{"email":"a@x.org","session":"s1"},{"email":"b@x.org","session":"s2"}]""",
                "mega_session" to "s1",
                "mega_email" to "a@x.org"
            ),
            prefs.values
        )
        assertEquals(listOf("a@x.org", "b@x.org"), store.load().map { it.email })
        store.save(emptyList())
        assertEquals(mapOf("mega_accounts" to "[]"), prefs.values)
    }

    @Test
    fun `a single mega account from before there could be several becomes a list`() {
        val prefs = MemoryPreferences("mega_session" to "s", "mega_email" to "a@x.org")
        assertEquals(listOf(MegaAccount("a@x.org", "s")), MegaAccountStore(prefs).load())
        assertEquals("""[{"email":"a@x.org","session":"s"}]""", prefs.values["mega_accounts"])
    }

    @Test
    fun `a list that can't be read falls back on the single account`() {
        val prefs = MemoryPreferences("mega_accounts" to "{", "mega_session" to "s", "mega_email" to "a@x.org")
        assertEquals(listOf(MegaAccount("a@x.org", "s")), MegaAccountStore(prefs).load())
        assertEquals(emptyList<MegaAccount>(), MegaAccountStore(MemoryPreferences("mega_accounts" to "{")).load())
    }
}
