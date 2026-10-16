package com.n44r.app.fit

import com.n44r.app.session.HrAnalysis
import com.n44r.app.session.PhaseType
import com.n44r.app.session.SessionMode
import com.n44r.app.session.SessionSummary
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Minimal, self-contained FIT encoder. Message order (as Garmin devices write it):
 * file_id, event(timer start), record x N (exactly one per second), event(timer stop_all),
 * lap x N, session, activity.
 *
 * IMPORTANT: sport/sub_sport are deliberately running(1)/treadmill(1), NOT rowing/indoor_rowing:
 * Strava has a display bug for indoor_rowing (no distance / wrong moving time). Consequences of
 * the "run" disguise that are handled here:
 *  - Cadence: for runs, FIT cadence is strides/min of ONE leg and Strava/Garmin show it doubled
 *    as steps/min. So the stroke rate is written halved (+ fractional_cadence for half strokes),
 *    then Strava shows the real stroke rate (e.g. 30) instead of 60. Switch: [HALVE_CADENCE].
 *  - Power is a regular running-power field - Strava shows avg/max watts and the power curve.
 *  - Calories come from the S4 kcal counter (Strava otherwise estimates a run from pace/weight).
 *
 * Records: the S4 sends every ~1.15 s, so writing "one record per received sample, max one per
 * second" left every ~8th second empty (2026-10-15: 1839 records for 2151 s). Now resampled onto
 * a strict 1 s grid: distance interpolated linearly, other values from the latest sample.
 * HR: short dropouts interpolated (HrAnalysis.clean), longer gaps written as invalid (0xFF).
 * All timestamps use the ACTIVE session timeline (start + unpaused time), same as laps/session.
 */
@OptIn(ExperimentalUnsignedTypes::class)
object FitWriter {
    private const val FIT_EPOCH_OFFSET_SEC = 631065600L
    const val HALVE_CADENCE = true
    const val PRODUCT_NAME = "Nordic4x4 Rower"

    private val CRC_TABLE: UIntArray = uintArrayOf(
        0x0000u, 0xCC01u, 0xD801u, 0x1400u, 0xF001u, 0x3C00u, 0x2800u, 0xE401u,
        0xA001u, 0x6C00u, 0x7800u, 0xB401u, 0x5000u, 0x9C01u, 0x8801u, 0x4400u
    )

    private fun fitTime(unixMs: Long) = unixMs / 1000L - FIT_EPOCH_OFFSET_SEC

    // Explicit unsigned arithmetic - an earlier signed-Int version produced wrong CRCs.
    private fun crc16(bytes: ByteArray): Int {
        var crc: UInt = 0u
        for (b in bytes) {
            val byteVal = (b.toInt() and 0xFF).toUInt()
            var tmp = CRC_TABLE[(crc and 0xFu).toInt()]
            crc = (crc shr 4) and 0x0FFFu
            crc = crc xor tmp xor CRC_TABLE[(byteVal and 0xFu).toInt()]
            tmp = CRC_TABLE[(crc and 0xFu).toInt()]
            crc = (crc shr 4) and 0x0FFFu
            crc = crc xor tmp xor CRC_TABLE[((byteVal shr 4) and 0xFu).toInt()]
        }
        return (crc and 0xFFFFu).toInt()
    }

    private fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)
    private fun ByteArrayOutputStream.u16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
    private fun ByteArrayOutputStream.u32(v: Long) {
        write((v and 0xFF).toInt()); write(((v shr 8) and 0xFF).toInt())
        write(((v shr 16) and 0xFF).toInt()); write(((v shr 24) and 0xFF).toInt())
    }

    // ---- generic message definition / data writer --------------------------------------------
    private const val ENUM = 0x00
    private const val U8 = 0x02
    private const val U16 = 0x84
    private const val U32 = 0x86
    private const val STR = 0x07
    /** Marker for "no value": written as all bits set = FIT invalid for enum/uint types. */
    private const val NA = Long.MIN_VALUE

    private class Field(val num: Int, val size: Int, val type: Int)
    private class Msg(val local: Int, val global: Int, vararg f: Field) { val fields = f.toList() }

    private fun ByteArrayOutputStream.define(m: Msg) {
        u8(0x40 or m.local); u8(0); u8(0); u16(m.global); u8(m.fields.size)
        m.fields.forEach { u8(it.num); u8(it.size); u8(it.type) }
    }

    /** Values: Long/Int for numbers ([NA] = invalid), String for string fields. */
    private fun ByteArrayOutputStream.data(m: Msg, vararg v: Any) {
        require(v.size == m.fields.size) { "FIT msg ${m.global}: ${v.size} values for ${m.fields.size} fields" }
        u8(m.local)
        m.fields.forEachIndexed { i, f ->
            when (val x = v[i]) {
                is String -> {
                    val b = x.toByteArray(Charsets.UTF_8).copyOf(f.size - 1)
                    write(b); write(0)
                }
                else -> {
                    val n = (x as Number).toLong().let { if (it == NA) -1L else it }
                    for (k in 0 until f.size) write(((n shr (8 * k)) and 0xFF).toInt())
                }
            }
        }
    }

    private fun u8OrNa(v: Int?) = v?.coerceIn(0, 254)?.toLong() ?: NA
    private fun u16OrNa(v: Int?) = v?.coerceIn(0, 65534)?.toLong() ?: NA

    /** FIT cadence (whole) + fractional_cadence (1/128) for a stroke rate, see class doc. */
    private fun cadence(spm: Float?): Pair<Long, Long> {
        if (spm == null || spm <= 0f) return 0L to 0L
        val c = if (HALVE_CADENCE) spm / 2f else spm
        val whole = floor(c)
        return whole.toLong().coerceIn(0, 254) to ((c - whole) * 128).roundToInt().coerceIn(0, 127).toLong()
    }

    // ---- 1 s grid ------------------------------------------------------------------------------
    /** One sample per second of active session time (index = second). */
    internal class Sec(val distM: Double, val speedMps: Double, val powerW: Int, val spm: Float, val hr: Int?)

    internal fun resample(summary: SessionSummary): List<Sec> {
        val recs = summary.records
        if (recs.isEmpty()) return emptyList()
        val hr = HrAnalysis.clean(recs).bpm
        val out = ArrayList<Sec>(summary.totalDurationSec + 1)
        var j = 0
        for (s in 0..summary.totalDurationSec) {
            val t = s * 1000L
            while (j + 1 < recs.size && recs[j + 1].sessionElapsedMs <= t) j++
            val before = t < recs[0].sessionElapsedMs
            val r = recs[j]
            // linear distance interpolation between the surrounding samples ((0 s, 0 m) before the first)
            val (t0, d0) = if (before) 0L to 0.0 else r.sessionElapsedMs to r.distanceM.toDouble()
            val nxt = if (before) recs[0] else recs.getOrNull(j + 1)
            val dist = if (nxt == null || nxt.sessionElapsedMs <= t0) d0
                       else d0 + (nxt.distanceM - d0) * (t - t0).toDouble() / (nxt.sessionElapsedMs - t0)
            val speed = r.paceSecPer500m?.takeIf { it > 0 }?.let { 500.0 / it }?.coerceAtMost(10.0) ?: 0.0
            out.add(Sec(dist, speed, r.powerW.coerceAtLeast(0), r.strokeRate, hr[j]))
        }
        return out
    }

    private fun kcalWeight(s: Sec) = (4 * 0.8604 * s.powerW + 300) / 3600.0

    fun write(outFile: File, summary: SessionSummary) {
        val body = ByteArrayOutputStream()
        val secs = resample(summary)
        val base = fitTime(summary.startedAtMs)
        val end = base + summary.totalDurationSec

        // file_id: type(0) manufacturer(1) product(2) time_created(4) product_name(8)
        val fileId = Msg(0, 0, Field(0, 1, ENUM), Field(1, 2, U16), Field(2, 2, U16), Field(4, 4, U32),
            Field(8, 20, STR))
        body.define(fileId)
        body.data(fileId, 4, 255, 1, base, PRODUCT_NAME)  // activity, development

        // event: timestamp(253) event(0) event_type(1) event_group(4)
        val event = Msg(1, 21, Field(253, 4, U32), Field(0, 1, ENUM), Field(1, 1, ENUM), Field(4, 1, U8))
        body.define(event)
        body.data(event, base, 0, 0, 0)  // timer start

        // record: timestamp distance speed power cadence fractional_cadence heart_rate
        val record = Msg(2, 20, Field(253, 4, U32), Field(5, 4, U32), Field(6, 2, U16), Field(7, 2, U16),
            Field(4, 1, U8), Field(53, 1, U8), Field(3, 1, U8))
        body.define(record)
        secs.forEachIndexed { s, p ->
            val (cad, frac) = cadence(p.spm)
            body.data(record, base + s, (p.distM * 100).roundToInt().toLong(),
                (p.speedMps * 1000).roundToInt().toLong(), p.powerW.coerceAtMost(65534).toLong(),
                cad, frac, u8OrNa(p.hr?.coerceAtLeast(1)))
        }
        body.data(event, end, 0, 4, 0)  // timer stop_all

        writeLaps(body, summary, secs, base)
        writeSessionAndActivity(body, summary, secs, base, end)

        val bodyBytes = body.toByteArray()
        val header = ByteArrayOutputStream()
        header.u8(12); header.u8(0x10); header.u16(2178); header.u32(bodyBytes.size.toLong())
        header.write(".FIT".toByteArray(Charsets.US_ASCII))
        val file = ByteArrayOutputStream()
        file.write(header.toByteArray()); file.write(bodyBytes)
        file.u16(crc16(file.toByteArray()))
        outFile.writeBytes(file.toByteArray())
    }

    /** Summary values over a range of seconds (lap or whole session). */
    private class Stats(secs: List<Sec>, distM: Double, durSec: Int) {
        val hrs = secs.mapNotNull { it.hr }
        val avgHr: Int? = if (hrs.isEmpty()) null else hrs.average().roundToInt()
        val maxHr: Int? = hrs.maxOrNull()
        val avgSpeed: Int = if (durSec > 0) (distM / durSec * 1000).roundToInt() else 0
        // instantaneous S4 pace is smoothed -> its max can lie below the distance-based average
        val maxSpeed: Int = maxOf(avgSpeed, ((secs.maxOfOrNull { it.speedMps } ?: 0.0) * 1000).roundToInt())
        private val rowing = secs.filter { it.spm > 0f }
        val avgSpm: Float? = if (rowing.isEmpty()) null else rowing.map { it.spm }.average().toFloat()
        val maxSpm: Float? = rowing.maxOfOrNull { it.spm }
        val avgPower: Int = if (secs.isEmpty()) 0 else secs.map { it.powerW }.average().roundToInt()
        val maxPower: Int = secs.maxOfOrNull { it.powerW } ?: 0
    }

    private fun intensity(t: PhaseType) = when (t) {
        PhaseType.REST -> 1; PhaseType.WARMUP -> 2; PhaseType.COOLDOWN -> 3; else -> 0
    }

    /** Laps worth writing: a 0-s / 0-m lap (finish right at a phase boundary) shows up as an
     *  empty "0:00" lap in Strava - it is skipped (num_laps counts only the written ones). */
    private fun visibleLaps(summary: SessionSummary) =
        summary.laps.withIndex().filter { (_, l) -> l.durationSec > 0 || l.endDistanceM > l.startDistanceM }

    private fun writeLaps(body: ByteArrayOutputStream, summary: SessionSummary, secs: List<Sec>, base: Long) {
        // per-lap calories: session total split by a power-based weighting of each lap
        val totalWeight = secs.sumOf { kcalWeight(it) }.takeIf { it > 0 } ?: 1.0
        val lap = Msg(3, 19,
            Field(253, 4, U32), Field(2, 4, U32), Field(7, 4, U32), Field(8, 4, U32), Field(9, 4, U32),
            Field(254, 2, U16), Field(0, 1, ENUM), Field(1, 1, ENUM),
            Field(11, 2, U16), Field(13, 2, U16), Field(14, 2, U16),
            Field(15, 1, U8), Field(16, 1, U8), Field(17, 1, U8), Field(18, 1, U8), Field(80, 1, U8),
            Field(19, 2, U16), Field(20, 2, U16),
            Field(23, 1, ENUM), Field(24, 1, ENUM), Field(25, 1, ENUM), Field(39, 1, ENUM))
        body.define(lap)
        var cursor = 0
        val visible = visibleLaps(summary).map { it.index }.toSet()
        var msgIndex = 0
        summary.laps.forEachIndexed { i, l ->
            val from = cursor.coerceAtMost(secs.size)
            cursor += l.durationSec
            if (i !in visible) return@forEachIndexed
            val to = cursor.coerceAtMost(secs.size)
            val part = secs.subList(from, to)
            val dist = (l.endDistanceM - l.startDistanceM).toDouble()
            val st = Stats(part, dist, l.durationSec)
            val kcal = summary.totalEnergyKcal * part.sumOf { kcalWeight(it) } / totalWeight
            val (avgCad, avgFrac) = cadence(st.avgSpm)
            val (maxCad, _) = cadence(st.maxSpm)
            val last = i == visible.maxOrNull()
            val trigger = when {
                last -> 7                                  // session_end
                summary.mode == SessionMode.DISTANCE -> 2  // distance
                else -> 1                                  // time
            }
            body.data(lap,
                base + cursor, base + from, l.durationSec * 1000L, l.durationSec * 1000L, (dist * 100).roundToInt(),
                msgIndex++, 9, 1,                          // message_index, lap / stop
                kcal.roundToInt(), st.avgSpeed, st.maxSpeed,
                u8OrNa(st.avgHr), u8OrNa(st.maxHr), avgCad, maxCad, avgFrac,
                st.avgPower, st.maxPower,
                intensity(l.phaseType), trigger, 1, 1)     // sport running, sub_sport treadmill
        }
    }

    private fun writeSessionAndActivity(body: ByteArrayOutputStream, summary: SessionSummary, secs: List<Sec>,
                                        base: Long, end: Long) {
        val dur = summary.totalDurationSec
        val st = Stats(secs, summary.totalDistanceM.toDouble(), dur)
        val (avgCad, avgFrac) = cadence(st.avgSpm)
        val (maxCad, _) = cadence(st.maxSpm)
        val session = Msg(4, 18,
            Field(253, 4, U32), Field(2, 4, U32), Field(7, 4, U32), Field(8, 4, U32), Field(9, 4, U32),
            Field(254, 2, U16), Field(0, 1, ENUM), Field(1, 1, ENUM), Field(28, 1, ENUM),
            Field(5, 1, ENUM), Field(6, 1, ENUM), Field(11, 2, U16),
            Field(14, 2, U16), Field(15, 2, U16), Field(16, 1, U8), Field(17, 1, U8),
            Field(18, 1, U8), Field(19, 1, U8), Field(92, 1, U8), Field(20, 2, U16), Field(21, 2, U16),
            Field(25, 2, U16), Field(26, 2, U16))
        body.define(session)
        body.data(session,
            end, base, dur * 1000L, dur * 1000L, summary.totalDistanceM * 100L,
            0, 8, 1, 0,                                    // session / stop / activity_end
            1, 1, u16OrNa(summary.totalEnergyKcal),        // running / treadmill (see class doc)
            st.avgSpeed, st.maxSpeed, u8OrNa(st.avgHr), u8OrNa(summary.maxHeartRateBpm ?: st.maxHr),
            avgCad, maxCad, avgFrac, st.avgPower, st.maxPower,
            0, visibleLaps(summary).size)

        // activity: timestamp total_timer_time num_sessions type event event_type local_timestamp
        val activity = Msg(5, 34, Field(253, 4, U32), Field(0, 4, U32), Field(1, 2, U16), Field(2, 1, ENUM),
            Field(3, 1, ENUM), Field(4, 1, ENUM), Field(5, 4, U32))
        body.define(activity)
        val tzOffsetSec = TimeZone.getDefault().getOffset(summary.startedAtMs) / 1000
        body.data(activity, end, dur * 1000L, 1, 0, 26, 1, end + tzOffsetSec)
    }
}
