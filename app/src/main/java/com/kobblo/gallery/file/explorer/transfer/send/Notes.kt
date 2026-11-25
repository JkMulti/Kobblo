package com.kobblo.gallery.file.explorer.transfer.send

import android.content.Context
import com.kobblo.gallery.file.explorer.FileItem
import java.io.File
import java.util.UUID

/**
 * A note or link to send. LocalSend carries one as a text file with its whole content in the preview,
 * which a receiver shows instead of saving. The file is here for any receiver that asks for it anyway,
 * and for when a note goes along with other things, where it arrives as `Note.txt`.
 */
object Notes {
    /** Marks a staged [FileItem] as a note, whose path is this phone's copy of the text. */
    const val KIND = "note"
    const val FILE_NAME = "Note.txt"
    private const val DIR = "send_notes"
    private const val LABEL_CHARS = 48

    /** Anything longer is more than a note, and travels as an ordinary text file. */
    const val MAX_PREVIEW_BYTES = 128 * 1024L

    fun write(context: Context, text: String): FileItem? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        return try {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.txt").apply { writeText(clean) }
            TransferFiles.fromFile(context, file, label(clean)).copy(kind = KIND, type = "doc")
        } catch (e: java.io.IOException) {
            null
        }
    }

    /** The first line, short enough to read in a chip. */
    const val MAX_HISTORY_CHARS = 8_000

    fun label(text: String): String {
        val line = text.lineSequence().first().trim()
        return if (line.length <= LABEL_CHARS) line else line.take(LABEL_CHARS - 1).trimEnd() + "…"
    }

    /** Only when nothing is chosen any more. */
    fun clear(context: Context) {
        File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
    }
}
