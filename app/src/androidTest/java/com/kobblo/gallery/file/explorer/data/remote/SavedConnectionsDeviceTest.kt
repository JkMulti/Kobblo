package com.kobblo.gallery.file.explorer.data.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kobblo.gallery.file.explorer.data.ftp.FtpConnection
import com.kobblo.gallery.file.explorer.data.sftp.SftpConnection
import com.kobblo.gallery.file.explorer.data.sftp.SftpStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Saved connections on a real device, since the passwords go through the Android keystore. */
@RunWith(AndroidJUnit4::class)
class SavedConnectionsDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val kind = "devicetest"

    private fun store() = SavedConnections(context, kind, FtpConnection::class.java, FtpConnection::id)

    private fun ftp(id: String, name: String) =
        FtpConnection(id = id, displayName = name, host = "10.0.0.2", username = "me", savedAt = 5L)

    @Before
    @After
    fun wipe() {
        context.deleteSharedPreferences("${kind}_connections")
        context.deleteSharedPreferences("${kind}_secure_prefs")
    }

    /** What an earlier build left on disk, written here by hand, has to read back unchanged. */
    @Test
    fun readsWhatEarlierBuildsSaved() {
        context.getSharedPreferences("${kind}_connections", Context.MODE_PRIVATE).edit().putString(
            "connections_list",
            """[{"id":"a1","displayName":"Attic NAS","host":"192.168.1.9","port":2121,"username":"sam",""" +
                """"useFtps":true,"useImplicitFtps":false,"initialPath":"/pub","passiveMode":false,"savedAt":77}]"""
        ).commit()
        EncryptedSharedPreferences.create(
            context,
            "${kind}_secure_prefs",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).edit().putString("pwd_a1", "s3cret").commit()

        val saved = store().getConnections()

        assertEquals(1, saved.size)
        assertEquals(
            FtpConnection("a1", "Attic NAS", "192.168.1.9", 2121, "sam", useFtps = true, useImplicitFtps = false, initialPath = "/pub", passiveMode = false, savedAt = 77),
            saved.single()
        )
        assertEquals("s3cret", store().getPassword("a1"))
    }

    @Test
    fun savesReplacesAndDeletes_withEachPassword() {
        val store = store()
        assertTrue(store.getConnections().isEmpty())

        store.saveConnection(ftp("one", "First"), "pw-one")
        store.saveConnection(ftp("two", "Second"), "pw-two")
        store.saveConnection(ftp("one", "First, renamed"), "pw-one-new")

        // A second store reads what the first wrote, as after the app restarts.
        val reopened = store()
        assertEquals(listOf("First, renamed", "Second"), reopened.getConnections().map { it.displayName })
        assertEquals("pw-one-new", reopened.getPassword("one"))
        assertEquals("pw-two", reopened.getPassword("two"))

        reopened.deleteConnection("one")

        assertEquals(listOf("two"), store().getConnections().map { it.id })
        assertNull(store().getPassword("one"))
        assertEquals("pw-two", store().getPassword("two"))
    }

    @Test
    fun passwordsAreNotReadableInThePlainFile() {
        store().saveConnection(ftp("one", "First"), "very-distinctive-password")
        val plain = context.getSharedPreferences("${kind}_secure_prefs", Context.MODE_PRIVATE).all
        assertTrue(plain.isNotEmpty())
        assertTrue(plain.none { (key, value) -> key.contains("pwd_one") || value.toString().contains("very-distinctive-password") })
    }

    @Test
    fun sftpStore_pinsAHostKeyAndKeepsThePassword() {
        val store = SftpStore(context)
        val id = "devicetest-${System.nanoTime()}"
        try {
            store.saveConnection(SftpConnection(id = id, displayName = "Box", host = "h", username = "u", savedAt = 1L), "pw")
            store.updateHostKeyFingerprint(id, "SHA256:abc")

            val saved = SftpStore(context).getConnections().single { it.id == id }
            assertEquals("SHA256:abc", saved.hostKeyFingerprint)
            assertEquals("pw", SftpStore(context).getPassword(id))
        } finally {
            store.deleteConnection(id)
        }
    }
}
