package com.kobblo.gallery.file.explorer.data.smb

import android.content.Context
import com.kobblo.gallery.file.explorer.data.remote.SavedConnections

/** The saved SMB shares. */
class SmbStore(context: Context) :
    SavedConnections<SmbConnection>(context, "smb", SmbConnection::class.java, SmbConnection::id)
