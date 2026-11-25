package com.kobblo.gallery.file.explorer.transfer.core

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException

enum class TransferDirection { SENT, RECEIVED }

enum class TransferResult { COMPLETED, PARTIAL, FAILED, CANCELLED, DECLINED }

data class TransferRecordFile(
    val name: String,
    val size: Long,
    /** Where it is on this phone, for opening or sending again. Absent for a file that never arrived. */
    val path: String? = null,
    val ok: Boolean = true
)

/** One send or receive, as remembered afterwards. */
data class TransferRecord(
    val id: String,
    val direction: TransferDirection,
    val peerAlias: String,
    val peerAddress: String? = null,
    val peerFingerprint: String? = null,
    /** "nearby" for device to device, "web" for a browser through Web Share. */
    val channel: String = "nearby",
    val timeMs: Long,
    val result: TransferResult,
    val message: String? = null,
    val files: List<TransferRecordFile> = emptyList(),
    /** Files beyond the ones kept in [files], so one huge folder can't bloat the history. */
    val moreFiles: Int = 0,
    val totalBytes: Long = files.sumOf { it.size },
    /** The words, when this was a note rather than files. */
    val note: String? = null
) {
    val fileCount: Int get() = files.size + moreFiles
}

/**
 * The history of sends and receives, in one small JSON file. Writes go to a temporary file and then
 * replace the old one, so a crash mid-save can't lose what was already there.
 */
class TransferHistoryStore(
    private val file: File,
    private val maxRecords: Int = 500,
    private val maxFilesPerRecord: Int = 2000
) {
    private val gson = Gson()
    private val listType = object : TypeToken<List<TransferRecord>>() {}.type
    private var records: MutableList<TransferRecord>? = null

    /** Newest first. */
    @Synchronized
    fun all(): List<TransferRecord> = loaded().toList()

    @Synchronized
    fun add(record: TransferRecord): List<TransferRecord> {
        val list = loaded()
        list.removeAll { it.id == record.id }
        list.add(0, trimmed(record))
        return commit(list)
    }

    /**
     * Folds [record] into the one with its id, keeping the first's place in the list. Web Share adds
     * each file as it finishes, so a browser's whole session reads as one line.
     */
    @Synchronized
    fun merge(record: TransferRecord): List<TransferRecord> {
        val list = loaded()
        val index = list.indexOfFirst { it.id == record.id }
        if (index < 0) {
            list.add(0, trimmed(record))
        } else {
            val existing = list[index]
            list[index] = trimmed(
                existing.copy(
                    timeMs = record.timeMs,
                    files = existing.files + record.files,
                    moreFiles = existing.moreFiles + record.moreFiles,
                    totalBytes = existing.totalBytes + record.totalBytes,
                    result = if (existing.result == TransferResult.COMPLETED) record.result else existing.result,
                    message = record.message ?: existing.message
                )
            )
        }
        return commit(list)
    }

    @Synchronized
    fun remove(id: String): List<TransferRecord> {
        val list = loaded()
        list.removeAll { it.id == id }
        return commit(list)
    }

    @Synchronized
    fun clear(): List<TransferRecord> {
        val list = loaded()
        list.clear()
        return commit(list)
    }

    private fun trimmed(record: TransferRecord): TransferRecord {
        if (record.files.size <= maxFilesPerRecord) return record
        return record.copy(
            files = record.files.take(maxFilesPerRecord),
            moreFiles = record.moreFiles + record.files.size - maxFilesPerRecord
        )
    }

    private fun commit(list: MutableList<TransferRecord>): List<TransferRecord> {
        while (list.size > maxRecords) list.removeAt(list.size - 1)
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(gson.toJson(list))
            if (!temp.renameTo(file)) throw IOException("Couldn't replace ${file.name}")
        } catch (e: IOException) {
            // History is a convenience. A full disk must not break the transfer that is being recorded.
        }
        return list.toList()
    }

    private fun loaded(): MutableList<TransferRecord> {
        records?.let { return it }
        val loaded: MutableList<TransferRecord> = try {
            if (file.exists()) {
                val parsed: List<TransferRecord>? = gson.fromJson(file.readText(), listType)
                // Gson fills in null for a field an older file lacks, even when Kotlin says it can't be.
                parsed.orEmpty().filter { isComplete(it) }.toMutableList()
            } else mutableListOf()
        } catch (e: Exception) {
            // A damaged file is set aside rather than deleted, and the history starts over.
            file.renameTo(File(file.parentFile, "${file.name}.bad"))
            mutableListOf()
        }
        records = loaded
        return loaded
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun isComplete(record: TransferRecord): Boolean =
        record.id != null && record.direction != null && record.result != null &&
            record.peerAlias != null && record.files != null
}
