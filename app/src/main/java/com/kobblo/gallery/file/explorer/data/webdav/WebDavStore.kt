package com.kobblo.gallery.file.explorer.data.webdav

import android.content.Context
import com.kobblo.gallery.file.explorer.data.remote.SavedConnections

/** The saved WebDAV servers, Nextcloud among them. */
class WebDavStore(context: Context) :
    SavedConnections<WebDavConnection>(context, "webdav", WebDavConnection::class.java, WebDavConnection::id)
