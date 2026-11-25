package com.kobblo.gallery.file.explorer.explorer.drag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.mutableStateListOf
import androidx.core.graphics.createBitmap
import coil.decode.DataSource
import coil.imageLoader
import coil.request.Disposable
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.ui.design.icons.folderGlyphFor
import kotlinx.coroutines.Dispatchers
import kotlin.math.min

/** A card with what's on it. */
internal class CardModel(
    val file: FileItem,
    val body: DragCard,
    /** What it's worth decoding at, if nothing's in memory already. */
    val picturePx: Int,
    /** Whether the list has it on screen, which means its picture is almost certainly in memory right now. */
    val onScreen: Boolean
) {
    val isVideo: Boolean = file.type == "video"

    var picture: Bitmap? = null
        private set
    val pictureArea = Rect()

    /** When the picture turned up, for a quick fade in, or zero if it was there from the first. */
    var pictureSinceNanos = 0L
        private set

    /** What's on it if it has no picture, or none yet. */
    var art: TileArt? = null

    /** Decoded just for this card. Anything else is the cache's, and may be on screen. */
    private var ownsPicture = false

    fun show(bitmap: Bitmap, sinceNanos: Long, owned: Boolean) {
        // The card is square whatever the picture is, so the picture's cropped to its middle.
        val side = min(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        pictureArea.set(left, top, left + side, top + side)
        pictureSinceNanos = sinceNanos
        ownsPicture = owned
        picture = bitmap
    }

    fun forgetPicture() {
        // Nothing else has this one. Without this it would sit in graphics memory until the next garbage collection,
        // which on an idle app could be a long time off.
        if (ownsPicture) picture?.recycle()
        picture = null
        art = null
    }
}

/**
 * The picture for a file type with none of its own, drawn once and then used on every card of that type. [file] is any
 * file of that type, and null is the play mark on a video.
 */
internal class TileArt(val key: String, val file: FileItem?) {
    var bitmap: Bitmap? = null
}

/**
 * Gets every card its picture. Top cards first, a little each frame, so that lifting a large selection never holds up a
 * frame. What's on screen is in memory, which comes straight back. Anything else decodes off the main thread at the size
 * its card is drawn, and until it's here the card shows a plain tile.
 *
 * Nothing's kept once the drag ends. The bitmaps belong to the cache or to the cards, and go with them.
 */
internal class CardPictures(private val context: Context, private val source: DragSource) {
    private val loader = context.imageLoader
    private val requests = ArrayList<Disposable>()
    private val arts = HashMap<String, TileArt>()
    private val waitingToDraw = ArrayDeque<TileArt>()
    private var asked = 0
    private var released = false
    private var hidden = true

    /** What the overlay should draw into a bitmap next. A few at a time, so a lift never composes many at once. */
    val artToDraw = mutableStateListOf<TileArt>()

    val videoMark = TileArt("video-mark", null)
    private var askedForMark = false

    /** Once the cards are in view. Pictures after this fade in, where before it they're simply there. */
    fun cardsAppeared() {
        hidden = false
    }

    /** Called once a frame. */
    fun pump(cards: List<CardModel>, carrying: Boolean) {
        if (released) return
        if (carrying) {
            val until = System.nanoTime() + BudgetNanos
            while (asked < cards.size && System.nanoTime() < until) ask(cards[asked++])
        }
        drawArt()
    }

    private fun drawArt() {
        if (artToDraw.isNotEmpty()) artToDraw.removeAll { it.bitmap != null }
        while (artToDraw.size < ArtAtOnce) artToDraw.add(waitingToDraw.removeFirstOrNull() ?: return)
    }

    private fun ask(model: CardModel) {
        val file = model.file
        val builder = source.pictureRequest(context, file, model.picturePx)
        if (builder == null) {
            model.art = artFor(file)
            return
        }
        if (model.isVideo && !askedForMark) {
            askedForMark = true
            waitingToDraw.addLast(videoMark)
        }
        // The memory lookup stats the file, and what's off screen isn't worth doing that for on the main thread.
        if (!model.onScreen) builder.interceptorDispatcher(Dispatchers.Default)
        val request = builder
            .listener(
                onSuccess = { _, result -> arrived(model, result.drawable, owned = result.dataSource != DataSource.MEMORY_CACHE) },
                onError = { _, _ -> model.art = artFor(file) }
            )
            .build()
        requests.add(loader.enqueue(request))
    }

    private fun arrived(model: CardModel, drawable: Drawable, owned: Boolean) {
        if (released) return
        val plain = (drawable as? BitmapDrawable)?.bitmap
        val bitmap = plain ?: drawn(drawable, model.picturePx)
        model.show(bitmap, if (hidden) 0L else System.nanoTime(), owned || plain == null)
    }

    /** Tiles are stills, so this is only for what a decoder hands back that isn't a plain bitmap. */
    private fun drawn(drawable: Drawable, px: Int): Bitmap {
        val bitmap = createBitmap(px.coerceAtLeast(1), px.coerceAtLeast(1))
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun artFor(file: FileItem): TileArt? {
        val key = artKey(file)
        arts[key]?.let { return it }
        // Hundreds of made-up extensions in one folder shouldn't each cost a bitmap. Those stay plain.
        if (arts.size >= MaxArts) return null
        val art = TileArt(key, file)
        arts[key] = art
        waitingToDraw.addLast(art)
        return art
    }

    private fun artKey(file: FileItem): String =
        if (file.type == "folder") {
            "folder:${folderGlyphFor(file.name)?.name.orEmpty()}"
        } else {
            "paper:${file.type}:${file.name.substringAfterLast('.', "").lowercase()}"
        }

    fun release(cards: List<CardModel>) {
        released = true
        requests.forEach { it.dispose() }
        requests.clear()
        arts.clear()
        waitingToDraw.clear()
        artToDraw.clear()
        videoMark.bitmap = null
        cards.forEach { it.forgetPicture() }
    }

    private companion object {
        const val BudgetNanos = 2_000_000L
        const val ArtAtOnce = 3
        const val MaxArts = 32
    }
}
