package com.kobblo.gallery.file.explorer.viewer.pdf

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileInputStream
import java.io.FileOutputStream

/** Hands the file as it is to the system's print dialog, which brings its own preview and printers. */
internal object PdfPrint {
    /** [openFile] gives the bytes to print. It runs off the main thread, as it may be a large file. */
    fun print(context: Context, name: String, pageCount: Int, openFile: () -> ParcelFileDescriptor): Boolean {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return false
        return try {
            manager.print(name, Adapter(name, pageCount, openFile), PrintAttributes.Builder().build())
            true
        } catch (_: Exception) {
            false
        }
    }

    private class Adapter(
        private val name: String,
        private val pageCount: Int,
        private val openFile: () -> ParcelFileDescriptor
    ) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pageCount)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            Thread {
                try {
                    openFile().use { source ->
                        FileInputStream(source.fileDescriptor).use { input ->
                            FileOutputStream(destination.fileDescriptor).use { output ->
                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    if (cancellationSignal?.isCanceled == true) {
                                        callback.onWriteCancelled()
                                        return@Thread
                                    }
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (_: Exception) {
                    callback.onWriteFailed(null)
                }
            }.start()
        }
    }
}
