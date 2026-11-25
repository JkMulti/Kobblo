package com.kobblo.gallery.file.explorer.viewer.sphere

import com.kobblo.gallery.file.explorer.viewer.metadata.Xmp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What part of the sphere a 360° photo covers, as fractions of the whole. Only XMP saying so counts, because plenty of
 * ordinary wide photos are exactly 2:1. A phone's panorama mode often covers less than the full sphere and says how much.
 */
class SphereProjection(val left: Float = 0f, val top: Float = 0f, val width: Float = 1f, val height: Float = 1f) {
    companion object {
        fun parse(xmp: String): SphereProjection? {
            if (Xmp.value(xmp, "ProjectionType")?.equals("equirectangular", ignoreCase = true) != true) return null
            fun number(name: String) = Xmp.value(xmp, name)?.trim()?.toFloatOrNull()
            val fullWidth = number("FullPanoWidthPixels")
            val fullHeight = number("FullPanoHeightPixels")
            val width = number("CroppedAreaImageWidthPixels")
            val height = number("CroppedAreaImageHeightPixels")
            if (fullWidth == null || fullHeight == null || width == null || height == null) return SphereProjection()
            if (fullWidth <= 0f || fullHeight <= 0f || width <= 0f || height <= 0f) return SphereProjection()
            return SphereProjection(
                left = ((number("CroppedAreaLeftPixels") ?: 0f) / fullWidth).coerceIn(0f, 1f),
                top = ((number("CroppedAreaTopPixels") ?: 0f) / fullHeight).coerceIn(0f, 1f),
                width = (width / fullWidth).coerceIn(0.001f, 1f),
                height = (height / fullHeight).coerceIn(0.001f, 1f)
            )
        }
    }
}

/** Where the view is pointing. A finger moves the picture with it, at the same speed whatever the zoom. */
class SphereCamera {
    /** Degrees right of the photo's middle. */
    @Volatile var yaw = 0f
        private set

    /** Degrees above the horizon. */
    @Volatile var pitch = 0f
        private set

    /** The vertical field of view, in degrees. */
    @Volatile var fieldOfView = DefaultFieldOfView
        private set

    fun drag(dxPx: Float, dyPx: Float, viewHeightPx: Int) {
        if (viewHeightPx <= 0) return
        val degreesPerPx = fieldOfView / viewHeightPx
        yaw = wrap(yaw - dxPx * degreesPerPx)
        // Stops short of the poles, where left and right stop meaning anything.
        pitch = (pitch + dyPx * degreesPerPx).coerceIn(-MaxPitch, MaxPitch)
    }

    fun zoom(scale: Float) {
        if (scale <= 0f) return
        fieldOfView = (fieldOfView / scale).coerceIn(MinFieldOfView, MaxFieldOfView)
    }

    fun reset() {
        yaw = 0f
        pitch = 0f
        fieldOfView = DefaultFieldOfView
    }

    private fun wrap(degrees: Float): Float {
        var angle = degrees % 360f
        if (angle > 180f) angle -= 360f
        if (angle < -180f) angle += 360f
        return angle
    }

    companion object {
        // Vertical, on a phone held upright, so the width it shows is about half of this.
        const val DefaultFieldOfView = 90f
        const val MinFieldOfView = 30f
        const val MaxFieldOfView = 110f
        const val MaxPitch = 89f
    }
}

/** A sphere of unit radius seen from inside: x, y, z, u, v per vertex, with v = 0 at the photo's top. */
internal class SphereMesh(rings: Int = 64, segments: Int = 128) {
    val vertices: FloatArray
    val indices: ShortArray

    init {
        val perRow = segments + 1
        vertices = FloatArray((rings + 1) * perRow * FloatsPerVertex)
        var at = 0
        for (ring in 0..rings) {
            val v = ring.toFloat() / rings
            val latitude = (0.5 - v) * PI
            for (segment in 0..segments) {
                val u = segment.toFloat() / segments
                val longitude = (u - 0.5) * 2 * PI
                vertices[at++] = (cos(latitude) * sin(longitude)).toFloat()
                vertices[at++] = sin(latitude).toFloat()
                vertices[at++] = (-cos(latitude) * cos(longitude)).toFloat()
                vertices[at++] = u
                vertices[at++] = v
            }
        }
        indices = ShortArray(rings * segments * 6)
        at = 0
        for (ring in 0 until rings) {
            for (segment in 0 until segments) {
                val a = ring * perRow + segment
                val b = a + perRow
                indices[at++] = a.toShort()
                indices[at++] = b.toShort()
                indices[at++] = (a + 1).toShort()
                indices[at++] = (a + 1).toShort()
                indices[at++] = b.toShort()
                indices[at++] = (b + 1).toShort()
            }
        }
    }

    companion object {
        const val FloatsPerVertex = 5
    }
}

/** The power of two that brings a picture within [maxSide] on its longer side. */
internal fun sphereSampleSize(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / sample > maxSide) sample *= 2
    return sample
}
