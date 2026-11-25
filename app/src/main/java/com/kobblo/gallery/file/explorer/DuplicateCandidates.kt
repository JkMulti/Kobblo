package com.kobblo.gallery.file.explorer

/**
 * Files that might have a twin, in as little memory as the job allows: a path and a size each, and no
 * object per file. Only a size that two or more paths share is worth reading the files for.
 */
internal class DuplicateCandidates {
    private val paths = ArrayList<String>()
    private var sizes = LongArray(1024)

    fun add(path: String, size: Long) {
        if (paths.size == sizes.size) sizes = sizes.copyOf(sizes.size * 2)
        sizes[paths.size] = size
        paths.add(path)
    }

    /** Calls [block] once for each size at least two paths have, smallest first. */
    fun forEachSharedSize(block: (size: Long, paths: List<String>) -> Unit) {
        val order = paths.indices.sortedBy { sizes[it] }
        var from = 0
        while (from < order.size) {
            val size = sizes[order[from]]
            var to = from + 1
            while (to < order.size && sizes[order[to]] == size) to++
            if (to - from > 1) block(size, order.subList(from, to).map { paths[it] })
            from = to
        }
    }
}
