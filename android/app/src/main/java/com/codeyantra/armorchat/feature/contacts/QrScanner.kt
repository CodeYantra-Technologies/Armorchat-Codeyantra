package com.codeyantra.armorchat.feature.contacts

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Camera frames and decoded text remain on device and are never logged or persisted. */
@Composable
internal fun QrScanner(modifier: Modifier, onQr: (String) -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onQr)
    val failure by rememberUpdatedState(onError)
    val previewView = remember { PreviewView(context) }
    AndroidView(factory = { previewView }, modifier = modifier)
    DisposableEffect(owner, previewView) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val disposed = AtomicBoolean(false)
        val detected = AtomicBoolean(false)
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
        var lastFrame = 0L
        analysis.setAnalyzer(executor) { frame ->
            try {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!disposed.get() && !detected.get() && now - lastFrame >= 200) {
                    lastFrame = now
                    val plane = frame.planes[0]
                    val buffer = plane.buffer.duplicate()
                    val offset = buffer.position()
                    val width = frame.width
                    val height = frame.height
                    val luminance = ByteArray(width * height)
                    for (y in 0 until height) for (x in 0 until width) {
                        luminance[y * width + x] = buffer.get(offset + y * plane.rowStride + x * plane.pixelStride)
                    }
                    val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
                    val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                    if (detected.compareAndSet(false, true)) main.execute {
                        if (!disposed.get()) callback(result.text)
                    }
                }
            } catch (_: ReaderException) {
                // No decodable QR in this frame; continue scanning.
            } catch (_: Exception) {
                if (detected.compareAndSet(false, true)) main.execute { if (!disposed.get()) failure() }
            } finally { reader.reset(); frame.close() }
        }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            if (!disposed.get()) {
                try {
                    provider = future.get()
                    provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) { failure() }
            }
        }, main)
        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            executor.shutdown()
        }
    }
}
