package com.kobblo.gallery.file.explorer.transfer.send

import android.content.Context
import com.kobblo.gallery.file.explorer.localsend.TransferSession
import com.kobblo.gallery.file.explorer.localsend.TransferStatus
import com.kobblo.gallery.file.explorer.transfer.core.TransferDirection
import com.kobblo.gallery.file.explorer.transfer.core.TransferHistoryStore
import com.kobblo.gallery.file.explorer.transfer.core.TransferRecord
import com.kobblo.gallery.file.explorer.transfer.core.TransferRecordFile
import com.kobblo.gallery.file.explorer.transfer.core.TransferResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** The app-wide history of transfers. Reads and writes stay off the main thread. */
object TransferHistory {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _records = MutableStateFlow<List<TransferRecord>>(emptyList())
    val records: StateFlow<List<TransferRecord>> = _records.asStateFlow()

    @Volatile private var store: TransferHistoryStore? = null

    fun attach(context: Context) {
        if (store != null) return
        synchronized(this) {
            if (store != null) return
            store = TransferHistoryStore(File(context.applicationContext.filesDir, "transfer_history.json"))
        }
        scope.launch { _records.value = store?.all().orEmpty() }
    }

    fun add(record: TransferRecord) = change { it.add(record) }
    fun merge(record: TransferRecord) = change { it.merge(record) }
    fun remove(id: String) = change { it.remove(id) }
    fun clear() = change { it.clear() }

    /** Records how a session ended, unless it never got as far as being a transfer. */
    fun record(session: TransferSession) {
        val result = when (session.status) {
            TransferStatus.COMPLETED -> TransferResult.COMPLETED
            TransferStatus.CANCELLED -> TransferResult.CANCELLED
            TransferStatus.REJECTED -> TransferResult.DECLINED
            TransferStatus.FAILED -> if (session.finishedFiles > 0) TransferResult.PARTIAL else TransferResult.FAILED
            else -> return
        }
        // What it said is what someone will look for later, and a note that never arrived isn't lost.
        val note = if (session.isIncoming) null else session.files.singleOrNull()?.preview?.take(Notes.MAX_HISTORY_CHARS)
        add(
            TransferRecord(
                id = UUID.randomUUID().toString(),
                direction = if (session.isIncoming) TransferDirection.RECEIVED else TransferDirection.SENT,
                peerAlias = session.peer.alias,
                peerAddress = session.peer.id,
                peerFingerprint = session.peer.fingerprint.ifBlank { null },
                timeMs = System.currentTimeMillis(),
                result = result,
                message = session.errorMessage,
                note = note,
                files = if (note != null) emptyList() else session.files.map { item ->
                    TransferRecordFile(
                        name = item.fileName,
                        size = item.size,
                        path = item.localPath,
                        ok = item.isFinished
                    )
                }
            )
        )
    }

    private fun change(action: (TransferHistoryStore) -> List<TransferRecord>) {
        val current = store ?: return
        scope.launch { _records.value = action(current) }
    }
}
