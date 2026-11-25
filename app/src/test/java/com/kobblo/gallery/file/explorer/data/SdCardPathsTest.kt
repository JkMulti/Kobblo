package com.kobblo.gallery.file.explorer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdCardPathsTest {
    private val builtIn = "/storage/emulated/0"

    @Test
    fun `the system's own mount point is read as the one apps can open`() {
        assertEquals("/storage/1A2B-3C4D", SdCardPaths.readable("/mnt/media_rw/1A2B-3C4D"))
        assertEquals("/storage/1A2B-3C4D/Android/data", SdCardPaths.readable("/mnt/media_rw/1A2B-3C4D/Android/data"))
        assertEquals("/storage/1A2B-3C4D", SdCardPaths.readable("/storage/1A2B-3C4D"))
        assertEquals("/data/mnt/media_rw/x", SdCardPaths.readable("/data/mnt/media_rw/x"))
    }

    @Test
    fun `an app folder gives the root of the storage it's on`() {
        assertEquals("/storage/1A2B-3C4D", SdCardPaths.rootOfAppFolder("/storage/1A2B-3C4D/Android/data/com.kobblo/files"))
        assertEquals("/storage/1A2B-3C4D", SdCardPaths.rootOfAppFolder("/storage/1A2B-3C4D/android/data/com.kobblo/files"))
        assertEquals("/storage/1A2B-3C4D/files", SdCardPaths.rootOfAppFolder("/storage/1A2B-3C4D/files"))
    }

    @Test
    fun `an app folder on the built-in storage doesn't point to a card`() {
        assertFalse(SdCardPaths.isOffBuiltIn("/storage/emulated/0/Android/data/app/files", builtIn))
        // The same storage reached by another user's number.
        assertFalse(SdCardPaths.isOffBuiltIn("/storage/emulated/10/Android/data/app/files", builtIn))
        assertTrue(SdCardPaths.isOffBuiltIn("/storage/1A2B-3C4D/Android/data/app/files", builtIn))
    }

    @Test
    fun `the mount table gives the first card and skips the built-in storage`() {
        val mounts = sequenceOf(
            "/dev/block/dm-5 /data ext4 rw,seclabel 0 0",
            "/dev/fuse /storage/emulated fuse rw,nosuid 0 0",
            "/dev/fuse /storage/self fuse rw 0 0",
            "tmpfs /storage tmpfs rw 0 0",
            "",
            "lonely",
            "/dev/block/vold/public:179,1 /mnt/media_rw/1A2B-3C4D vfat rw 0 0",
            "/dev/fuse    /storage/1A2B-3C4D\tfuse rw 0 0",
            "/dev/fuse /storage/FFFF-0000 fuse rw 0 0"
        )
        assertEquals("/storage/1A2B-3C4D", SdCardPaths.mountedCard(mounts))
    }

    @Test
    fun `a mount table with no card gives nothing`() {
        assertNull(SdCardPaths.mountedCard(emptySequence()))
        assertNull(SdCardPaths.mountedCard(sequenceOf("/dev/fuse /storage/emulated fuse rw 0 0", "tmpfs /storage tmpfs rw 0 0")))
    }

    @Test
    fun `the folders under storage that aren't cards`() {
        for (name in listOf("emulated", "self", "knox", ".hidden")) assertFalse(name, SdCardPaths.isCardFolderName(name))
        for (name in listOf("1A2B-3C4D", "sdcard1", "Emulated")) assertTrue(name, SdCardPaths.isCardFolderName(name))
    }

    @Test
    fun `the app's own shorthand is the card`() {
        assertTrue(SdCardPaths.isSdCardLocation("sd", null))
        assertTrue(SdCardPaths.isSdCardLocation("sd:DCIM", null))
        assertTrue(SdCardPaths.isSdCardLocation("sd/DCIM/Camera", null))
        assertFalse(SdCardPaths.isSdCardLocation("sdcard", null))
        assertFalse(SdCardPaths.isSdCardLocation("home", null))
    }

    @Test
    fun `anything under the known card is on it`() {
        val card = "/mnt/expand/card"
        assertTrue(SdCardPaths.isSdCardLocation(card, card))
        assertTrue(SdCardPaths.isSdCardLocation("$card/DCIM", card))
        assertFalse(SdCardPaths.isSdCardLocation("${card}2/DCIM", card))
        assertFalse(SdCardPaths.isSdCardLocation("$card/DCIM", null))
    }

    @Test
    fun `a path that can only be removable storage is the card even when none is known`() {
        assertTrue(SdCardPaths.isSdCardLocation("/storage/1A2B-3C4D/Music", null))
        assertTrue(SdCardPaths.isSdCardLocation("/mnt/media_rw/1A2B-3C4D", null))
        assertFalse(SdCardPaths.isSdCardLocation("/storage/emulated/0/Music", null))
        assertFalse(SdCardPaths.isSdCardLocation("/storage/self/primary", null))
        assertFalse(SdCardPaths.isSdCardLocation("/storage", null))
        assertFalse(SdCardPaths.isSdCardLocation("/data/local/tmp", null))
        assertFalse(SdCardPaths.isSdCardLocation("drive_id:1", "/storage/1A2B-3C4D"))
    }

    @Test
    fun `sizes are in decimal gigabytes`() {
        assertEquals(64f, SdCardPaths.gigabytes(64_000_000_000L), 0f)
        assertEquals(0.5f, SdCardPaths.gigabytes(500_000_000L), 0f)
        assertEquals(0f, SdCardPaths.gigabytes(0L), 0f)
    }
}
