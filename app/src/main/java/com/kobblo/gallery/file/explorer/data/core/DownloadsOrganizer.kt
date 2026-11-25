package com.kobblo.gallery.file.explorer.data.core

import com.kobblo.gallery.file.explorer.viewer.FileKinds

/**
 * Which folder Organize Downloads files something under. Both what's counted and what's moved come
 * from here, so nothing is promised that won't happen. Kinds with no folder, such as archives, stay.
 */
object DownloadsOrganizer {
    enum class Group { Docs, Images, Apps, Music, Videos }

    fun groupOf(name: String): Group? = when (FileKinds.typeOf(name)) {
        "doc" -> Group.Docs
        "image" -> Group.Images
        "apk" -> Group.Apps
        "audio" -> Group.Music
        "video" -> Group.Videos
        else -> null
    }
}
