package com.kobblo.gallery.file.explorer.transfer.send

import android.content.Context
import android.os.Environment
import java.io.File

/** Where everything that arrives goes, unless the person chose another folder in Settings. */
object ReceiveFolder {
    private const val DEFAULT_NAME = "KobbloReceived"

    fun resolve(context: Context): File {
        val custom = context.getSharedPreferences("kobblo_prefs", Context.MODE_PRIVATE)
            .getString("nearby_receive_path", null)
        if (!custom.isNullOrBlank()) {
            val dir = File(custom)
            if (dir.isDirectory || dir.mkdirs()) return dir
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DEFAULT_NAME)
        dir.mkdirs()
        return dir
    }
}
