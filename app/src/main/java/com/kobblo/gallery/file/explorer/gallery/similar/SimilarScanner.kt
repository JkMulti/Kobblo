package com.kobblo.gallery.file.explorer.gallery.similar

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.fileops.DuplicateFinder
import com.kobblo.gallery.file.explorer.gallery.MediaFacts
import com.kobblo.gallery.file.explorer.gallery.MediaIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Looks through the library for duplicates in the background. The work lives here rather than with the
 * screen, so leaving to do something else doesn't lose it. Hashes are saved as they're made, so the
 * next scan only has to look at what's new, and an interrupted one picks up where it stopped.
 */
object SimilarScanner {
    sealed interface State {
        data object Idle : State
        data class Scanning(val done: Int, val total: Int) : State
        data class Done(val groups: List<DuplicateGroup>, val scanned: Int) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hashing = Dispatchers.IO.limitedParallelism(3)
    private var job: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            _state.value = State.Scanning(0, 0)
            val result = scan(app)
            _state.value = State.Done(result.first, result.second)
        }
    }

    fun rescan(context: Context) {
        job?.cancel()
        job = null
        start(context)
    }

    fun update(group: DuplicateGroup, next: DuplicateGroup) {
        _state.update { current ->
            if (current is State.Done) current.copy(groups = current.groups.map { if (it === group) next else it }) else current
        }
    }

    /** Takes photos just deleted out of the results, and drops any group left with nothing to choose between. */
    fun removeDeleted(ids: Set<Int>) {
        _state.update { current ->
            if (current !is State.Done) return@update current
            val remaining = current.groups.mapNotNull { group ->
                val members = group.members.filterNot { it.id in ids }
                if (members.size < 2) null else group.copy(members = members, remove = group.remove - ids)
            }
            current.copy(groups = remaining)
        }
    }

    private suspend fun scan(context: Context): Pair<List<DuplicateGroup>, Int> {
        // Locked files stay out of it: suggesting them would show what's meant to be private.
        val files = withContext(Dispatchers.IO) {
            FileRepository(context).getGalleryMedia(showHidden = false)
        }.filter { it.type == "image" && !it.isLocked && !it.isHidden }
        val facts = withContext(Dispatchers.IO) { MediaIndex.get(context).readFactsNow() }
        val total = files.size
        _state.value = State.Scanning(0, total)

        val cache = PrintCache(File(context.filesDir, "gallery/prints.bin"))
        cache.load()

        val prints = arrayOfNulls<PhotoPrint>(total)
        var done = 0
        var unsaved = 0
        for (chunk in files.indices.chunked(24)) {
            coroutineScope {
                chunk.map { index ->
                    async(hashing) {
                        val file = files[index]
                        prints[index] = cache.get(file) ?: printOf(context, file, facts[file.id])?.also { cache.put(file, it) }
                    }
                }.awaitAll()
            }
            done += chunk.size
            unsaved += chunk.size
            _state.value = State.Scanning(done, total)
            if (unsaved >= 1000) {
                withContext(Dispatchers.IO) { cache.save() }
                unsaved = 0
            }
        }
        withContext(Dispatchers.IO) { cache.save() }

        // Every byte of each file, which is what makes "exact" true. Rescanning or leaving stops it between reads.
        val sharing = filesSharingSize(files)
        val contents = arrayOfNulls<String>(total)
        withContext(Dispatchers.IO) {
            val job = coroutineContext[Job]
            files.forEachIndexed { index, file ->
                if (file.id in sharing) contents[index] = DuplicateFinder.checksum(File(file.path)) { job?.ensureActive() }
            }
        }

        val groups = groupPhotos(prints.asList(), contents.asList())
        val review = buildReview(files, groups, contents.asList()) { file ->
            val fact = facts[file.id]
            (fact?.width ?: 0) to (fact?.height ?: 0)
        }
        return review to total
    }

    private fun printOf(context: Context, file: FileItem, facts: MediaFacts?): PhotoPrint? {
        val small = smallBitmap(context, file) ?: return null
        return try {
            val sample = Bitmap.createScaledBitmap(small, DifferenceHash.SampleWidth, DifferenceHash.SampleHeight, true)
            val pixels = IntArray(DifferenceHash.SampleWidth * DifferenceHash.SampleHeight)
            sample.getPixels(pixels, 0, DifferenceHash.SampleWidth, 0, 0, DifferenceHash.SampleWidth, DifferenceHash.SampleHeight)
            if (sample !== small) sample.recycle()
            val luma = IntArray(pixels.size)
            var red = 0
            var green = 0
            var blue = 0
            for (i in pixels.indices) {
                val r = (pixels[i] shr 16) and 255
                val g = (pixels[i] shr 8) and 255
                val b = pixels[i] and 255
                luma[i] = (r * 299 + g * 587 + b * 114) / 1000
                red += r
                green += g
                blue += b
            }
            PhotoPrint(
                hash = DifferenceHash.of(luma),
                width = facts?.width ?: 0,
                height = facts?.height ?: 0,
                red = red / pixels.size,
                green = green / pixels.size,
                blue = blue / pixels.size
            )
        } finally {
            small.recycle()
        }
    }

    /** The system's thumbnail when it has one, since most of a scan's time goes on reading pictures. */
    private fun smallBitmap(context: Context, file: FileItem): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && file.id > 0) {
            try {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, file.id.toLong())
                return context.contentResolver.loadThumbnail(uri, Size(64, 64), null)
            } catch (e: Exception) {
                // Falls through to reading the file itself.
            }
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 64 && bounds.outHeight / (sample * 2) >= 64) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** A print stays good as long as the file's id and size do. */
    private class PrintCache(private val file: File) {
        private class Entry(val sizeBytes: Long, val print: PhotoPrint)

        private val entries = java.util.concurrent.ConcurrentHashMap<Int, Entry>()

        fun get(item: FileItem): PhotoPrint? = entries[item.id]?.takeIf { it.sizeBytes == item.sizeBytes }?.print

        fun put(item: FileItem, print: PhotoPrint) {
            entries[item.id] = Entry(item.sizeBytes, print)
        }

        fun load() {
            try {
                if (!file.exists()) return
                DataInputStream(file.inputStream().buffered()).use { input ->
                    repeat(input.readInt()) {
                        val id = input.readInt()
                        val size = input.readLong()
                        val hash = input.readLong()
                        val width = input.readInt()
                        val height = input.readInt()
                        val red = input.readUnsignedByte()
                        val green = input.readUnsignedByte()
                        val blue = input.readUnsignedByte()
                        entries[id] = Entry(size, PhotoPrint(hash, width, height, red, green, blue))
                    }
                }
            } catch (e: Exception) {
                // A damaged cache only costs a slower scan.
                entries.clear()
            }
        }

        fun save() {
            try {
                file.parentFile?.mkdirs()
                val temp = File(file.path + ".tmp")
                DataOutputStream(temp.outputStream().buffered()).use { output ->
                    val snapshot = entries.entries.toList()
                    output.writeInt(snapshot.size)
                    for ((id, entry) in snapshot) {
                        output.writeInt(id)
                        output.writeLong(entry.sizeBytes)
                        output.writeLong(entry.print.hash)
                        output.writeInt(entry.print.width)
                        output.writeInt(entry.print.height)
                        output.writeByte(entry.print.red)
                        output.writeByte(entry.print.green)
                        output.writeByte(entry.print.blue)
                    }
                }
                temp.renameTo(file)
            } catch (e: Exception) {
                android.util.Log.e("SimilarScanner", "Couldn't save the print cache", e)
            }
        }
    }
}
