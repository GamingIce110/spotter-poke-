package com.spotter.posespike

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.spotter.posespike.camera.CameraController
import com.spotter.posespike.databinding.ActivityMainBinding
import com.spotter.posespike.pose.PoseLandmarkerHelper
import com.spotter.posespike.util.PerformanceTracker

/**
 * Entry point for the pose estimation technical spike.
 *
 * Deliberately minimal: no auth, no nav graph, no DB. Single screen whose
 * only job is proving out real-device fps/latency/jitter numbers for the
 * MediaPipe Pose Landmarker pipeline before that pipeline gets built into
 * the full Spotter app (rep counting, form scoring, etc. all sit on top
 * of what this screen measures).
 */
class MainActivity : AppCompatActivity(), PoseLandmarkerHelper.LandmarkerListener {

    private lateinit var binding: ActivityMainBinding
    private var cameraController: CameraController? = null
    private var poseLandmarkerHelper: PoseLandmarkerHelper? = null
    private val performanceTracker = PerformanceTracker()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                binding.permissionMessage.visibility = android.view.View.GONE
                initPoseAndCamera()
            } else {
                binding.permissionMessage.visibility = android.view.View.VISIBLE
                Toast.makeText(
                    this,
                    "Camera permission denied — spike cannot run without it",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.permissionMessage.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        if (hasCameraPermission()) {
            initPoseAndCamera()
        } else {
            binding.permissionMessage.visibility = android.view.View.VISIBLE
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun initPoseAndCamera() {
        // previewView.post ensures the view has been laid out (non-zero
        // width/height) before CameraX binds to it — binding too early is
        // a common source of a black preview on first launch.
        binding.previewView.post {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = this,
                listener = this,
            )

            cameraController = CameraController(
                context = this,
                lifecycleOwner = this,
                previewView = binding.previewView,
                poseLandmarkerHelper = poseLandmarkerHelper!!,
            ).also { controller ->
                controller.start { error ->
                    runOnUiThread {
                        Log.e(TAG, "Camera start failed: $error")
                        Toast.makeText(this, "Camera error: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.PoseResultBundle) {
        performanceTracker.recordFrame(System.currentTimeMillis())
        performanceTracker.recordInferenceLatency(resultBundle.inferenceTimeMs)

        val landmarks = resultBundle.results.landmarks().firstOrNull() ?: emptyList()

        runOnUiThread {
            binding.poseOverlay.updateResults(
                newLandmarks = landmarks,
                srcWidth = resultBundle.inputImageWidth,
                srcHeight = resultBundle.inputImageHeight,
                currentFps = performanceTracker.currentFps(),
                avgLatency = performanceTracker.averageInferenceLatencyMs(),
                p95Latency = performanceTracker.p95InferenceLatencyMs(),
                delegate = poseLandmarkerHelper?.currentDelegate?.name ?: "?",
            )
        }
    }

    override fun onError(error: String) {
        Log.e(TAG, "Pose landmarker error: $error")
        runOnUiThread {
            Toast.makeText(this, "Pose error: $error", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Order matters: stop the camera feed (and its analysis executor)
        // before closing the landmarker, so no in-flight frame calls
        // detectAsync() against an already-closed native handle.
        cameraController?.stop()
        poseLandmarkerHelper?.close()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
