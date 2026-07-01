package com.spotter.posespike.pose

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Computes 2D joint angles from MediaPipe's 33-point pose landmark set, and
 * exposes raw-vs-smoothed values so the spike can actually measure jitter
 * instead of assuming it away.
 *
 * This is 2D-only (screen-plane angle), not true 3D joint angle. That's a
 * known, deliberate limitation: MediaPipe's z-coordinate is a rough depth
 * estimate, not metrically reliable on a single phone camera. Real squat
 * depth / knee-valgus detection will need the 45°-angle camera placement
 * from PRD F-05 to make the 2D projection meaningful — this spike should
 * be run at that same 45° angle to produce a representative signal.
 */
object JointAngleCalculator {

    // MediaPipe Pose Landmarker indices (BlazePose 33-point topology)
    object Landmark {
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_ELBOW = 13
        const val RIGHT_ELBOW = 14
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val LEFT_KNEE = 25
        const val RIGHT_KNEE = 26
        const val LEFT_ANKLE = 27
        const val RIGHT_ANKLE = 28
    }

    /**
     * Angle at [vertex] formed by rays to [a] and [c], in degrees, 0-180.
     * Returns null if any required landmark has low visibility — this
     * matters more than it sounds: partial occlusion mid-rep (e.g. the
     * far-side knee during a squat) is common and silently returning a
     * garbage angle instead of null is how bad rep-counts happen.
     */
    fun angleDegrees(
        a: NormalizedLandmark,
        vertex: NormalizedLandmark,
        c: NormalizedLandmark,
        visibilityThreshold: Float = 0.5f,
    ): Float? {
        if (!isVisible(a, visibilityThreshold) ||
            !isVisible(vertex, visibilityThreshold) ||
            !isVisible(c, visibilityThreshold)
        ) {
            return null
        }

        val angleA = atan2(a.y() - vertex.y(), a.x() - vertex.x())
        val angleC = atan2(c.y() - vertex.y(), c.x() - vertex.x())
        var degrees = Math.toDegrees((angleA - angleC).toDouble()).toFloat()
        degrees = abs(degrees)
        if (degrees > 180f) degrees = 360f - degrees
        return degrees
    }

    /**
     * NOTE on a known MediaPipe Android quirk: visibility() is documented as
     * always populated, but there's a longstanding open issue
     * (google-ai-edge/mediapipe#4536) where it comes back empty on Android
     * in some configurations. When absent, this treats the landmark as
     * visible rather than rejecting it — otherwise every angle silently
     * returns null on affected devices/model versions, which is worse
     * (looks like "no pose detected" instead of "no confidence data").
     * The spike's HUD will make this visible either way: if knee angles
     * never populate at all on your device, check this first.
     */
    private fun isVisible(landmark: NormalizedLandmark, threshold: Float): Boolean {
        val visibility = landmark.visibility()
        return !visibility.isPresent || visibility.get() >= threshold
    }

    fun kneeAngle(landmarks: List<NormalizedLandmark>, leftSide: Boolean): Float? {
        val hip = landmarks.getOrNull(if (leftSide) Landmark.LEFT_HIP else Landmark.RIGHT_HIP)
        val knee = landmarks.getOrNull(if (leftSide) Landmark.LEFT_KNEE else Landmark.RIGHT_KNEE)
        val ankle = landmarks.getOrNull(if (leftSide) Landmark.LEFT_ANKLE else Landmark.RIGHT_ANKLE)
        if (hip == null || knee == null || ankle == null) return null
        return angleDegrees(hip, knee, ankle)
    }

    fun elbowAngle(landmarks: List<NormalizedLandmark>, leftSide: Boolean): Float? {
        val shoulder = landmarks.getOrNull(if (leftSide) Landmark.LEFT_SHOULDER else Landmark.RIGHT_SHOULDER)
        val elbow = landmarks.getOrNull(if (leftSide) Landmark.LEFT_ELBOW else Landmark.RIGHT_ELBOW)
        val wrist = landmarks.getOrNull(if (leftSide) Landmark.LEFT_WRIST else Landmark.RIGHT_WRIST)
        if (shoulder == null || elbow == null || wrist == null) return null
        return angleDegrees(shoulder, elbow, wrist)
    }

    fun hipAngle(landmarks: List<NormalizedLandmark>, leftSide: Boolean): Float? {
        val shoulder = landmarks.getOrNull(if (leftSide) Landmark.LEFT_SHOULDER else Landmark.RIGHT_SHOULDER)
        val hip = landmarks.getOrNull(if (leftSide) Landmark.LEFT_HIP else Landmark.RIGHT_HIP)
        val knee = landmarks.getOrNull(if (leftSide) Landmark.LEFT_KNEE else Landmark.RIGHT_KNEE)
        if (shoulder == null || hip == null || knee == null) return null
        return angleDegrees(shoulder, hip, knee)
    }
}

/**
 * Exponential moving average smoother. Exists so the on-screen overlay can
 * show BOTH raw and smoothed angle side by side — that comparison is the
 * actual jitter measurement the spike is for. If raw and smoothed track
 * closely, MediaPipe's native signal is clean enough to rep-count directly.
 * If they diverge a lot frame to frame, rep-counting logic needs a
 * heavier filter (or a minimum-frames-per-state debounce) before it ships.
 */
class AngleSmoother(private val alpha: Float = 0.35f) {
    private var smoothed: Float? = null

    fun update(raw: Float?): Float? {
        if (raw == null) return smoothed
        val prev = smoothed
        smoothed = if (prev == null) raw else (alpha * raw + (1 - alpha) * prev)
        return smoothed
    }

    fun reset() {
        smoothed = null
    }
}
