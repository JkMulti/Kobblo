package com.kobblo.gallery.file.explorer.smart

import android.app.Application
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.kobblo.gallery.file.explorer.MainActivity
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.fileops.Locks
import com.subhashh.lumen.ai.AiNotifications
import com.subhashh.lumen.ai.AiSettings
import com.subhashh.lumen.ai.IndexSchedule
import com.subhashh.lumen.ai.LumenAi
import com.subhashh.lumen.ai.LumenAiEngine
import com.subhashh.lumen.ai.LumenAiHost
import com.subhashh.lumen.ai.MediaRef
import com.subhashh.lumen.ai.ModelCatalog
import com.subhashh.lumen.ai.ModelPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finding photos by what's in them, reading the text in photos, and putting each person's photos
 * together. The work is done on the phone: it only goes online to download a model the person
 * turned on, and nothing it finds
 * leaves the phone. It needs Android 11, so below that these features don't exist and nothing here
 * calls into it.
 */
object SmartFeatures {
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** The features offered, in the order they're listed. Subtitles and cutouts aren't part of the app. */
    val Offered = listOf(ModelPack.Search, ModelPack.Text, ModelPack.Faces)

    /** Called first thing in every process, since the library's jobs and its GPU check look it up. */
    fun install(app: Application) {
        if (!isSupported) return
        LumenAi.install(SmartHost(app))
    }

    /** Keeps indexing scheduled. It does nothing until a model has been downloaded. */
    fun keepIndexing(context: Context) {
        if (isSupported) LumenAi.indexing.start(context)
    }

    /** Whether any feature can be used yet. */
    fun anyReady(context: Context): Boolean =
        isSupported && Offered.any { LumenAi.models.status(context, it) !is com.subhashh.lumen.ai.FeatureStatus.NotDownloaded }

    fun isReady(context: Context, pack: ModelPack): Boolean =
        isSupported && LumenAi.models.status(context, pack).let {
            it is com.subhashh.lumen.ai.FeatureStatus.Ready || it is com.subhashh.lumen.ai.FeatureStatus.UpdateAvailable
        }

    /** What's left to download for [pack], at the search quality chosen now. Nothing once it's all here. */
    fun downloadBytes(context: Context, pack: ModelPack): Long =
        if (isSupported) LumenAi.models.size(context, pack) else 0L

    /** The whole download for search at [quality], for showing both choices before one is made. */
    @OptIn(LumenAiEngine::class)
    fun searchBytes(context: Context, quality: SearchQuality): Long =
        if (isSupported) ModelCatalog.byId(quality.id).bytes(context) else 0L
}

/** How good Smart Search is, against how much it downloads. */
enum class SearchQuality(val id: String) {
    /** SigLIP 2: better results. */
    Accurate("siglip2-b16"),

    /** CLIP ViT-B/32: smaller and quicker, for phones short of memory or space. */
    Fast("clip-vit-b32");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Accurate

        /** Accurate on a phone with plenty of memory, Fast below that. */
        fun recommended(context: Context): SearchQuality {
            val memory = android.app.ActivityManager.MemoryInfo()
            context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
            return if (memory.totalMem >= 7_000_000_000L) Accurate else Fast
        }
    }
}

/** What the person chose for Smart Features. */
class SmartPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("smart_features", Context.MODE_PRIVATE)

    var searchQuality: SearchQuality
        get() = SearchQuality.of(prefs.getString(KeySearch, null))
        set(value) = prefs.edit { putString(KeySearch, value.id) }

    /** Models are hundreds of megabytes, so they wait for Wi-Fi unless told otherwise. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KeyWifi, true)
        set(value) = prefs.edit { putBoolean(KeyWifi, value) }

    /** Index overnight, plugged in or not, instead of only while charging. */
    var overnight: Boolean
        get() = prefs.getBoolean(KeyOvernight, false)
        set(value) = prefs.edit { putBoolean(KeyOvernight, value) }

    private companion object {
        const val KeySearch = "search_model"
        const val KeyWifi = "wifi_only"
        const val KeyOvernight = "overnight"
    }
}

/** What the smart features library needs from the app: its library, the person's choices, and how its notifications look. */
@RequiresApi(Build.VERSION_CODES.R)
private class SmartHost(app: Application) : LumenAiHost {
    private val prefs = SmartPrefs(app)

    /**
     * The library as the gallery shows it. The bin, hidden folders and locked files stay out, so
     * nothing locked can turn up in a search or among the people.
     */
    override suspend fun library(context: Context): List<MediaRef> = withContext(Dispatchers.IO) {
        val locked = Locks.forLists(context)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val items = ArrayList<MediaRef>()
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, selection, args,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(7) ?: continue
                    if (path.contains("/.") || locked.covers(path)) continue
                    val id = c.getLong(0)
                    val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val modified = c.getLong(4)
                    val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    items += MediaRef(
                        id = id,
                        uri = ContentUris.withAppendedId(base, id),
                        mimeType = c.getString(2) ?: "",
                        isVideo = video,
                        takenAt = c.getLong(3).takeIf { it > 0 } ?: (modified * 1000),
                        durationMs = c.getLong(5),
                        modified = modified,
                        name = c.getString(6) ?: ""
                    )
                }
            }
        } catch (_: SecurityException) {
            // No access to storage yet, so nothing to index until there is.
        }
        items
    }

    override val settings = object : AiSettings {
        override val searchModelId get() = prefs.searchQuality.id
        override val videoDetailPx = 1280
        override val indexOnBattery get() = prefs.overnight
        override val indexSchedule get() = if (prefs.overnight) IndexSchedule(startMinute = 60, endMinute = 420) else null
    }

    override val notifications = object : AiNotifications {
        override val smallIcon = R.drawable.ic_stat_smart
        override fun contentIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    }
}
