package com.spotter.posespike.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * Wraps MediaPipe's PoseLandmarker for LIVE_STREAM mode against a CameraX feed.
 *
 * This is the actual thing the technical spike needs to prove out:
 * - Does the GPU delegate initialize reliably across real OEM hardware, or
 *   does it need a CPU fallback path in production (it does — see below).
 * - What's the real per-frame latency budget once results come back on a
 *   background thread via the listener, not synchronously.
 *
 * IMPORTANT — this is deliberately NOT a singleton and NOT thread-safe by
 * default. MediaPipe's PoseLandmarker in LIVE_STREAM mode requires that
 * detectAsync() calls happen from a single dedicated thread in timestamp
 * order — call this from one background executor only (see CameraController).
 */
class PoseLandmarkerHelper(
    private val context: Context,
    private val listener: LandmarkerListener,
    private val minPoseDetectionConfidence: Float = 0.5f,
    private val minPoseTrackingConfidence: Float = 0.5f,
    private val minPosePresenceConfidence: Float = 0.5f,
) {
    private var poseLandmarker: PoseLandmarker? = null
    var currentDelegate: Delegate = Delegate.GPU
        private set

    init {
        setupPoseLandmarker()
    }

    /**
     * Attempts GPU delegate first (much faster — meaningfully changes
     * whether 30fps is achievable). Falls back to CPU on failure.
     *
     * GPU delegate initialization can throw or silently fail on some OEM
     * skins (observed historically on certain MediaTek chipsets and older
     * Samsung One UI builds) — this is exactly the kind of device-fragmentation
     * risk flagged in the PRD's open questions, and why this fallback exists
     * rather than assuming GPU always works.
     */
    private fun setupPoseLandmarker() {
        try {
            buildLandmarker(Delegate.GPU)
            currentDelegate = Delegate.GPU
        } catch (e: Exception) {
            Log.w(TAG, "GPU delegate init failed, falling back to CPU: ${e.message}")
            try {
                buildLandmarker(Delegate.CPU)
                currentDelegate = Delegate.CPU
            } catch (e2: Exception) {
                Log.e(TAG, "CPU delegate init also failed", e2)
                listener.onError("Pose landmarker failed to initialize on this device: ${e2.message}")
            }
        }
    }

    private fun buildLandmarker(delegate: Delegate) {
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath(MODEL_PATH)
            .setDelegate(delegate)

        val optionsBuilder = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptionsBuilder.build())
            .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
            .setMinTrackingConfidence(minPoseTrackingConfidence)
            .setMinPosePresenceConfidence(minPosePresenceConfidence)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::returnLivestreamResult)
            .setErrorListener(this::returnLivestreamError)

        poseLandmarker = PoseLandmarker.createFromOptions(context, optionsBuilder.build())
    }

    /**
     * Feeds one frame in. Must be called from a single dedicated background
     * thread with monotonically increasing timestamps — CameraController
     * owns this contract, this class does not enforce it.
     */
    fun detectLiveStream(bitmap: Bitmap, frameTimeMs: Long) {
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        poseLandmarker?.detectAsync(mpImage, frameTimeMs)
    }

    private fun returnLivestreamResult(result: PoseLandmarkerResult, input: MPImage) {
        listener.onResults(
            PoseResultBundle(
                results = result,
                inferenceTimeMs = System.currentTimeMillis() - result.timestampMs(),
                inputImageWidth = input.width,
                inputImageHeight = input.height,
            )
        )
    }

    private fun returnLivestreamError(error: RuntimeException) {
        Log.e(TAG, "Pose landmarker inference error", error)
        listener.onError(error.message ?: "Unknown pose landmarker error")
    }

    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
    }

    data class PoseResultBundle(
        val results: PoseLandmarkerResult,
        val inferenceTimeMs: Long,
        val inputImageWidth: Int,
        val inputImageHeight: Int,
    )

    interface LandmarkerListener {
        fun onError(error: String)
        fun onResults(resultBundle: PoseResultBundle)
    }

    companion object {
        private const val TAG = "PoseLandmarkerHelper"

        // Lite variant deliberately chosen for the spike: full/heavy variants
        // trade accuracy for latency in a direction that's wrong for a
        // 30fps real-time requirement. Must be placed at
        // app/src/main/assets/pose_landmarker_lite.task — see README.
        const val MODEL_PATH = "pose_landmarker_lite.task"
    }
}
