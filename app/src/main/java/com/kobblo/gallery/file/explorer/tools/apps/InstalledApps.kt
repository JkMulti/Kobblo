package com.kobblo.gallery.file.explorer.tools.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The installed apps, kept between visits so the list appears at once and refreshes behind it.
 * Labels are the slow part of asking PackageManager, so they load across a few threads.
 */
internal object InstalledApps {
    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)
    val apps: StateFlow<List<InstalledApp>?> = _apps.asStateFlow()

    private val refreshing = Mutex()

    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        if (!refreshing.tryLock()) return
        try {
            _apps.value = load(app)
        } finally {
            refreshing.unlock()
        }
    }

    private suspend fun load(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        coroutineScope {
            packages.chunked((packages.size / 4).coerceAtLeast(1)).map { chunk ->
                async { chunk.mapNotNull { toInstalledApp(pm, it) } }
            }.awaitAll().flatten()
        }
    }

    private fun toInstalledApp(pm: PackageManager, info: PackageInfo): InstalledApp? {
        val appInfo = info.applicationInfo ?: return null
        val base = appInfo.sourceDir ?: appInfo.publicSourceDir ?: return null
        val splits = appInfo.splitSourceDirs?.toList().orEmpty()
        val size = (listOf(base) + splits).sumOf { File(it).length() }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return InstalledApp(
            packageName = info.packageName,
            label = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { info.packageName },
            versionName = info.versionName.orEmpty(),
            versionCode = versionCode,
            isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            apkPath = base,
            splitPaths = splits,
            sizeBytes = size,
            installedAt = info.firstInstallTime,
            updatedAt = info.lastUpdateTime,
            targetSdk = appInfo.targetSdkVersion
        )
    }
}

private val iconCache = LruCache<String, ImageBitmap>(256)

/** An app's icon, drawn off the main thread and kept for when its row scrolls back on. */
@Composable
internal fun rememberAppIcon(app: InstalledApp, sizePx: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    val key = "${app.packageName}:${app.updatedAt}"
    return produceState(initialValue = iconCache.get(key), key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(app.packageName)
                    .toBitmap(sizePx, sizePx)
                    .asImageBitmap()
                    .also { iconCache.put(key, it) }
            } catch (_: Exception) {
                null
            }
        }
    }
}
