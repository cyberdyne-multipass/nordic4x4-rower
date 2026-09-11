package com.n44r.app.session

/**
 * Auto-pause: if distance and stroke count haven't moved for [stillnessThresholdSec],
 * we consider the rower stopped (not just in a slow interval recovery phase, which
 * still shows distance changing). Separate from the manual pause button.
 */
class PauseDetector(private val stillnessThresholdSec: Int = 5) {
    private var lastDistance: Int? = null
    private var lastStrokeCount: Int? = null
    private var lastChangeTimeMs: Long = 0L

    fun update(distanceM: Int, strokeCount: Int, nowMs: Long): Boolean {
        if (lastChangeTimeMs == 0L) lastChangeTimeMs = nowMs
        if (distanceM != lastDistance || strokeCount != lastStrokeCount) {
            lastDistance = distanceM
            lastStrokeCount = strokeCount
            lastChangeTimeMs = nowMs
            return false
        }
        return (nowMs - lastChangeTimeMs) / 1000 >= stillnessThresholdSec
    }

    fun reset() {
        lastDistance = null
        lastStrokeCount = null
        lastChangeTimeMs = 0L
    }
}

/** Pace computed only from movement since the current phase started (resets on every phase change). */
class SplitTracker {
    private var startDistanceM = 0
    private var startTimeMs = 0L

    fun reset(distanceM: Int, timeMs: Long) {
        startDistanceM = distanceM
        startTimeMs = timeMs
    }

    fun currentSplitPaceSecPer500m(distanceM: Int, timeMs: Long): Int? {
        val deltaDistance = distanceM - startDistanceM
        if (deltaDistance <= 0) return null
        val deltaTimeSec = (timeMs - startTimeMs) / 1000.0
        return ((deltaTimeSec / deltaDistance) * 500).toInt()
    }
}
