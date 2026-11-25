package com.kobblo.gallery.file.explorer.startup

import android.content.Intent
import com.kobblo.gallery.file.explorer.music.MusicService
import com.kobblo.gallery.file.explorer.tools.files.ToolsFiles
import com.kobblo.gallery.file.explorer.transfer.TransferEntry
import com.kobblo.gallery.file.explorer.transfer.send.TransferNotices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingRequestTest {
    /** An intent on paper, with words standing in for addresses. It keeps count of what gets unpacked. */
    private class Parts(
        override val action: String?,
        override val type: String? = null,
        override val data: String? = null,
        private val stream: String? = null,
        private val streams: List<String>? = null,
        private val clip: List<String?> = emptyList(),
        private val text: String? = null,
        private val extras: Map<String, String> = emptyMap()
    ) : IntentParts<String> {
        val unpacked = mutableListOf<String>()

        override fun stream() = stream.also { unpacked += "stream" }
        override fun streams() = streams.also { unpacked += "streams" }
        override fun clip() = clip.also { unpacked += "clip" }
        override fun sharedText() = text.also { unpacked += "text" }
        override fun extra(name: String) = extras[name].also { unpacked += name }
    }

    @Test
    fun openingTheApp_asksForNothing() {
        assertNull(requestIn(Parts(Intent.ACTION_MAIN)))
        assertNull(requestIn(Parts(action = null, data = "a", stream = "b", text = "c")))
        assertNull(requestIn(Parts("another.app.ACTION", data = "a", stream = "b", text = "c")))
    }

    @Test
    fun openingTheApp_unpacksNothing() {
        val parts = Parts(Intent.ACTION_MAIN, stream = "a", streams = listOf("b"), clip = listOf("c"), text = "d")

        requestIn(parts)

        assertEquals(emptyList<String>(), parts.unpacked)
    }

    @Test
    fun oneSharedFile_isTakenFromWhereItBelongsFirst() {
        val parts = Parts(Intent.ACTION_SEND, type = "image/png", data = "data", stream = "stream", clip = listOf("clip"), text = "caption")

        assertEquals(IncomingRequest.SharedFiles(listOf("stream"), "image/png"), requestIn(parts))
    }

    @Test
    fun oneSharedFile_canBeOnTheClip_andOnlyTheFirstThingThereCounts() {
        val clipped = Parts(Intent.ACTION_SEND, data = "data", clip = listOf("first", "second"))
        assertEquals(IncomingRequest.SharedFiles(listOf("first"), null), requestIn(clipped))

        val wordsFirst = Parts(Intent.ACTION_SEND, data = "data", clip = listOf(null, "second"))
        assertEquals(IncomingRequest.SharedFiles(listOf("data"), null), requestIn(wordsFirst))
    }

    @Test
    fun oneSharedFile_canBeTheIntentsOwnAddress() {
        val parts = Parts(Intent.ACTION_SEND, type = "text/plain", data = "data", text = "caption")

        assertEquals(IncomingRequest.SharedFiles(listOf("data"), "text/plain"), requestIn(parts))
    }

    @Test
    fun sharedWords_countOnlyWhenNoFileCameWithThem() {
        assertEquals(
            IncomingRequest.SharedText("https://kobblo.com"),
            requestIn(Parts(Intent.ACTION_SEND, type = "text/plain", clip = listOf(null), text = "https://kobblo.com"))
        )
        assertNull(requestIn(Parts(Intent.ACTION_SEND, type = "text/plain")))
    }

    @Test
    fun severalSharedFiles_comeFromTheListOrElseTheClip() {
        assertEquals(
            IncomingRequest.SharedFiles(listOf("a", "b"), "image/*"),
            requestIn(Parts(Intent.ACTION_SEND_MULTIPLE, type = "image/*", streams = listOf("a", "b"), clip = listOf("c")))
        )
        assertEquals(
            IncomingRequest.SharedFiles(listOf("c", "d"), null),
            requestIn(Parts(Intent.ACTION_SEND_MULTIPLE, clip = listOf("c", null, "d")))
        )
    }

    @Test
    fun severalSharedFiles_withNoneInThem_askForNothing() {
        assertNull(requestIn(Parts(Intent.ACTION_SEND_MULTIPLE, data = "data", text = "words")))
        assertNull(requestIn(Parts(Intent.ACTION_SEND_MULTIPLE, clip = listOf(null))))
        // An empty list is still the sender's list, so the clip isn't looked at instead.
        assertNull(requestIn(Parts(Intent.ACTION_SEND_MULTIPLE, streams = emptyList(), clip = listOf("c"))))
    }

    @Test
    fun viewing_opensTheFile_andWithNoFileOnlyTheApp() {
        assertEquals(
            IncomingRequest.OpenFile("content://photo", "image/jpeg"),
            requestIn(Parts(Intent.ACTION_VIEW, type = "image/jpeg", data = "content://photo", stream = "other"))
        )
        assertNull(requestIn(Parts(Intent.ACTION_VIEW, type = "image/jpeg", stream = "other")))
    }

    @Test
    fun aFolderShortcut_opensItsFolder() {
        val parts = Parts(ToolsFiles.ACTION_OPEN_FOLDER, extras = mapOf(ToolsFiles.EXTRA_FOLDER_PATH to "/storage/emulated/0/DCIM"))

        assertEquals(IncomingRequest.OpenFolder("/storage/emulated/0/DCIM"), requestIn(parts))
        assertNull(requestIn(Parts(ToolsFiles.ACTION_OPEN_FOLDER)))
    }

    @Test
    fun theAppsOwnNotificationsAndTiles_goWhereTheySay() {
        assertEquals(IncomingRequest.ShowPlayer, requestIn(Parts(MusicService.ActionOpenPlayer)))
        assertEquals(IncomingRequest.GetReadyToReceive, requestIn(Parts(TransferEntry.ACTION_RECEIVE)))
    }

    @Test
    fun acceptingFiles_namesThePromptItAnswers_orDoesNothing() {
        val parts = Parts(TransferEntry.ACTION_ACCEPT_PROMPT, extras = mapOf(TransferNotices.EXTRA_PROMPT to "42"))

        assertEquals(IncomingRequest.AcceptFiles("42"), requestIn(parts))
        assertNull(requestIn(Parts(TransferEntry.ACTION_ACCEPT_PROMPT)))
    }
}
