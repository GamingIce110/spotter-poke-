package com.spotter.posespike.util

/**
 * Rolling-window fps and inference-latency tracker. This produces the
 * literal numbers the PRD's open question #1 and #3 need answered:
 * "what's the real fps, and does it hold up over a full set (60-90s),
 * not just the first few frames while thermals are cold."
 *
 * Window size of 60 samples ~= last 2 seconds at 30fps target, which is
 * responsive enough to see thermal throttling kick in mid-set rather than
 * being smoothed away by an all-time average.
 */
class PerformanceTracker(private val windowSize: Int = 60) {

    private val frameTimestamps = ArrayDeque<Long>(windowSize)
    private val inferenceLatencies = ArrayDeque<Long>(windowSize)

    fun recordFrame(timestampMs: Long) {
        frameTimestamps.addLast(timestampMs)
        if (frameTimestamps.size > windowSize) frameTimestamps.removeFirst()
    }

    fun recordInferenceLatency(latencyMs: Long) {
        inferenceLatencies.addLast(latencyMs)
        if (inferenceLatencies.size > windowSize) inferenceLatencies.removeFirst()
    }

    /** Rolling fps over the current window. Null until enough samples exist. */
    fun currentFps(): Float? {
        if (frameTimestamps.size < 2) return null
        val elapsedMs = frameTimestamps.last() - frameTimestamps.first()
        if (elapsedMs <= 0) return null
        return (frameTimestamps.size - 1) * 1000f / elapsedMs
    }

    fun averageInferenceLatencyMs(): Long? {
        if (inferenceLatencies.isEmpty()) return null
        return inferenceLatencies.sum() / inferenceLatencies.size
    }

    fun p95InferenceLatencyMs(): Long? {
        if (inferenceLatencies.isEmpty()) return null
        val sorted = inferenceLatencies.sorted()
        val index = ((sorted.size - 1) * 0.95).toInt()
        return sorted[index]
    }

    fun reset() {
        frameTimestamps.clear()
        inferenceLatencies.clear()
    }
}
