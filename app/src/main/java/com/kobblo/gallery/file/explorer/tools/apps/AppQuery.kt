package com.kobblo.gallery.file.explorer.tools.apps

import java.text.Collator
import java.util.Locale

/** One installed app. Paths point at the APKs, so every action can work from this alone. */
internal data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val apkPath: String,
    val splitPaths: List<String>,
    val sizeBytes: Long,
    val installedAt: Long,
    val updatedAt: Long,
    val targetSdk: Int
) {
    val isSplit: Boolean get() = splitPaths.isNotEmpty()
    val allApkPaths: List<String> get() = listOf(apkPath) + splitPaths
}

internal enum class AppSort { NAME, SIZE, DATE }

internal object AppQuery {
    /** What the list shows for a filter, a search and a sort. */
    fun apply(
        apps: List<InstalledApp>,
        system: Boolean,
        search: String,
        sort: AppSort,
        locale: Locale = Locale.getDefault()
    ): List<InstalledApp> {
        val term = search.trim()
        val matching = apps.filter { app ->
            app.isSystem == system &&
                (term.isEmpty() || app.label.contains(term, ignoreCase = true) || app.packageName.contains(term, ignoreCase = true))
        }
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        val byName = Comparator<InstalledApp> { a, b -> collator.compare(a.label, b.label) }
            .thenBy { it.packageName }
        return when (sort) {
            AppSort.NAME -> matching.sortedWith(byName)
            AppSort.SIZE -> matching.sortedWith(compareByDescending<InstalledApp> { it.sizeBytes }.then(byName))
            AppSort.DATE -> matching.sortedWith(compareByDescending<InstalledApp> { it.updatedAt }.then(byName))
        }
    }

    /**
     * A backup's file name: readable, safe on any file system, and telling which version it holds.
     * A split app becomes one bundle, so it takes the bundle's extension.
     */
    fun backupFileName(app: InstalledApp): String {
        val name = sanitize(app.label).ifEmpty { app.packageName }
        val version = sanitize(app.versionName)
        val stem = if (version.isEmpty()) name else "${name}_$version"
        return if (app.isSplit) "$stem.apks" else "$stem.apk"
    }

    private fun sanitize(text: String): String =
        text.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ')
            .take(80)
}
