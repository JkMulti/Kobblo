package com.kobblo.gallery.file.explorer.tools.picker

/** Which files a requesting app asked for, from the type and extras of its intent. */
class MimeFilter(patterns: List<String>) {
    private val patterns: List<String> = patterns.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    val acceptsAll: Boolean = this.patterns.isEmpty() || this.patterns.any { it == "*/*" || it == "*" }

    fun accepts(mime: String?): Boolean {
        if (acceptsAll) return true
        val type = mime?.lowercase() ?: return false
        return patterns.any { pattern ->
            when {
                pattern.endsWith("/*") -> type.startsWith(pattern.dropLast(1))
                else -> type == pattern
            }
        }
    }

    /** True when only photos and videos can match, so a grid suits them better than a list. */
    val isVisualOnly: Boolean = !acceptsAll && this.patterns.all { it.startsWith("image/") || it.startsWith("video/") }

    companion object {
        /**
         * @param type the intent's type
         * @param extraMimeTypes EXTRA_MIME_TYPES, which narrows a broad type
         * @param dataUri the intent's data, which is how ACTION_PICK names a media collection
         */
        fun from(type: String?, extraMimeTypes: List<String>?, dataUri: String?): MimeFilter {
            if (!extraMimeTypes.isNullOrEmpty()) return MimeFilter(extraMimeTypes)
            val fromType = type?.let(::normalize)
            if (fromType != null) return MimeFilter(listOf(fromType))
            val fromData = dataUri?.let { uri ->
                when {
                    "/images/" in uri -> "image/*"
                    "/video/" in uri -> "video/*"
                    "/audio/" in uri -> "audio/*"
                    else -> null
                }
            }
            return MimeFilter(listOfNotNull(fromData))
        }

        /** ACTION_PICK may use a cursor type that only names a collection. */
        private fun normalize(type: String): String = when (type.lowercase()) {
            "vnd.android.cursor.dir/image" -> "image/*"
            "vnd.android.cursor.dir/video" -> "video/*"
            "vnd.android.cursor.dir/audio" -> "audio/*"
            else -> type
        }
    }
}
