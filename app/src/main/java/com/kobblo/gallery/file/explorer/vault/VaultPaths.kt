package com.kobblo.gallery.file.explorer.vault

/** Recognizes a Vault file from its path alone, for code far from a context. */
object VaultPaths {
    const val FolderName = "vault_files"
    private const val Marker = "/files/$FolderName/"

    fun isVaultPath(path: String): Boolean = path.contains(Marker)
}
