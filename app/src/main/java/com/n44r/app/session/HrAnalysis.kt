package com.n44r.app.session

import kotlin.math.roundToInt

/** Per work interval: how fast HR rose at the start and how fast it fell afterwards. */
data class IntervalHr(
    val number: Int,
    /** Seconds from interval start until HR covered 90 % of its rise to the plateau. */
    val riseT90Sec: Int?,
    val startBpm: Int?,
    val plateauBpm: Int?,
    /** HR at the end of the interval. */
    val endBpm: Int?,
    /** Drop in bpm during the 60 s after the interval (Heart Rate Recovery, HRR60). */
    val hrr60: Int?
)

data class HrMetrics(val intervals: List<IntervalHr>) {
    val avgHrr60: Int? = intervals.mapNotNull { it.hrr60 }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    val avgRiseT90Sec: Int? = intervals.mapNotNull { it.riseT90Sec }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
}

/**
 * Heart-rate cleaning and physiology metrics.
 *
 * Cleaning: anything outside 30..230 bpm is "no data" (the Apple-Watch bridge sends 0 when the
 * watch delivers nothing). Gaps up to [MAX_INTERP_GAP_MS] are linearly interpolated (charts, FIT),
 * longer gaps stay empty - drawing a straight line over minutes of missing data would be invented.
 * Metrics only ever use REAL samples, never interpolated ones.
 */
object HrAnalysis {
    const val MAX_INTERP_GAP_MS = 60_000L

    fun isValid(bpm: Int?): Boolean = bpm != null && bpm in 30..230

    /** HRR as shown to the user: a drop is "−34 bpm", a (rare) rise "+3 bpm". */
    fun formatHrr(v: Int): String = if (v >= 0) "−$v bpm" else "+${-v} bpm"

    /** One value per record: real or interpolated HR, or null; plus which ones are interpolated. */
    class Cleaned(val bpm: Array<Int?>, val interpolated: BooleanArray)

    fun clean(records: List<RecordPoint>): Cleaned {
        val n = records.size
        val out = arrayOfNulls<Int>(n)
        val interp = BooleanArray(n)
        var prev = -1
        for (i in 0 until n) {
            if (!isValid(records[i].heartRateBpm)) continue
            out[i] = records[i].heartRateBpm
            if (prev >= 0 && i - prev > 1) {
                val t0 = records[prev].sessionElapsedMs; val t1 = records[i].sessionElapsedMs
                if (t1 - t0 <= MAX_INTERP_GAP_MS && t1 > t0) {
                    val v0 = records[prev].heartRateBpm!!; val v1 = records[i].heartRateBpm!!
                    for (k in prev + 1 until i) {
                        val f = (records[k].sessionElapsedMs - t0).toDouble() / (t1 - t0)
                        out[k] = (v0 + (v1 - v0) * f).roundToInt()
                        interp[k] = true
                    }
                }
            }
            prev = i
        }
        return Cleaned(out, interp)
    }

    fun maxHr(records: List<RecordPoint>): Int? = records.mapNotNull { it.heartRateBpm?.takeIf(::isValid) }.maxOrNull()

    /** Mean of the real, valid HR samples whose wall-clock time lies in [fromMs, toMs]. */
    private fun meanIn(records: List<RecordPoint>, fromMs: Long, toMs: Long): Double? {
        val v = records.filter { it.timestampMs in fromMs..toMs && isValid(it.heartRateBpm) }.map { it.heartRateBpm!! }
        return if (v.isEmpty()) null else v.average()
    }

    fun computeMetrics(records: List<RecordPoint>): HrMetrics {
        if (records.isEmpty()) return HrMetrics(emptyList())
        val result = mutableListOf<IntervalHr>()
        val workPhases = records.filter { it.phaseType == PhaseType.WORK }.map { it.phaseIndex }.distinct()
        workPhases.forEachIndexed { idx, phaseIndex ->
            val phaseRecs = records.filter { it.phaseIndex == phaseIndex }
            val startMs = phaseRecs.first().timestampMs
            val endMs = phaseRecs.last().timestampMs
            val durMs = endMs - startMs

            // --- rise (HR on-kinetics): only meaningful if the interval is long enough to plateau
            var rise: Int? = null
            val startBpm = meanIn(records, startMs - 5_000, startMs + 2_000)
            val plateau = if (durMs >= 90_000) meanIn(records, endMs - 60_000, endMs) else null
            if (startBpm != null && plateau != null && plateau - startBpm >= 10) {
                val threshold = startBpm + 0.9 * (plateau - startBpm)
                phaseRecs.firstOrNull { isValid(it.heartRateBpm) && it.heartRateBpm!! >= threshold }
                    ?.let { rise = ((it.timestampMs - startMs) / 1000).toInt() }
            }

            // --- recovery (HRR60): needs data 60 s after the interval end
            val endBpm = meanIn(records, endMs - 5_000, endMs)
            val after = meanIn(records, endMs + 55_000, endMs + 65_000)
            val hrr = if (endBpm != null && after != null) (endBpm - after).roundToInt() else null

            result.add(
                IntervalHr(
                    number = idx + 1, riseT90Sec = rise,
                    startBpm = startBpm?.roundToInt(), plateauBpm = plateau?.roundToInt(),
                    endBpm = endBpm?.roundToInt(), hrr60 = hrr
                )
            )
        }
        return HrMetrics(result)
    }
}
