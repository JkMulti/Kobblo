package com.kobblo.gallery.file.explorer.vault

import com.kobblo.gallery.file.explorer.fileops.SafeFiles
import org.json.JSONObject
import java.io.File

/**
 * Where the unencrypted files of an older version came from. Those versions kept it in one JSON file
 * next to the Vault's folder, in the clear:
 *
 *     { "<name in the vault>": { "originalPath": "/storage/…", … }, … }
 *
 * A sealed file carries its origin inside it, so this is only read for files still waiting to be
 * encrypted, it only ever shrinks, and it goes when the last of them has.
 */
internal class VaultLegacyOrigins(private val record: File) {
    val exists: Boolean get() = record.exists()

    /**
     * One reading of the record, for looking names up in. The answer is the path as it was written
     * down, which can be blank; null means the name was never recorded.
     */
    fun lookup(): (name: String) -> String? {
        val entries = read()
        return { name -> entries[name]?.optString(ORIGINAL_PATH) }
    }

    /**
     * Drops the entries of files [isStillWaiting] no longer finds, keeping the others exactly as they
     * were written. With none left the record itself is deleted.
     */
    fun keepOnly(isStillWaiting: (name: String) -> Boolean) {
        val remaining = read().filterKeys(isStillWaiting)
        try {
            if (remaining.isEmpty()) {
                record.delete()
                return
            }
            val json = JSONObject()
            remaining.forEach { (name, entry) -> json.put(name, entry) }
            // The only note of where these files belong, so it's replaced whole or not at all.
            SafeFiles.writeAtomically(record, json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** The entries in the order they were written. A record that can't be parsed reads as far as it could be. */
    private fun read(): Map<String, JSONObject> {
        val entries = LinkedHashMap<String, JSONObject>()
        if (!record.exists()) return entries
        try {
            val text = record.readText()
            if (text.isNotBlank()) {
                val root = JSONObject(text)
                root.keys().forEach { name -> entries[name] = root.getJSONObject(name) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return entries
    }

    private companion object {
        const val ORIGINAL_PATH = "originalPath"
    }
}
