package com.kobblo.gallery.file.explorer.viewer.video

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import com.kobblo.gallery.file.explorer.viewer.FileKinds

/**
 * Where a byte of a plain transport stream lies in a BDAV one, the form AVCHD cameras and Blu-ray
 * write as `.m2ts` and `.mts`. It's the same stream, with a 4-byte timestamp ahead of every packet.
 */
internal object BdavPackets {
    const val PlainSize = 188
    const val BdavSize = 192
    private const val Header = BdavSize - PlainSize
    private const val Sync: Byte = 0x47

    fun storedPosition(plainPosition: Long): Long =
        plainPosition / PlainSize * BdavSize + Header + plainPosition % PlainSize

    /** How many plain bytes are left, given where reading starts and how many stored bytes follow it. */
    fun plainBytesAvailable(plainPosition: Long, storedBytesRemaining: Long): Long {
        val storedEnd = storedPosition(plainPosition) + storedBytesRemaining
        return storedEnd / BdavSize * PlainSize - plainPosition
    }

    /** Sync bytes sit four bytes in and every 192 after, which a plain stream's never do. */
    fun looksLikeBdav(head: ByteArray, length: Int): Boolean {
        if (length < Header + BdavSize * 2 + 1) return false
        var at = Header
        var seen = 0
        while (at < length) {
            if (head[at] != Sync) return false
            seen++
            at += BdavSize
        }
        return seen >= 3
    }
}

/**
 * Lets ExoPlayer's transport stream reader, which only knows 188-byte packets, read a BDAV file by
 * leaving the timestamps out as it goes. Anything else passes straight through.
 */
@OptIn(UnstableApi::class)
internal class BdavDataSource(private val upstream: DataSource) : DataSource by upstream {
    private var isBdav = false
    private var plainPosition = 0L
    private var plainRemaining = 0L
    private val skip = ByteArray(BdavPackets.BdavSize - BdavPackets.PlainSize)

    /** Reading starts at a packet's first plain byte, already past the timestamp before it. */
    private var headerSkippedAt = -1L

    override fun open(dataSpec: DataSpec): Long {
        isBdav = mightBeBdav(dataSpec.uri) && probe(dataSpec.uri)
        if (!isBdav) return upstream.open(dataSpec)

        plainPosition = dataSpec.position
        headerSkippedAt = dataSpec.position
        val stored = upstream.open(dataSpec.buildUpon().setPosition(BdavPackets.storedPosition(dataSpec.position)).setLength(C.LENGTH_UNSET.toLong()).build())
        val available = if (stored == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else BdavPackets.plainBytesAvailable(dataSpec.position, stored)
        plainRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available, dataSpec.length)
        return if (plainRemaining == Long.MAX_VALUE) C.LENGTH_UNSET.toLong() else plainRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!isBdav) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (plainRemaining <= 0L) return C.RESULT_END_OF_INPUT

        // Crossing into the next packet means stepping over its timestamp first.
        if (plainPosition % BdavPackets.PlainSize == 0L && !skipHeader()) return C.RESULT_END_OF_INPUT
        val leftInPacket = BdavPackets.PlainSize - (plainPosition % BdavPackets.PlainSize).toInt()
        val want = minOf(length.toLong(), leftInPacket.toLong(), plainRemaining).toInt()
        val read = upstream.read(buffer, offset, want)
        if (read > 0) {
            plainPosition += read
            plainRemaining -= read
        }
        return read
    }

    private fun skipHeader(): Boolean {
        if (headerSkippedAt == plainPosition) return true
        var done = 0
        while (done < skip.size) {
            val read = upstream.read(skip, done, skip.size - done)
            if (read == C.RESULT_END_OF_INPUT) return false
            done += read
        }
        headerSkippedAt = plainPosition
        return true
    }

    private fun mightBeBdav(uri: Uri): Boolean =
        FileKinds.extensionOf(uri.lastPathSegment.orEmpty()) in setOf("m2ts", "mts")

    private fun probe(uri: Uri): Boolean = try {
        upstream.open(DataSpec.Builder().setUri(uri).setPosition(0).setLength(ProbeBytes.toLong()).build())
        val head = ByteArray(ProbeBytes)
        var filled = 0
        while (filled < head.size) {
            val read = upstream.read(head, filled, head.size - filled)
            if (read == C.RESULT_END_OF_INPUT) break
            filled += read
        }
        BdavPackets.looksLikeBdav(head, filled)
    } catch (_: Exception) {
        false
    } finally {
        try { upstream.close() } catch (_: Exception) {}
    }

    class Factory(context: Context) : DataSource.Factory {
        private val upstream = DefaultDataSource.Factory(context)
        override fun createDataSource(): DataSource = BdavDataSource(upstream.createDataSource())
    }

    private companion object {
        const val ProbeBytes = BdavPackets.BdavSize * 4
    }
}
