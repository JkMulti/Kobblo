package com.kobblo.gallery.file.explorer.fileops

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.util.Base64

/** One thing in the Bin. [originalPath] is null only for a file whose record was lost and whose name can't say. */
internal data class BinEntry(
    val encodedName: String,
    val originalPath: String?,
    val deletedAt: Long,
    val originalName: String,
    val isDirectory: Boolean,
    /** -1 when the entry was written before sizes were kept. */
    val sizeBytes: Long = -1L
)

/**
 * The names files take inside the Bin. A name used to be the base64 of the whole path, which fails on a
 * path past about 190 bytes and can't hold two files deleted from one path. Such a name is still the best
 * kind while it fits, because even with every record gone it says where the file came from. A longer path
 * or a clash gets a random name that keeps the end of the file's own.
 */
internal object BinNames {
    private const val MaxSelfDescribing = 200
    private const val RandomPrefix = "bin-"
    private val Unsafe = Regex("[^A-Za-z0-9._-]")

    fun selfDescribing(path: String): String =
        Base64.getUrlEncoder().encodeToString(path.toByteArray(Charsets.UTF_8))

    /** The path inside a self-describing name, or null for a name that isn't one. */
    fun pathIn(name: String): String? {
        if (name.startsWith(RandomPrefix)) return null
        return try {
            val bytes = Base64.getUrlDecoder().decode(name)
            val path = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            path.takeIf { it.startsWith("/") && selfDescribing(it) == name }
        } catch (_: Exception) {
            null
        }
    }

    /** What to call a file known only by its name in the Bin. */
    fun displayNameOf(name: String): String =
        if (name.startsWith(RandomPrefix)) name.removePrefix(RandomPrefix).substringAfter('-', name) else name

    /**
     * A name for [path] that [isTaken] doesn't have. [isTaken] must ignore case, since shared storage does,
     * and two different paths can differ in nothing else once encoded.
     */
    fun allocate(path: String, random: () -> Long, isTaken: (String) -> Boolean): String {
        val plain = selfDescribing(path)
        if (plain.length <= MaxSelfDescribing && !isTaken(plain)) return plain
        val tail = path.substringAfterLast('/').replace(Unsafe, "_").takeLast(MaxSelfDescribing - 24)
        while (true) {
            val candidate = RandomPrefix + java.lang.Long.toHexString(random()) + "-" + tail
            if (!isTaken(candidate)) return candidate
        }
    }
}

/** Reads and writes the Bin's list. The format is what it always was, plus a size, so older entries read as they are. */
internal object BinCodec {
    class Decoded(val entries: List<BinEntry>, val damaged: Boolean)

    fun encode(entries: Collection<BinEntry>): String {
        val out = StringWriter()
        JsonWriter(out).use { json ->
            json.beginArray()
            entries.forEach { writeEntry(json, it) }
            json.endArray()
        }
        return out.toString()
    }

    private fun writeEntry(json: JsonWriter, entry: BinEntry) {
        json.beginObject()
        json.name("encodedName").value(entry.encodedName)
        if (entry.originalPath != null) json.name("originalPath").value(entry.originalPath)
        json.name("deletedAt").value(entry.deletedAt)
        json.name("originalName").value(entry.originalName)
        json.name("isDirectory").value(entry.isDirectory)
        if (entry.sizeBytes >= 0L) json.name("sizeBytes").value(entry.sizeBytes)
        json.endObject()
    }

    /**
     * Everything readable in [text]. Older versions wrote this file in place, so a kill partway leaves the
     * start of it. The entries before the damage are still good, and the rest are found by their files.
     */
    fun decode(text: String, now: Long): Decoded {
        val entries = ArrayList<BinEntry>()
        var damaged = false
        try {
            JsonReader(StringReader(text)).use { json ->
                json.beginArray()
                while (json.hasNext()) readEntry(json, now)?.let(entries::add)
                json.endArray()
            }
        } catch (_: Exception) {
            damaged = true
        }
        return Decoded(entries, damaged)
    }

    /** One line of the journal, which is one entry as JSON. */
    fun encodeLine(entry: BinEntry): String {
        val out = StringWriter()
        JsonWriter(out).use { writeEntry(it, entry) }
        return out.toString()
    }

    fun decodeLine(line: String, now: Long): BinEntry? = try {
        JsonReader(StringReader(line)).use { readEntry(it, now) }
    } catch (_: Exception) {
        null
    }

    private fun readEntry(json: JsonReader, now: Long): BinEntry? {
        var name: String? = null
        var path: String? = null
        var deletedAt = now
        var originalName: String? = null
        var isDirectory = false
        var size = -1L
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "encodedName" -> name = json.nextString()
                "originalPath" -> if (json.peek() == JsonToken.NULL) json.nextNull() else path = json.nextString()
                "deletedAt" -> deletedAt = json.nextLong()
                "originalName" -> originalName = json.nextString()
                "isDirectory" -> isDirectory = json.nextBoolean()
                "sizeBytes" -> size = json.nextLong()
                else -> json.skipValue()
            }
        }
        json.endObject()
        val encoded = name ?: return null
        return BinEntry(
            encodedName = encoded,
            originalPath = path,
            deletedAt = deletedAt,
            originalName = originalName ?: path?.substringAfterLast('/') ?: BinNames.displayNameOf(encoded),
            isDirectory = isDirectory,
            sizeBytes = size
        )
    }
}

/**
 * The Bin's records on disk. Three promises:
 *
 * - **Nothing is ever without a record.** Before a batch moves, what's about to happen is written to a
 *   journal and flushed. A kill partway leaves files in the Bin that the journal explains.
 * - **The list is never half-written.** It's replaced whole with a temporary file and a rename.
 * - **A file in the Bin always shows.** Whatever the records say, the folder itself decides what's there. A
 *   file with no record is listed anyway, restorable and deletable, under what its name can tell.
 *
 * Not thread-safe. [RecycleBin] holds the lock.
 */
internal class BinStore(val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    val registryFile = File(dir, REGISTRY)
    private val journalFile = File(dir, JOURNAL)
    private val damagedFile = File(dir, DAMAGED)

    fun fileOf(entry: BinEntry): File = File(dir, entry.encodedName)

    /** What the folder and the records come to. [changed] means the list on disk no longer says this. */
    class Contents(val entries: LinkedHashMap<String, BinEntry>, val changed: Boolean)

    /**
     * What's in the Bin, or null if the folder can't be listed. That's different from empty: without storage
     * access nothing can be seen, and concluding the files are gone would throw away the record of them.
     */
    fun load(): Contents? {
        val listed = dir.list()
        if (listed == null) return if (dir.exists()) null else Contents(LinkedHashMap(), false)
        val present = HashSet<String>(listed.size * 2)
        for (name in listed) {
            when {
                name in RESERVED -> Unit
                // Left by a copy that a kill interrupted. The whole file is still at its source.
                SafeFiles.isPartName(name) || name == OLD_TEMP -> SafeFiles.deleteTree(File(dir, name))
                else -> present.add(name)
            }
        }
        val entries = LinkedHashMap<String, BinEntry>(present.size * 2)
        val time = now()
        var changed = false
        if (registryFile.exists()) {
            val text = try {
                registryFile.readText()
            } catch (e: IOException) {
                // Unreadable right now says nothing about the files, and they're listed anyway.
                null
            }
            if (text != null) {
                val decoded = BinCodec.decode(text, time)
                if (decoded.damaged) {
                    keepDamaged()
                    changed = true
                }
                for (entry in decoded.entries) {
                    if (entry.encodedName in present) entries[entry.encodedName] = entry else changed = true
                }
            }
        }
        readJournal(time).forEach { entry ->
            if (entry.encodedName in present && entry.encodedName !in entries) {
                entries[entry.encodedName] = entry
                changed = true
            }
        }
        for (name in present) {
            if (name !in entries) {
                entries[name] = orphan(name, time)
                changed = true
            }
        }
        return Contents(entries, changed)
    }

    /**
     * Found with no record. It's dated now, not by the file's own date, which a rename keeps: a photo taken
     * last year would otherwise be past its time and deleted the moment the Bin next opened.
     */
    private fun orphan(name: String, time: Long): BinEntry {
        val path = BinNames.pathIn(name)
        val file = File(dir, name)
        return BinEntry(
            encodedName = name,
            originalPath = path,
            deletedAt = time,
            originalName = path?.substringAfterLast('/') ?: BinNames.displayNameOf(name),
            isDirectory = file.isDirectory,
            sizeBytes = -1L
        )
    }

    private fun keepDamaged() {
        try {
            registryFile.copyTo(damagedFile, overwrite = true)
        } catch (_: IOException) {
        }
    }

    /** Writes down what's about to move. Flushed, because the whole point is to outlive what comes next. */
    fun writeJournal(entries: Collection<BinEntry>) {
        FileOutputStream(journalFile, true).use { stream ->
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            for (entry in entries) {
                writer.write(BinCodec.encodeLine(entry))
                writer.write("\n")
            }
            writer.flush()
            stream.fd.sync()
        }
    }

    private fun readJournal(time: Long): List<BinEntry> {
        if (!journalFile.exists()) return emptyList()
        return try {
            journalFile.readLines(Charsets.UTF_8).mapNotNull { BinCodec.decodeLine(it, time) }
        } catch (_: IOException) {
            emptyList()
        }
    }

    /** Replaces the list whole, then retires the journal, whose work is now in the list. */
    fun save(entries: Collection<BinEntry>) {
        SafeFiles.writeAtomically(registryFile, BinCodec.encode(entries))
        journalFile.delete()
    }

    companion object {
        const val REGISTRY = "trash_registry.json"
        const val JOURNAL = "trash_journal.jsonl"
        const val DAMAGED = "trash_registry.damaged"
        const val NO_MEDIA = ".nomedia"
        /** What an earlier build wrote the list to first. Never anyone's file. */
        const val OLD_TEMP = "trash_registry.json.tmp"
        val RESERVED = setOf(REGISTRY, JOURNAL, DAMAGED, NO_MEDIA)
    }
}
