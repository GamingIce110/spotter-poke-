package com.spotter.posespike.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.spotter.posespike.pose.PoseLandmarkerHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the CameraX lifecycle and the single dedicated background thread
 * that pose inference must run on.
 *
 * Why a single dedicated executor and not a thread pool: MediaPipe's
 * LIVE_STREAM mode requires detectAsync() calls in strictly increasing
 * timestamp order on one thread. A thread pool (or CameraX's default
 * analyzer executor under contention) can reorder frames, which corrupts
 * MediaPipe's internal tracking state silently — no crash, just wrong
 * results. This is a real, previously-seen failure mode, not theoretical.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val poseLandmarkerHelper: PoseLandmarkerHelper,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun start(onError: (String) -> Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindUseCases()
            } catch (e: Exception) {
                onError("Camera provider init failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // STRATEGY.KEEP_ONLY_LATEST is deliberate: if pose inference falls
        // behind the camera's native frame rate, we want to drop stale
        // frames rather than build a backlog. A backlog would make the
        // on-screen skeleton lag further and further behind the live feed
        // as a set progresses — worse UX than a slightly lower effective fps.
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor) { imageProxy ->
                    processFrame(imageProxy)
                }
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis,
            )
        } catch (e: Exception) {
            throw IllegalStateException("Camera use case binding failed", e)
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmapRotated()
            val frameTimeMs = System.currentTimeMillis()
            poseLandmarkerHelper.detectLiveStream(bitmap, frameTimeMs)
        } finally {
            // Must always close, even on failure — leaking ImageProxy
            // stalls the camera pipeline within a few frames.
            imageProxy.close()
        }
    }

    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }
}

/**
 * Converts an ImageProxy (RGBA_8888 format, as configured above) to a
 * Bitmap and applies the sensor rotation so the pose model sees an
 * upright frame regardless of device/sensor mounting angle.
 *
 * NOTE: this is a straightforward but not zero-copy conversion. For the
 * spike this is acceptable — it's exactly the kind of thing the fps
 * counter will surface if it's a real bottleneck (check inference latency
 * vs. total per-frame time in the overlay; if they diverge significantly,
 * this conversion is the next place to optimize, e.g. via RenderScript-free
 * YUV->RGB paths or ImageAnalysis.Analyzer running directly on YUV planes).
 */
private fun ImageProxy.toBitmapRotated(): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width

    val bitmap = Bitmap.createBitmap(
        width + rowPadding / pixelStride,
        height,
        Bitmap.Config.ARGB_8888,
    )
    bitmap.copyPixelsFromBuffer(buffer)

    val cropped = if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)

    val rotationDegrees = imageInfo.rotationDegrees
    if (rotationDegrees == 0) return cropped

    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
}
