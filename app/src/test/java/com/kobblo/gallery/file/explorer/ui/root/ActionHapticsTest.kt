package com.kobblo.gallery.file.explorer.ui.root

import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.HapticEvent
import com.kobblo.gallery.file.explorer.NavTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionHapticsTest {
    private fun assertFeels(feel: HapticEvent?, vararg actions: AppAction) =
        actions.forEach { assertEquals(it.toString(), feel, feelOf(it)) }

    @Test
    fun deletingFeelsLikeDeleting() = assertFeels(
        HapticEvent.Delete,
        AppAction.DeleteSelectedFiles, AppAction.DeleteFiles(emptyList()), AppAction.PermanentlyDeleteTrashFiles(emptyList())
    )

    @Test
    fun movingFilesAboutFeelsLikeCopying() = assertFeels(
        HapticEvent.CopyPaste,
        AppAction.SetClipboard("copy"), AppAction.PasteClipboard("/a"), AppAction.MoveDragged(emptyList(), "/a")
    )

    @Test
    fun openingFeelsLikeOpening() = assertFeels(HapticEvent.FileOpen, AppAction.OpenFileViewer(1), AppAction.PlayAudio(testFile("a.mp3")))

    @Test
    fun settingsAndTheVaultFeelAlike() = assertFeels(
        HapticEvent.SettingsToggle,
        AppAction.SetShowSettingsScreen(true), AppAction.SetShowVaultScreen(false), AppAction.ToggleHapticsMaster(true),
        AppAction.ToggleHapticsOption("delete", false), AppAction.SetRecycleBinSettings(true, 7, "Days")
    )

    @Test
    fun theCleanerHasItsOwn() = assertFeels(
        HapticEvent.Cleaner,
        AppAction.SetCleanerScreenVisible(true), AppAction.SetCleanerCategory(null),
        AppAction.SelectAllCleanerFiles(emptyList()), AppAction.ClearCleanerSelection
    )

    @Test
    fun makingSomethingNewFeelsLikeThePlusButton() =
        assertFeels(HapticEvent.Fab, AppAction.CreateFolder("a"), AppAction.CreateFile("a"))

    @Test
    fun whatTheAppDoesByItselfIsNotFelt() = listOf(
        AppAction.Reload, AppAction.LoadFileDetails("/a") {}, AppAction.AutoRequestAccess("/a"),
        AppAction.AuthSuccess(com.kobblo.gallery.file.explorer.AuthReason.OPEN_FILE, "/a"),
        AppAction.SetGoogleDriveAuthStatus(true, null), AppAction.SetMegaAuthStatus(true, null), AppAction.SetDropboxAuthStatus(true, null)
    ).forEach { assertNull(it.toString(), feelOf(it)) }

    @Test
    fun anythingElseIsATap() = assertFeels(
        HapticEvent.General,
        AppAction.SetLocation("home"), AppAction.ClearSelection, AppAction.ViewFile(testFile("a.jpg")), AppAction.ClipFile("/a", "cut"),
        AppAction.RestoreTrashFiles(emptyList()), AppAction.SelectNavTab(NavTab.HOME),
        AppAction.NearbyShareAction.StageFiles(emptyList()), AppAction.SmbAction.Connect("a"), AppAction.ShowToast("a")
    )
}
