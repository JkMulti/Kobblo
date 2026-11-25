package com.kobblo.gallery.file.explorer.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultPathsTest {
    @Test
    fun `the vault's private folder is recognized, whichever way the system spells it`() {
        assertTrue(VaultPaths.isVaultPath("/data/user/0/com.kobblo.gallery.file.explorer/files/vault_files/a.jpg"))
        assertTrue(VaultPaths.isVaultPath("/data/data/com.kobblo.gallery.file.explorer/files/vault_files/a.jpg"))
    }

    @Test
    fun `a folder that only sounds like it isn't`() {
        assertFalse(VaultPaths.isVaultPath("/storage/emulated/0/vault_files/a.jpg"))
        assertFalse(VaultPaths.isVaultPath("/storage/emulated/0/Download/vault_files.jpg"))
        assertFalse(VaultPaths.isVaultPath("content://media/external/images/1"))
    }
}
