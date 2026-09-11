package com.n44r.app.ble

import com.n44r.app.session.PhaseType
import kotlin.random.Random

private const val DEMO_SEED = 42L

/**
 * Stateful wrapper around the real-data curves in DemoRowerDataSource. Tracks distance/stroke
 * accumulators and seconds-in-phase (reset when the actual session phase changes), and
 * rate-limits pace and heart rate toward the curve target so phase transitions look like a
 * real body (HR can't jump 30 bpm in a second) instead of a table lookup. Fixed seed keeps it
 * deterministic for demo seek/replay.
 */
class DemoGenerator(seed: Long = DEMO_SEED) {
    private val rnd = Random(seed)
    private var distanceAccum = 0.0
    private var strokeCountAccum = 0
    private var lastPhase: PhaseType? = null
    private var ticksInPhase = 0
    private var curPace = -1
    private var curHr = -1

    fun next(tick: Int, distanceScale: Double, phase: PhaseType): RowerData {
        if (phase != lastPhase) { lastPhase = phase; ticksInPhase = 0 }
        val target = DemoRowerDataSource.sample(DemoRowerDataSource.curveFor(phase), ticksInPhase)
        ticksInPhase++

        // first sample: start on the curve; afterwards approach it with a per-second rate limit
        curPace = if (curPace < 0) target.pace else approach(curPace, target.pace, 6)
        curHr = if (curHr < 0) target.hr else approach(curHr, target.hr, 2)

        val pace = (curPace + DemoRowerDataSource.jitter(rnd, 2)).coerceAtLeast(60)
        val hr = (curHr + DemoRowerDataSource.jitter(rnd, 1)).coerceIn(40, 200)
        val strokeRate = (target.sr + DemoRowerDataSource.jitter(rnd, 1)).coerceIn(8, 40).toFloat()
        val power = (target.pw + DemoRowerDataSource.jitter(rnd, 8)).coerceAtLeast(0)

        distanceAccum += (500.0 / pace) * distanceScale
        strokeCountAccum += 1
        return RowerData(
            strokeRatePerMin = strokeRate,
            strokeCount = strokeCountAccum,
            totalDistanceMeters = distanceAccum.toInt(),
            instantaneousPaceSecPer500m = pace,
            instantaneousPowerWatts = power,
            totalEnergyKcal = (distanceAccum / 250).toInt(),
            energyPerHourKcal = 0,
            energyPerMinuteKcal = 0,
            heartRateBpm = hr,
            elapsedTimeSec = tick
        )
    }

    private fun approach(current: Int, target: Int, maxStep: Int): Int =
        current + (target - current).coerceIn(-maxStep, maxStep)
}
