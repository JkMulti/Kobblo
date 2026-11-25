package com.kobblo.gallery.file.explorer.viewer.decoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Pulls one frame out of a video by actually playing it, into an offscreen texture. The platform's
 * retriever can't open AVI or FLV at all, but ExoPlayer can, and this is the only route to a real
 * thumbnail for them. It is slow next to the retriever, so it only runs when that has given up, and
 * its result is kept on disk.
 */
internal object ExoFrameGrabber {
    private const val TimeoutMillis = 5000L

    suspend fun grab(context: Context, file: File, side: Int, frameMillis: Long): Bitmap? {
        val thread = HandlerThread("video-frame").apply { start() }
        val handler = Handler(thread.looper)
        val result = CompletableDeferred<Bitmap?>()
        var cleanup: (() -> Unit)? = null
        handler.post {
            try {
                cleanup = start(context, file, side, frameMillis, handler, result)
            } catch (_: Throwable) {
                result.complete(null)
            }
        }
        return try {
            withTimeoutOrNull(TimeoutMillis) { result.await() }
        } finally {
            val done = CompletableDeferred<Unit>()
            handler.post {
                try { cleanup?.invoke() } catch (_: Throwable) {}
                done.complete(Unit)
            }
            withTimeoutOrNull(TimeoutMillis) { done.await() }
            thread.quitSafely()
        }
    }

    private fun start(
        context: Context,
        file: File,
        side: Int,
        frameMillis: Long,
        handler: Handler,
        result: CompletableDeferred<Bitmap?>
    ): () -> Unit {
        val gl = OffscreenGl()
        val texture = SurfaceTexture(gl.textureId)
        val surface = Surface(texture)
        var videoWidth = 0
        var videoHeight = 0
        var seekedToFrame = false

        val player = ExoPlayer.Builder(context.applicationContext, DefaultRenderersFactory(context.applicationContext).setEnableDecoderFallback(true))
            .setLooper(handler.looper)
            .build()
        player.volume = 0f
        player.setVideoSurface(surface)
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height
            }

            override fun onPlayerError(error: PlaybackException) {
                result.complete(null)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !seekedToFrame) {
                    seekedToFrame = true
                    val duration = player.duration
                    // Skip a black opening when there is room, as the other routes do.
                    val target = if (duration > 0 && frameMillis >= duration) duration / 2 else frameMillis
                    if (target > 0) player.seekTo(target)
                }
                if (playbackState == Player.STATE_ENDED) result.complete(null)
            }
        })
        texture.setOnFrameAvailableListener({
            if (result.isCompleted) return@setOnFrameAvailableListener
            try {
                texture.updateTexImage()
                val width = videoWidth
                val height = videoHeight
                if (width > 0 && height > 0 && player.currentPosition > 0) {
                    result.complete(gl.read(texture, width, height, side))
                }
            } catch (_: Throwable) {
                result.complete(null)
            }
        }, handler)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true

        return {
            player.release()
            surface.release()
            texture.release()
            gl.release()
        }
    }

    /** The smallest OpenGL setup that can read a decoder's output back into a bitmap. */
    private class OffscreenGl {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface
        private val program: Int
        val textureId: Int

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
                ),
                0, configs, 0, 1, count, 0
            )
            check(count[0] > 0)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            // The real drawing surface is a framebuffer sized later, so this only has to exist.
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            program = link(
                """
                attribute vec4 position;
                uniform mat4 transform;
                varying vec2 coordinate;
                void main() {
                    gl_Position = vec4(position.xy, 0.0, 1.0);
                    coordinate = (transform * vec4(position.zw, 0.0, 1.0)).xy;
                }
                """.trimIndent(),
                """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES frame;
                varying vec2 coordinate;
                void main() { gl_FragColor = texture2D(frame, coordinate); }
                """.trimIndent()
            )
        }

        fun read(texture: SurfaceTexture, videoWidth: Int, videoHeight: Int, side: Int): Bitmap {
            val scale = minOf(1f, side.toFloat() / maxOf(videoWidth, videoHeight))
            val width = (videoWidth * scale).toInt().coerceAtLeast(1)
            val height = (videoHeight * scale).toInt().coerceAtLeast(1)

            val framebuffer = IntArray(1)
            val target = IntArray(1)
            GLES20.glGenFramebuffers(1, framebuffer, 0)
            GLES20.glGenTextures(1, target, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, target[0], 0)
            GLES20.glViewport(0, 0, width, height)

            val transform = FloatArray(16)
            texture.getTransformMatrix(transform)
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, transform, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "frame"), 0)
            val position = GLES20.glGetAttribLocation(program, "position")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 4, GLES20.GL_FLOAT, false, 0, Quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, framebuffer, 0)
            GLES20.glDeleteTextures(1, target, 0)

            pixels.rewind()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(pixels)
            // OpenGL reads from the bottom row up.
            val flipped = Bitmap.createBitmap(bitmap, 0, 0, width, height, Matrix().apply { preScale(1f, -1f) }, false)
            if (flipped !== bitmap) bitmap.recycle()
            return flipped
        }

        fun release() {
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }

        private fun link(vertex: String, fragment: String): Int {
            val linked = GLES20.glCreateProgram()
            GLES20.glAttachShader(linked, compile(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(linked, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(linked)
            return linked
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        private companion object {
            /** A full-screen strip: x, y for the screen, then u, v for the frame. */
            val Quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
                rewind()
            }
        }
    }
}
