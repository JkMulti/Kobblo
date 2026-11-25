package com.kobblo.gallery.file.explorer.explorer

/** One step of a path bar: what it reads, and where tapping it goes. */
data class PathSegment(val name: String, val path: String)

/** Paths as people read and type them. Written without Android types so it can be tested on the JVM. */
object PathTools {

    /** The folders from just below [root] down to [location], in order. */
    fun segments(location: String, root: String): List<PathSegment> {
        if (location == root || !location.startsWith("$root/")) return emptyList()
        val found = ArrayList<PathSegment>()
        var path = root
        for (name in location.substring(root.length + 1).split('/')) {
            if (name.isEmpty()) continue
            path = "$path/$name"
            found.add(PathSegment(name, path))
        }
        return found
    }

    /**
     * Turns what someone typed into an absolute path. `/sdcard` is how most guides spell the phone's
     * storage, and anything without a leading slash starts from the folder they're in, or from the top
     * of storage with `~`. Null when there's nothing to go to.
     */
    fun resolve(input: String, currentFolder: String?, primaryRoot: String): String? {
        val typed = input.trim().trimEnd('/').ifEmpty { if (input.trim().startsWith("/")) "/" else return null }
        val absolute = when {
            typed == "/sdcard" || typed.startsWith("/sdcard/") -> primaryRoot + typed.removePrefix("/sdcard")
            typed == "/storage/self/primary" || typed.startsWith("/storage/self/primary/") -> primaryRoot + typed.removePrefix("/storage/self/primary")
            typed.startsWith("/") -> typed
            typed == "~" -> primaryRoot
            typed.startsWith("~/") -> primaryRoot + typed.removePrefix("~")
            currentFolder != null -> "$currentFolder/$typed"
            else -> "$primaryRoot/$typed"
        }
        return normalize(absolute)
    }

    /** Settles `.` and `..`, so what's opened and what the path bar shows are the same place. */
    fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.add(part)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /**
     * The folder a search result lives in, as a line short enough to fit beside its name: `Download › Work`.
     * [volumes] pairs each volume's root with its name, and the phone's own storage goes first, so its
     * name only shows for a file at its very top. A card's name always shows, since it's the part that
     * tells two copies apart.
     */
    fun folderLine(path: String, volumes: List<Pair<String, String>>): String {
        val folder = path.substringBeforeLast('/', "")
        val index = volumes.indexOfFirst { (root, _) -> folder == root || folder.startsWith("$root/") }
        if (index < 0) return folder
        val (root, name) = volumes[index]
        val inside = folder.removePrefix(root).trim('/').split('/').filter { it.isNotEmpty() }
        return when {
            inside.isEmpty() -> name
            index == 0 -> inside.joinToString(" › ")
            else -> (listOf(name) + inside).joinToString(" › ")
        }
    }
}
