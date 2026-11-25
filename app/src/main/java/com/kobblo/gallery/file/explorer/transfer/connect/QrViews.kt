package com.kobblo.gallery.file.explorer.transfer.connect

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.PlanarYUVLuminanceSource
import com.kobblo.gallery.file.explorer.transfer.core.QrCodes
import com.kobblo.gallery.file.explorer.transfer.core.QrMatrix
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A QR code, drawn module by module so it stays sharp at any size. It always sits on white with its
 * quiet margin, in dark mode too: most scanners can't read a code on black.
 */
@Composable
fun QrImage(text: String, description: String, modifier: Modifier = Modifier) {
    val matrix by produceState<QrMatrix?>(null, text) {
        value = withContext(Dispatchers.Default) { runCatching { QrCodes.encode(text) }.getOrNull() }
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(KobbloShapes.lg)
            .background(Color.White)
            .semantics { contentDescription = description }
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        val current = matrix
        if (current == null) {
            com.kobblo.gallery.file.explorer.ui.design.KobbloLoader(color = KobbloDesign.colors.action)
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cell = size.minDimension / current.size
                val cellSize = Size(cell + 0.5f, cell + 0.5f) // A hair over, so no seams show between modules.
                for (y in 0 until current.size) {
                    for (x in 0 until current.size) {
                        if (current[x, y]) {
                            drawRect(Color.Black, Offset(x * cell, y * cell), cellSize)
                        }
                    }
                }
            }
        }
    }
}

object QrImageReader {
    private const val MAX_SIDE = 2000

    /** Reads a QR code from a picture, or returns null if it has none. */
    suspend fun read(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@withContext null
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            QrCodes.decode(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
        } catch (e: Exception) {
            Log.w("QrImageReader", "Couldn't read that image: ${e.javaClass.simpleName}")
            null
        }
    }
}

/** The live camera, reading frames until one holds a code. Calls [onCamera] with whether it could start. */
@Composable
fun QrCameraView(
    onText: (String) -> Unit,
    onCamera: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    val currentOnCamera by rememberUpdatedState(onCamera)
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val delivered = remember { AtomicBoolean(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                val hasCamera = runCatching { cameraProvider.hasCamera(selector) }.getOrDefault(false)
                if (!hasCamera) {
                    currentOnCamera(false)
                    return@addListener
                }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { frame ->
                    val text = try {
                        if (delivered.get()) null else decodeFrame(frame)
                    } finally {
                        frame.close()
                    }
                    if (text != null && delivered.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(context).execute { currentOnText(text) }
                    }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                currentOnCamera(true)
            } catch (e: Exception) {
                Log.w("QrCameraView", "Camera failed: ${e.javaClass.simpleName}")
                currentOnCamera(false)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun decodeFrame(frame: ImageProxy): String? {
    val plane = frame.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val width = frame.width
    val height = frame.height
    if (data.size < rowStride * height) return null
    val source = PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
    return QrCodes.decode(source)
}
