package com.kobblo.gallery.file.explorer.gallery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.widget.RemoteViews
import com.kobblo.gallery.file.explorer.MainActivity
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.gallery.isScreenshotFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/** Locked files, hidden files and anything in a hidden folder must never reach a home screen. */
fun isWidgetEligible(path: String, locked: Set<String>): Boolean =
    isWidgetEligible(path, com.kobblo.gallery.file.explorer.fileops.LockedPaths(locked))

internal fun isWidgetEligible(path: String, locked: com.kobblo.gallery.file.explorer.fileops.LockedPaths): Boolean =
    !path.contains("/.") && !locked.covers(path)

/** A place on the home screen for one photo at a time, changing about every half hour. Nothing else on it. */
class PhotoWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAsync(context, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // A new size needs a new crop, but not a new photo.
        refreshAsync(context, intArrayOf(id), keepPhoto = true)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val edit = shownPrefs(context).edit()
        ids.forEach { edit.remove(keyFor(it)) }
        edit.apply()
    }

    private fun refreshAsync(context: Context, ids: IntArray, keepPhoto: Boolean = false) {
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                // A receiver gets ten seconds. A photo that's slow to read can wait for the next turn.
                withTimeoutOrNull(8_000L) { ids.forEach { update(app, it, keepPhoto) } }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val MaxSidePx = 1024
        private const val MinSidePx = 256
        private const val CornerDp = 22f

        private fun shownPrefs(context: Context) = context.getSharedPreferences("gallery_widget", Context.MODE_PRIVATE)
        private fun keyFor(id: Int) = "shown_$id"
        private fun lockedFiles(context: Context) = com.kobblo.gallery.file.explorer.fileops.Locks.forLists(context)

        private class Photo(val id: Long, val path: String)

        private fun update(context: Context, widgetId: Int, keepPhoto: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val locked = lockedFiles(context)
            val shown = shownPrefs(context).getString(keyFor(widgetId), null)
            val candidates = pickPhotos(context, locked, avoid = shown, keep = if (keepPhoto) shown else null)
            val views = RemoteViews(context.packageName, R.layout.widget_photo)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            // The index can't say whether a file decodes, so a damaged one just means trying the next.
            val size = sizeOf(context, manager, widgetId)
            var photo: Photo? = null
            var bitmap: Bitmap? = null
            for (candidate in candidates) {
                bitmap = render(context, candidate, size)
                if (bitmap != null) {
                    photo = candidate
                    break
                }
            }
            if (bitmap != null && photo != null) {
                views.setImageViewBitmap(R.id.widget_photo, bitmap)
                views.setViewVisibility(R.id.widget_photo, View.VISIBLE)
                views.setViewVisibility(R.id.widget_empty, View.GONE)
                shownPrefs(context).edit().putString(keyFor(widgetId), photo.path).apply()
            } else {
                // Clearing matters: leaving the old bitmap up could keep showing a file just locked.
                views.setImageViewBitmap(R.id.widget_photo, null)
                views.setViewVisibility(R.id.widget_photo, View.GONE)
                views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                shownPrefs(context).edit().remove(keyFor(widgetId)).apply()
            }
            manager.updateAppWidget(widgetId, views)
        }

        private fun sizeOf(context: Context, manager: AppWidgetManager, widgetId: Int): Pair<Int, Int> {
            val options = manager.getAppWidgetOptions(widgetId)
            val density = context.resources.displayMetrics.density
            val width = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 170) * density).roundToInt()
            val height = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 170) * density).roundToInt()
            val longest = max(width, height).coerceAtLeast(1)
            val ratio = minOf(1f, MaxSidePx.toFloat() / longest)
            return (width * ratio).roundToInt().coerceAtLeast(MinSidePx) to (height * ratio).roundToInt().coerceAtLeast(MinSidePx)
        }

        /** A few random picks at once, in case some of them won't decode. */
        private const val PickCount = 12

        /**
         * One pass over the index, keeping a small random sample, so a library of any size needs no
         * list in memory. Screenshots are a last resort: nobody hangs those on the wall.
         */
        private fun pickPhotos(context: Context, locked: com.kobblo.gallery.file.explorer.fileops.LockedPaths, avoid: String?, keep: String?): List<Photo> {
            val projection = arrayOf(BaseColumns._ID, MediaStore.MediaColumns.DATA)
            val best = ArrayList<Photo>(PickCount)
            var seen = 0
            val fallback = ArrayList<Photo>(PickCount)
            var fallbackSeen = 0
            var kept: Photo? = null
            val hidden = com.kobblo.gallery.file.explorer.fileops.LockedPaths(com.kobblo.gallery.file.explorer.gallery.GalleryStore.get(context).hiddenAlbums.value)
            try {
                context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { c ->
                    val idColumn = c.getColumnIndexOrThrow(BaseColumns._ID)
                    val dataColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    while (c.moveToNext()) {
                        val path = c.getString(dataColumn) ?: continue
                        if (!isWidgetEligible(path, locked) || hidden.covers(path)) continue
                        if (path == keep) kept = Photo(c.getLong(idColumn), path)
                        if (isScreenshotFile(path, path.substringAfterLast('/')) || path == avoid) {
                            fallbackSeen++
                            sampleInto(fallback, fallbackSeen, Photo(c.getLong(idColumn), path))
                        } else {
                            seen++
                            sampleInto(best, seen, Photo(c.getLong(idColumn), path))
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PhotoWidget", "Couldn't read the media index", e)
            }
            return listOfNotNull(kept) + best.shuffled() + fallback.shuffled()
        }

        /** Keeps every item seen so far equally likely to be in [sample], whatever the total turns out to be. */
        private fun sampleInto(sample: ArrayList<Photo>, seen: Int, photo: Photo) {
            if (sample.size < PickCount) {
                sample.add(photo)
            } else {
                val slot = Random.nextInt(seen)
                if (slot < PickCount) sample[slot] = photo
            }
        }

        private fun render(context: Context, photo: Photo, size: Pair<Int, Int>): Bitmap? {
            val (width, height) = size
            val source = loadBitmap(context, photo, max(width, height)) ?: return null
            return try {
                val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
                val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply {
                        setScale(scale, scale)
                        postTranslate((width - source.width * scale) / 2f, (height - source.height * scale) / 2f)
                    })
                }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
                val radius = CornerDp * context.resources.displayMetrics.density
                Canvas(out).drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
                out
            } catch (e: OutOfMemoryError) {
                null
            } finally {
                source.recycle()
            }
        }

        private fun loadBitmap(context: Context, photo: Photo, side: Int): Bitmap? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photo.id)
                    return context.contentResolver.loadThumbnail(uri, Size(side, side), null)
                } catch (e: Exception) {
                    // Falls through to reading the file itself.
                }
            }
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(photo.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= side && bounds.outHeight / (sample * 2) >= side) sample *= 2
                BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }

        private var watching = false
        private var observer: ContentObserver? = null
        private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

        /**
         * Keeps the promise above while the app runs. Locking, hiding and moving a file into the Vault
         * all happen in here, and each shows up as a change to the lock list or the media index. If the
         * photo on a widget is no longer allowed, it's replaced straight away rather than at the next turn.
         */
        fun watch(context: Context) {
            val app = context.applicationContext
            if (watching) return
            watching = true
            val handler = Handler(Looper.getMainLooper())
            val check = Runnable { scope.launch { replaceIfNotAllowed(app) } }
            fun schedule() {
                handler.removeCallbacks(check)
                handler.postDelayed(check, 400L)
            }
            observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) = schedule()
            }.also {
                app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, it)
            }
            prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "locked_files") schedule()
            }.also {
                app.getSharedPreferences("kobblo_prefs", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(it)
            }
        }

        private fun replaceIfNotAllowed(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, PhotoWidget::class.java))
            if (ids.isEmpty()) return
            val locked = lockedFiles(context)
            for (id in ids) {
                val shown = shownPrefs(context).getString(keyFor(id), null) ?: continue
                if (!isWidgetEligible(shown, locked) || !File(shown).exists()) update(context, id, keepPhoto = false)
            }
        }
    }
}
