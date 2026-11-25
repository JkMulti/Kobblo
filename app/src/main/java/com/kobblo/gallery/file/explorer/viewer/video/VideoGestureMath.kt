package com.kobblo.gallery.file.explorer.viewer.video

import kotlin.math.abs
import kotlin.math.min

/** What a drag started on the video turned into. */
internal enum class VideoDrag { None, Seek, Brightness, Volume }

/** The sums behind the player's gestures, kept apart from Compose so they can be tested on a desktop. */
internal object VideoGestureMath {
    /** Where along the width the brightness side ends and the volume side starts. The middle does neither. */
    private const val SideShare = 0.4f

    /** A sideways drag across the whole screen moves this far, or the whole clip if that's shorter. */
    const val FullWidthSeekMs = 120_000L

    /** An up or down drag over this share of the screen goes from one end of a level to the other. */
    private const val FullRangeShare = 0.7f

    /**
     * Whether a touch starting here belongs to the system. The edges are the back gesture and the bars, and taking
     * them would mean volume changing every time someone tries to go back.
     */
    fun isInSafeZone(x: Float, y: Float, width: Float, height: Float, sideMargin: Float, topMargin: Float, bottomMargin: Float): Boolean =
        x < sideMargin || x > width - sideMargin || y < topMargin || y > height - bottomMargin

    /** Decides what a drag is, from which way it's gone and where it started. */
    fun classify(startX: Float, width: Float, dx: Float, dy: Float): VideoDrag = when {
        abs(dx) > abs(dy) -> VideoDrag.Seek
        startX < width * SideShare -> VideoDrag.Brightness
        startX > width * (1f - SideShare) -> VideoDrag.Volume
        else -> VideoDrag.None
    }

    /** How far a sideways drag has moved the clip, and in which direction. */
    fun seekDelta(dragPx: Float, widthPx: Float, durationMs: Long): Long {
        if (widthPx <= 0f) return 0L
        val span = if (durationMs > 0L) min(durationMs, FullWidthSeekMs) else FullWidthSeekMs
        return (dragPx / widthPx * span).toLong()
    }

    /** The target of a seek, inside the clip. */
    fun seekTarget(startMs: Long, deltaMs: Long, durationMs: Long): Long {
        val target = (startMs + deltaMs).coerceAtLeast(0L)
        return if (durationMs > 0L) target.coerceAtMost(durationMs) else target
    }

    /** A level from 0 to 1 after dragging, up being more. */
    fun levelAfterDrag(start: Float, dragUpPx: Float, heightPx: Float): Float {
        if (heightPx <= 0f) return start
        return (start + dragUpPx / (heightPx * FullRangeShare)).coerceIn(0f, 1f)
    }

    /** The volume step nearest a level, which is all the system deals in. */
    fun volumeIndex(level: Float, maxIndex: Int): Int =
        (level.coerceIn(0f, 1f) * maxIndex + 0.5f).toInt().coerceIn(0, maxIndex)

    /**
     * A window can only be dimmed so far: zero would be taken by some phones as the screen turned off, leaving
     * someone with a black screen they can't see to fix.
     */
    fun brightnessForWindow(level: Float): Float = level.coerceIn(MinBrightness, 1f)

    private const val MinBrightness = 0.01f

    /** A pinch only counts once it's clearly more than fingers settling. */
    fun scaleForPinch(current: VideoScale, spanRatio: Float): VideoScale = when {
        spanRatio > PinchOut -> VideoScale.Fill
        spanRatio < PinchIn -> VideoScale.Fit
        else -> current
    }

    private const val PinchOut = 1.18f
    private const val PinchIn = 0.85f

    /** A signed time for the seek indicator, as a sign and a duration. */
    fun signedTime(deltaMs: Long): String = (if (deltaMs < 0) "-" else "+") + formatVideoTime(abs(deltaMs))
}

/** How a video fills its page. */
enum class VideoScale { Fit, Fill, Stretch;

    fun next(): VideoScale = entries[(ordinal + 1) % entries.size]

    /** The frame's size in a box of this shape, as a pair of how much of the box's width and height it takes. */
    fun frameFraction(videoAspect: Float, boxAspect: Float): Pair<Float, Float> {
        if (videoAspect <= 0f || boxAspect <= 0f || this == Stretch) return 1f to 1f
        val videoIsWider = videoAspect > boxAspect
        val fitToHeight = videoIsWider == (this == Fill)
        return if (fitToHeight) videoAspect / boxAspect to 1f else 1f to boxAspect / videoAspect
    }
}

/** What comes after a video when it ends on its own, which only fits when it's clearly the next in a series. */
internal object VideoNeighbors {
    private fun folderOf(path: String) = path.substringBeforeLast('/', "")

    /** The nearest video in [step]'s direction in the same folder, or -1. Photos in between are passed over. */
    fun find(paths: List<String>, isVideo: (Int) -> Boolean, current: Int, step: Int): Int {
        val folder = paths.getOrNull(current)?.let(::folderOf) ?: return -1
        var index = current + step
        while (index in paths.indices) {
            if (isVideo(index) && folderOf(paths[index]) == folder) return index
            index += step
        }
        return -1
    }
}

/** Which ending of a stored resume is worth it, on top of [ResumePolicy]. */
internal object ResumeOffer {
    /** The small pill offering to start over stays this long, and then it's just playing from where it was. */
    const val ShownForMs = 5_000L
}
