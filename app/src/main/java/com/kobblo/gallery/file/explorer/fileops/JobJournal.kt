package com.kobblo.gallery.file.explorer.fileops

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** What's written down about a job before it starts. Never a password, and never file contents. */
internal data class JobPlan(
    val kind: JobKind,
    /** How to name it in a sentence: a folder's name, or an archive's. */
    val label: String,
    /** Where to look afterwards. */
    val folder: String? = null,
    val sources: List<String> = emptyList()
)

/**
 * A private note of how far a job has got, so that if the process dies the next launch can clean up and say
 * what really happened. Each line goes straight to the system, and only a dead phone can lose what the system
 * has, so a kill can't lose it. A line is the cost of one small write, against a rename or a copy per item.
 */
internal class JobJournal(val file: File) {
    private var out: FileOutputStream? = null

    fun begin(id: Long, plan: JobPlan, startedAt: Long) {
        try {
            file.parentFile?.mkdirs()
            val stream = FileOutputStream(file, false)
            out = stream
            val text = StringBuilder()
            text.appendLine(line(HEADER, id.toString(), plan.kind.name, startedAt.toString(), plan.label, plan.folder.orEmpty()))
            for (source in plan.sources) text.appendLine(line(SOURCE, source))
            stream.write(text.toString().toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            // Without its note a job can still do its work, and nothing it does is less safe for it.
            android.util.Log.w("JobJournal", "Couldn't write a job's note", e)
        }
    }

    fun ran() = add(line(RAN))

    fun started(source: String, dest: String) = add(line(BEGIN, source, dest))

    /** The copy is whole and read back. If it's a move, its original is about to go. */
    fun verified(source: String, dest: String) = add(line(VERIFIED, source, dest))

    fun finished(source: String, dest: String) = add(line(FINISHED, source, dest))

    fun rolledBack(source: String, dest: String) = add(line(ROLLED_BACK, source, dest))

    /** An archive being built at [part], which gets the name [final] once it's whole. */
    fun building(part: String, final: String) = add(line(BUILDING, part, final))

    /**
     * Unpacking [archive] into [dest]. [startedEmpty] says it's all this job's own and may be removed. Otherwise
     * it's going to [staging] first, a hidden folder inside, to join what's there only once it's whole.
     */
    fun unpacking(archive: String, dest: String, startedEmpty: Boolean, staging: String?) =
        add(line(UNPACKING, archive, dest, if (startedEmpty) "1" else "0", staging.orEmpty()))

    /** Something that was staged has now joined the folder, under this name. */
    fun joined(path: String) = add(line(JOINED, path))

    private fun add(text: String) {
        try {
            out?.write((text + "\n").toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            android.util.Log.w("JobJournal", "Couldn't update a job's note", e)
        }
    }

    fun delete() {
        try {
            out?.close()
        } catch (_: IOException) {
        }
        out = null
        file.delete()
    }

    companion object {
        const val HEADER = "H"
        const val SOURCE = "S"
        const val RAN = "R"
        const val BEGIN = "B"
        const val VERIFIED = "V"
        const val FINISHED = "F"
        const val ROLLED_BACK = "X"
        const val BUILDING = "P"
        const val UNPACKING = "U"
        const val JOINED = "J"

        private fun line(tag: String, vararg fields: String): String =
            fields.joinToString(prefix = tag, separator = "") { "\t" + escape(it) }

        /** Any byte but a slash and a zero can be in a file name, tabs and line ends included. */
        private fun escape(text: String): String = buildString(text.length) {
            for (c in text) {
                when (c) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(c)
                }
            }
        }

        private fun unescape(text: String): String = buildString(text.length) {
            var index = 0
            while (index < text.length) {
                val c = text[index]
                if (c == '\\' && index + 1 < text.length) {
                    index++
                    when (text[index]) {
                        't' -> append('\t')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        else -> append(text[index])
                    }
                } else {
                    append(c)
                }
                index++
            }
        }

        /** Null if there's no usable header. The last line may be cut short by the kill, and is then just skipped. */
        fun read(file: File): JobRecord? {
            val lines = try {
                file.readLines(Charsets.UTF_8)
            } catch (_: IOException) {
                return null
            }
            val parts = lines.map { raw -> raw.split('\t').map(::unescape) }
            val header = parts.firstOrNull()?.takeIf { it.size >= 6 && it[0] == HEADER } ?: return null
            val kind = JobKind.entries.firstOrNull { it.name == header[2] } ?: return null
            val record = JobRecord(
                kind = kind,
                label = header[4],
                folder = header[5].ifEmpty { null },
                startedAt = header[3].toLongOrNull() ?: 0L
            )
            for (fields in parts.drop(1)) {
                when (fields[0]) {
                    SOURCE -> if (fields.size >= 2) record.sources += fields[1]
                    RAN -> record.ran = true
                    BEGIN -> if (fields.size >= 3) record.begun[fields[1]] = fields[2]
                    VERIFIED -> if (fields.size >= 3) record.verified += fields[1]
                    FINISHED -> if (fields.size >= 3) {
                        record.begun.remove(fields[1])
                        record.finished += fields[1] to fields[2]
                    }
                    ROLLED_BACK -> if (fields.size >= 3) record.begun.remove(fields[1])
                    BUILDING -> if (fields.size >= 3) record.building = fields[1] to fields[2]
                    UNPACKING -> if (fields.size >= 5) {
                        record.unpacking = Unpacking(fields[1], fields[2], fields[3] == "1", fields[4].ifEmpty { null })
                    }
                    JOINED -> if (fields.size >= 2) record.joined += fields[1]
                }
            }
            return record
        }
    }
}

internal data class Unpacking(val archive: String, val dest: String, val startedEmpty: Boolean, val staging: String?)

/** A journal read back. */
internal class JobRecord(
    val kind: JobKind,
    val label: String,
    val folder: String?,
    val startedAt: Long,
    val sources: MutableList<String> = ArrayList(),
    var ran: Boolean = false,
    /** Started and not finished: at most one, but a damaged note shouldn't change what's done about it. */
    val begun: LinkedHashMap<String, String> = LinkedHashMap(),
    val verified: MutableSet<String> = HashSet(),
    val finished: MutableList<Pair<String, String>> = ArrayList(),
    var building: Pair<String, String>? = null,
    var unpacking: Unpacking? = null,
    val joined: MutableList<String> = ArrayList()
)

/**
 * What a killed job left, after tidying. The counts are what's true on the disk now, not what the note hoped.
 */
internal data class SettledJob(
    val kind: JobKind,
    val label: String,
    val folder: String?,
    val ran: Boolean,
    val total: Int,
    /** Whole and where it was going, or for a delete, gone from where it was. */
    val finished: Int,
    /** Half-made results that were cleaned away. */
    val partialsRemoved: Int,
    /** Whole at the destination, but with some or all of the original still there too. */
    val originalsLeft: List<String>,
    /** A half-made result that had to stay, because its original has gone, or it wouldn't delete. */
    val partialsKept: List<String>,
    /** Turned up at a new path and the index has to be told. */
    val outputs: List<String>,
    /** Left a path behind and the index has to be told. */
    val gone: List<String>,
    /** Moved, so a lock has to follow. */
    val moved: List<Pair<String, String>>,
    /** The file a compress finished making before the note could be removed. */
    val archiveMade: String? = null
)

/**
 * Works out what a job killed partway really did, and removes only what can't be mistaken for anything else.
 *
 * Nothing here ever removes an original. A half-made copy goes only while its original is still there, so the
 * worst this can do is make someone copy something again. An item whose copy was finished and read back but
 * whose original had started going is left exactly as found and reported, since a person can see what to do
 * with that better than a guess can.
 */
internal object JobRecovery {
    fun settle(record: JobRecord): SettledJob = when (record.kind) {
        JobKind.COPY, JobKind.MOVE, JobKind.ORGANIZE -> settleTransfer(record, move = record.kind != JobKind.COPY)
        JobKind.TRASH, JobKind.DELETE, JobKind.RESTORE, JobKind.EMPTY_BIN -> settleRemoval(record)
        JobKind.COMPRESS -> settleCompress(record)
        JobKind.EXTRACT -> settleExtract(record)
        // Nothing about the Vault's files is ever written down, so there's nothing to go on but that it happened.
        JobKind.VAULT -> SettledJob(
            kind = record.kind,
            label = record.label,
            folder = null,
            ran = record.ran,
            total = 0,
            finished = 0,
            partialsRemoved = 0,
            originalsLeft = emptyList(),
            partialsKept = emptyList(),
            outputs = emptyList(),
            gone = emptyList(),
            moved = emptyList()
        )
    }

    private fun settleTransfer(record: JobRecord, move: Boolean): SettledJob {
        val total = record.sources.size
        val outputs = ArrayList<String>()
        val gone = ArrayList<String>()
        val moved = ArrayList<Pair<String, String>>()
        var finished = 0
        var removed = 0
        val originalsLeft = ArrayList<String>()
        val partialsKept = ArrayList<String>()

        for ((source, dest) in record.finished) {
            if (!SafeFiles.existsAtAll(File(dest))) continue
            finished++
            outputs += dest
            if (move && source != dest) {
                gone += source
                moved += source to dest
            }
        }
        for ((source, dest) in record.begun) {
            val sourceFile = File(source)
            val destFile = File(dest)
            val sourceThere = SafeFiles.existsAtAll(sourceFile)
            val destThere = SafeFiles.existsAtAll(destFile)
            when {
                // Whole and checked. Whatever's left of the original is for the person to see, not for this to guess at.
                move && source in record.verified -> {
                    finished++
                    outputs += dest
                    if (sourceThere) {
                        originalsLeft += source
                    } else {
                        gone += source
                        moved += source to dest
                    }
                }
                // A rename is all or nothing, so finding it at the far end and not the near one means it happened.
                move && destThere && !sourceThere -> {
                    finished++
                    outputs += dest
                    gone += source
                    moved += source to dest
                }
                destThere && sourceThere -> {
                    // Nothing was at this name before the job took it, so all of it is this job's own.
                    if (SafeFiles.deleteTree(destFile)) removed++ else partialsKept += dest
                }
                destThere -> partialsKept += dest
            }
            destFile.parentFile?.let { clearPartsSince(it, record.startedAt) }
        }
        return SettledJob(
            kind = record.kind,
            label = record.label,
            folder = record.folder,
            ran = record.ran,
            total = total,
            finished = finished,
            partialsRemoved = removed,
            originalsLeft = originalsLeft,
            partialsKept = partialsKept,
            outputs = outputs,
            gone = gone,
            moved = moved
        )
    }

    /** A rename or a delete is all or nothing per file, so looking at the sources tells the whole story. */
    private fun settleRemoval(record: JobRecord): SettledJob {
        val gone = record.sources.filterNot { SafeFiles.existsAtAll(File(it)) }
        return SettledJob(
            kind = record.kind,
            label = record.label,
            folder = record.folder,
            ran = record.ran,
            total = record.sources.size,
            finished = gone.size,
            partialsRemoved = 0,
            originalsLeft = emptyList(),
            partialsKept = emptyList(),
            outputs = emptyList(),
            gone = gone,
            moved = emptyList()
        )
    }

    private fun settleCompress(record: JobRecord): SettledJob {
        val (part, final) = record.building ?: (null to null)
        var removed = 0
        var made: String? = null
        if (part != null) {
            if (SafeFiles.existsAtAll(File(part))) {
                if (SafeFiles.deleteTree(File(part))) removed++
            } else if (final != null && SafeFiles.existsAtAll(File(final))) {
                made = final
            }
            File(part).parentFile?.let { clearPartsSince(it, record.startedAt) }
        }
        return SettledJob(
            kind = record.kind,
            label = record.label,
            folder = record.folder,
            ran = record.ran,
            total = 1,
            finished = if (made != null) 1 else 0,
            partialsRemoved = removed,
            originalsLeft = emptyList(),
            partialsKept = emptyList(),
            outputs = listOfNotNull(made),
            gone = emptyList(),
            moved = emptyList(),
            archiveMade = made
        )
    }

    private fun settleExtract(record: JobRecord): SettledJob {
        val unpacking = record.unpacking
        var removed = 0
        val kept = ArrayList<String>()
        if (unpacking != null) {
            val dest = File(unpacking.dest)
            // The archive is untouched by any of this, so all of it can be made again.
            if (SafeFiles.existsAtAll(File(unpacking.archive)) || unpacking.archive.startsWith("content:")) {
                if (unpacking.staging != null) {
                    for (joined in record.joined) {
                        if (SafeFiles.deleteTree(File(joined))) removed++ else kept += joined
                    }
                    if (SafeFiles.existsAtAll(File(unpacking.staging)) && SafeFiles.deleteTree(File(unpacking.staging))) removed++
                } else if (unpacking.startedEmpty && SafeFiles.existsAtAll(dest)) {
                    if (SafeFiles.deleteTree(dest)) removed++ else kept += dest.path
                }
            } else if (unpacking.staging != null || unpacking.startedEmpty) {
                kept += dest.path
            }
        }
        return SettledJob(
            kind = record.kind,
            label = record.label,
            folder = record.folder,
            ran = record.ran,
            total = 1,
            finished = 0,
            partialsRemoved = removed,
            originalsLeft = emptyList(),
            partialsKept = kept,
            outputs = emptyList(),
            gone = emptyList(),
            moved = emptyList()
        )
    }

    /**
     * Temporary files this job made and never got to rename. Only ones from since it began, so a part another
     * save is using, or left by something else, isn't this job's to clear.
     */
    private fun clearPartsSince(folder: File, startedAt: Long) {
        folder.listFiles { _, name -> SafeFiles.isPartName(name) }?.forEach { part ->
            if (part.lastModified() >= startedAt - CLOCK_SLACK_MILLIS) SafeFiles.deleteTree(part)
        }
    }

    private const val CLOCK_SLACK_MILLIS = 2_000L
}
