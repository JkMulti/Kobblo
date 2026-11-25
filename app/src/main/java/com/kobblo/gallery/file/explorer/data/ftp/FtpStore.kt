package com.kobblo.gallery.file.explorer.data.ftp

import android.content.Context
import com.kobblo.gallery.file.explorer.data.remote.SavedConnections

/** The saved FTP and FTPS connections. */
class FtpStore(context: Context) :
    SavedConnections<FtpConnection>(context, "ftp", FtpConnection::class.java, FtpConnection::id)
