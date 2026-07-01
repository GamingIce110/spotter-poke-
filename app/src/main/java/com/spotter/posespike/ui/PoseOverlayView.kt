package com.spotter.posespike.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.spotter.posespike.pose.AngleSmoother
import com.spotter.posespike.pose.JointAngleCalculator

/**
 * Draws the skeleton overlay plus a debug HUD (fps, inference latency,
 * raw vs. smoothed knee angle). The raw-vs-smoothed comparison on screen
 * is the actual deliverable of this spike — it's the fastest way to
 * visually judge "is this signal clean enough to rep-count on" without
 * exporting data and analyzing it separately first.
 */
class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var landmarks: List<NormalizedLandmark> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1

    private var fps: Float? = null
    private var avgLatencyMs: Long? = null
    private var p95LatencyMs: Long? = null
    private var delegateName: String = "?"

    private val rawKneeSmoother = AngleSmoother()
    private var rawKneeAngle: Float? = null
    private var smoothedKneeAngle: Float? = null

    private val pointPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        strokeWidth = 8f
    }

    private val linePaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val lowConfidencePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        strokeWidth = 8f
    }

    private val hudPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        style = Paint.Style.FILL
    }

    private val hudBackgroundPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    fun updateResults(
        newLandmarks: List<NormalizedLandmark>,
        srcWidth: Int,
        srcHeight: Int,
        currentFps: Float?,
        avgLatency: Long?,
        p95Latency: Long?,
        delegate: String,
    ) {
        landmarks = newLandmarks
        imageWidth = srcWidth
        imageHeight = srcHeight
        fps = currentFps
        avgLatencyMs = avgLatency
        p95LatencyMs = p95Latency
        delegateName = delegate

        rawKneeAngle = JointAngleCalculator.kneeAngle(newLandmarks, leftSide = true)
        smoothedKneeAngle = rawKneeSmoother.update(rawKneeAngle)

        invalidate()
    }

    fun clear() {
        landmarks = emptyList()
        rawKneeSmoother.reset()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawSkeleton(canvas)
        drawHud(canvas)
    }

    private fun drawSkeleton(canvas: Canvas) {
        if (landmarks.isEmpty()) return

        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight

        // Draw connections first so joint dots render on top
        for (connection in POSE_CONNECTIONS) {
            val start = landmarks.getOrNull(connection.first) ?: continue
            val end = landmarks.getOrNull(connection.second) ?: continue
            canvas.drawLine(
                start.x() * width,
                start.y() * height,
                end.x() * width,
                end.y() * height,
                linePaint,
            )
        }

        for (landmark in landmarks) {
            val visibility = landmark.visibility()
            val paint = if (visibility.isPresent && visibility.get() < 0.5f) {
                lowConfidencePaint
            } else {
                pointPaint
            }
            canvas.drawCircle(landmark.x() * width, landmark.y() * height, 10f, paint)
        }
    }

    private fun drawHud(canvas: Canvas) {
        val lines = listOf(
            "delegate: $delegateName",
            "fps: ${fps?.let { "%.1f".format(it) } ?: "—"}",
            "inference avg/p95: ${avgLatencyMs ?: "—"}ms / ${p95LatencyMs ?: "—"}ms",
            "knee raw: ${rawKneeAngle?.let { "%.1f°".format(it) } ?: "—"}",
            "knee smoothed: ${smoothedKneeAngle?.let { "%.1f°".format(it) } ?: "—"}",
        )

        val padding = 20f
        val lineHeight = 50f
        val boxHeight = lineHeight * lines.size + padding * 2
        canvas.drawRect(0f, 0f, width.toFloat(), boxHeight, hudBackgroundPaint)

        lines.forEachIndexed { index, line ->
            canvas.drawText(line, padding, padding + lineHeight * (index + 1), hudPaint)
        }
    }

    companion object {
        // BlazePose 33-point skeleton connections, limbs + torso only
        // (excludes the 468-point face mesh subset since it's irrelevant
        // for lift form analysis and just adds visual noise to the spike).
        private val POSE_CONNECTIONS = listOf(
            11 to 12, // shoulders
            11 to 13, 13 to 15, // left arm
            12 to 14, 14 to 16, // right arm
            11 to 23, 12 to 24, // torso sides
            23 to 24, // hips
            23 to 25, 25 to 27, // left leg
            24 to 26, 26 to 28, // right leg
            27 to 31, 28 to 32, // ankles to feet
        )
    }
}
