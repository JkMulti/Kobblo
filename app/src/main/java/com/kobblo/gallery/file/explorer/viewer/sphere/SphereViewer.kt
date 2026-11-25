package com.kobblo.gallery.file.explorer.viewer.sphere

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.ExpressiveCircularLoader
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Every real phone has this, but a thread failing to get a context takes the app with it, so it's worth asking. */
internal fun canDrawSphere(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    return manager.deviceConfigurationInfo.reqGlEsVersion >= 0x20000
}

/**
 * A 360° photo, from the middle of the sphere it's drawn on. It covers the whole viewer, so a drag here can't also
 * swipe to the next photo. Anything going wrong in GL calls [onFailed], which leaves the normal flat photo showing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SphereViewer(
    path: String,
    projection: SphereProjection,
    onClose: () -> Unit,
    onFailed: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val camera = remember { SphereCamera() }
    var showing by remember { mutableStateOf(false) }
    val surface = remember { mutableStateOf<SphereSurface?>(null) }
    val renderer = remember {
        val main = Handler(Looper.getMainLooper())
        SphereRenderer(
            camera = camera,
            projection = projection,
            onNeedsImage = { maxSide ->
                main.post {
                    showing = false
                    scope.launch {
                        val bitmap = withContext(Dispatchers.IO) { decodeForTexture(path, maxSide) }
                        if (bitmap == null) {
                            onFailed()
                            return@launch
                        }
                        val view = surface.value
                        if (view == null) {
                            bitmap.recycle()
                            return@launch
                        }
                        view.queueEvent { view.renderer.upload(bitmap) }
                    }
                }
            },
            onShowing = {
                main.post {
                    showing = true
                    surface.value?.requestRender()
                }
            },
            onFailed = { main.post(onFailed) }
        )
    }

    BackHandler(onBack = onClose)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surface.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> surface.value?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                SphereSurface(viewContext, renderer).also { surface.value = it }
            },
            onRelease = { view ->
                surface.value = null
                // The context goes with the view, and these go with it, but there's no reason to rely on a driver for that.
                view.queueEvent { renderer.releaseGl() }
            }
        )
        // Above the surface, because a view inside Compose doesn't get to share its touches.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        camera.drag(pan.x, pan.y, size.height)
                        camera.zoom(zoom)
                        surface.value?.requestRender()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            camera.reset()
                            surface.value?.requestRender()
                        }
                    )
                }
        )
        if (!showing) {
            ExpressiveCircularLoader(size = 36.dp, color = KobbloDesign.colors.action, modifier = Modifier.align(Alignment.Center))
        }
        KobbloIconButton(
            icon = AppIcons.Close,
            description = stringResource(R.string.viewer_sphere_exit),
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .padding(16.dp),
            background = KobbloDesign.colors.mist
        )
    }
}

private class SphereSurface(context: Context, val renderer: SphereRenderer) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(2)
        // Keeps the texture through a trip to the background where the driver can, sparing a decode on return.
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}

/** Small enough for the GPU's limit, and tried smaller still if memory runs short. */
private fun decodeForTexture(path: String, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || !File(path).exists()) return null
    var sample = sphereSampleSize(bounds.outWidth, bounds.outHeight, minOf(maxSide, MaxTextureSide))
    repeat(3) {
        try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeFile(path, options)
        } catch (_: OutOfMemoryError) {
            sample *= 2
        }
    }
    return null
}

/** Past this, a phone's memory gives out before the picture gets any sharper on its screen. */
private const val MaxTextureSide = 8192

private const val VertexShader = """
    uniform mat4 uMatrix;
    attribute vec3 aPosition;
    attribute vec2 aUv;
    varying vec2 vUv;
    void main() {
        vUv = aUv;
        gl_Position = uMatrix * vec4(aPosition, 1.0);
    }
"""

private const val FragmentShader = """
    #ifdef GL_FRAGMENT_PRECISION_HIGH
    precision highp float;
    #else
    precision mediump float;
    #endif
    uniform sampler2D uTexture;
    uniform vec4 uCrop;
    varying vec2 vUv;
    void main() {
        vec2 place = (vUv - uCrop.xy) / uCrop.zw;
        if (place.x < 0.0 || place.x > 1.0 || place.y < 0.0 || place.y > 1.0) {
            gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        } else {
            gl_FragColor = texture2D(uTexture, place);
        }
    }
"""

/** Runs on the GL thread. A new context can arrive at any time, and everything is made again when one does. */
private class SphereRenderer(
    private val camera: SphereCamera,
    private val projection: SphereProjection,
    private val onNeedsImage: (maxSide: Int) -> Unit,
    private val onShowing: () -> Unit,
    private val onFailed: () -> Unit
) : GLSurfaceView.Renderer {
    private var program = 0
    private var texture = 0
    private var vertexBuffer = 0
    private var indexBuffer = 0
    private var indexCount = 0
    private var matrixHandle = 0
    private var cropHandle = 0
    private var positionHandle = 0
    private var uvHandle = 0
    private var aspect = 1f
    private var failed = false
    private val view = FloatArray(16)
    private val rotateX = FloatArray(16)
    private val rotateY = FloatArray(16)
    private val perspective = FloatArray(16)
    private val combined = FloatArray(16)

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) = guarded {
        // Whatever the last context had went with it.
        program = 0
        texture = 0
        vertexBuffer = 0
        indexBuffer = 0
        program = linkProgram()
        matrixHandle = GLES20.glGetUniformLocation(program, "uMatrix")
        cropHandle = GLES20.glGetUniformLocation(program, "uCrop")
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        uvHandle = GLES20.glGetAttribLocation(program, "aUv")
        buildMesh()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val limit = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, limit, 0)
        checkGl("set up")
        onNeedsImage(limit[0].coerceAtLeast(1024))
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) = guarded {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
    }

    override fun onDrawFrame(unused: GL10?) = guarded {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (texture == 0 || program == 0) return@guarded
        Matrix.perspectiveM(perspective, 0, camera.fieldOfView, aspect, 0.1f, 10f)
        Matrix.setRotateM(rotateX, 0, -camera.pitch, 1f, 0f, 0f)
        Matrix.setRotateM(rotateY, 0, camera.yaw, 0f, 1f, 0f)
        Matrix.multiplyMM(view, 0, rotateX, 0, rotateY, 0)
        Matrix.multiplyMM(combined, 0, perspective, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, combined, 0)
        GLES20.glUniform4f(cropHandle, projection.left, projection.top, projection.width, projection.height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)

        val stride = SphereMesh.FloatsPerVertex * Float.SIZE_BYTES
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, stride, 3 * Float.SIZE_BYTES)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        checkGl("draw")
    }

    /** On the GL thread, with the picture decoded elsewhere. The bitmap isn't needed once the GPU has it. */
    fun upload(bitmap: Bitmap) {
        guarded {
            if (program == 0) return@guarded
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            val handle = IntArray(1)
            GLES20.glGenTextures(1, handle, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handle[0])
            // No mipmaps or wrapping: both need power of two sizes here, and a photo rarely is.
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            texture = handle[0]
            checkGl("upload")
            onShowing()
        }
        bitmap.recycle()
    }

    fun releaseGl() {
        // Nothing here is worth a report: the view is on its way out regardless.
        try {
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            if (vertexBuffer != 0 || indexBuffer != 0) GLES20.glDeleteBuffers(2, intArrayOf(vertexBuffer, indexBuffer), 0)
            if (program != 0) GLES20.glDeleteProgram(program)
        } catch (_: RuntimeException) {
        }
        texture = 0
        vertexBuffer = 0
        indexBuffer = 0
        program = 0
    }

    private fun buildMesh() {
        val mesh = SphereMesh()
        val vertices = ByteBuffer.allocateDirect(mesh.vertices.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        vertices.asFloatBuffer().put(mesh.vertices)
        val indices = ByteBuffer.allocateDirect(mesh.indices.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        indices.asShortBuffer().put(mesh.indices)
        val buffers = IntArray(2)
        GLES20.glGenBuffers(2, buffers, 0)
        vertexBuffer = buffers[0]
        indexBuffer = buffers[1]
        indexCount = mesh.indices.size
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.capacity(), vertices, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.capacity(), indices, GLES20.GL_STATIC_DRAW)
    }

    private fun linkProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VertexShader)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FragmentShader)
        val linked = GLES20.glCreateProgram()
        GLES20.glAttachShader(linked, vertex)
        GLES20.glAttachShader(linked, fragment)
        GLES20.glLinkProgram(linked)
        val status = IntArray(1)
        GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (status[0] == 0) throw IllegalStateException("The sphere's shaders didn't link")
        return linked
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) throw IllegalStateException("A sphere shader didn't compile")
        return shader
    }

    private fun checkGl(step: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) throw IllegalStateException("GL error $error during $step")
    }

    /** An exception on this thread would take the app down, for a view of a photo the flat one can stand in for. */
    private inline fun guarded(block: () -> Unit) {
        if (failed) return
        try {
            block()
        } catch (e: RuntimeException) {
            android.util.Log.w("SphereViewer", "Falling back to the flat photo", e)
            failed = true
            onFailed()
        }
    }
}
