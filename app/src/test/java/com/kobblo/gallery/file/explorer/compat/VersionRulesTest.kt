package com.kobblo.gallery.file.explorer.compat

import com.kobblo.gallery.file.explorer.compat.VersionRules.StorageRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionRulesTest {
    @Test
    fun `Android 11 and up always go to the all files page, whatever was asked before`() {
        for (sdk in 30..36) {
            assertEquals(StorageRoute.AllFilesSettings, VersionRules.storageRoute(sdk, askedBefore = false, systemWouldExplain = false))
            assertEquals(StorageRoute.AllFilesSettings, VersionRules.storageRoute(sdk, askedBefore = true, systemWouldExplain = true))
        }
    }

    @Test
    fun `Android 8 to 10 use the dialog until it has been refused for good`() {
        for (sdk in 26..29) {
            // Never asked: the system says it wouldn't explain, which doesn't mean no here.
            assertEquals(StorageRoute.SystemDialog, VersionRules.storageRoute(sdk, askedBefore = false, systemWouldExplain = false))
            // Refused once: the dialog comes back, with its "don't ask again" box.
            assertEquals(StorageRoute.SystemDialog, VersionRules.storageRoute(sdk, askedBefore = true, systemWouldExplain = true))
            // That box was ticked: the dialog would answer no without showing, and the button would seem dead.
            assertEquals(StorageRoute.AppSettings, VersionRules.storageRoute(sdk, askedBefore = true, systemWouldExplain = false))
        }
    }

    @Test
    fun `the system dialog is raised once a launch, so a refusal can't bring it straight back`() {
        for (sdk in 26..29) {
            assertTrue(VersionRules.asksForStorageOnResume(sdk, askedThisLaunch = false))
            assertFalse(VersionRules.asksForStorageOnResume(sdk, askedThisLaunch = true))
        }
        // From Android 11 the app's own prompt does this, and there's no system dialog that could grant it.
        assertFalse(VersionRules.asksForStorageOnResume(30, askedThisLaunch = false))
        assertFalse(VersionRules.asksForStorageOnResume(36, askedThisLaunch = false))
    }

    @Test
    fun `notifications are only asked for from Android 13, and only once`() {
        for (sdk in 26..32) assertFalse(VersionRules.asksForNotifications(sdk, granted = false, askedBefore = false))
        assertTrue(VersionRules.asksForNotifications(33, granted = false, askedBefore = false))
        assertTrue(VersionRules.asksForNotifications(36, granted = false, askedBefore = false))
        assertFalse(VersionRules.asksForNotifications(34, granted = true, askedBefore = false))
        assertFalse(VersionRules.asksForNotifications(34, granted = false, askedBefore = true))
    }

    @Test
    fun `only the video table lacks orientation, and only before Android 10`() {
        for (sdk in 26..28) {
            assertFalse(VersionRules.mediaTableHasOrientation(sdk, isVideoTable = true))
            assertTrue(VersionRules.mediaTableHasOrientation(sdk, isVideoTable = false))
        }
        assertTrue(VersionRules.mediaTableHasOrientation(29, isVideoTable = true))
        assertTrue(VersionRules.mediaTableHasOrientation(34, isVideoTable = true))
    }

    @Test
    fun `a provider that ignored the limit gets its page cut out by hand`() {
        val limit = "android:query-arg-limit"
        // Android 8 to 10: no honored-args extra at all.
        assertFalse(VersionRules.providerAppliedLimit(26, null, limit))
        assertFalse(VersionRules.providerAppliedLimit(29, arrayOf("android:query-arg-sql-selection"), limit))
        assertEquals(240, VersionRules.rowsToSkip(240, providerApplied = false))
        // A provider that says it used it.
        assertTrue(VersionRules.providerAppliedLimit(29, arrayOf(limit), limit))
        assertEquals(0, VersionRules.rowsToSkip(240, providerApplied = true))
        // Android 11 and up, said or not: stepping over a page there would lose rows.
        assertTrue(VersionRules.providerAppliedLimit(30, null, limit))
        assertTrue(VersionRules.providerAppliedLimit(36, null, limit))
    }
}
