package com.kobblo.gallery.file.explorer.ui.root

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

// Letting other apps read a file. They're lent a link to it rather than its path, which they may not
// be allowed to open.

/** A link to [file] that another app can be given leave to read. */
internal fun Context.lend(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

/** The same for a file known by its path. One that came from another app already is a link. */
internal fun Context.lend(path: String): Uri =
    if (path.startsWith("content://")) Uri.parse(path) else lend(File(path))

/** An intent to look at [uri] in another app. */
internal fun viewIntent(uri: Uri, mime: String): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

/** Offers one file to whatever app the person picks, under [title]. */
internal fun Context.share(uri: Uri, title: String) {
    val parcel = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)
    offer(parcel, title)
}

/** Offers several files at once, under [title]. */
internal fun Context.shareAll(uris: List<Uri>, title: String) {
    val parcel = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    offer(parcel, title)
}

private fun Context.offer(parcel: Intent, title: String) {
    parcel.setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(parcel, title))
}
