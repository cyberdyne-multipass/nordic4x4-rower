package com.n44r.app

import com.n44r.app.ble.HeartRateParser
import com.n44r.app.ble.RowerDataParser
import com.n44r.app.fit.FitWriter
import com.n44r.app.session.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Replays a real raw BLE log (Rower Data + Heart Rate lines) through the session logic and writes
 * a FIT next to it. The log contains personal HR data, so it is NOT in the (public) repo: the test
 * is skipped unless the file exists at ~/temp/n44r_2026-10-15_rawble.txt (Hans' PC).
 * 2026-10-15: first real workout with the HRM 600 - Norwegian 4x4, 8051 m, S4 said 421 kcal.
 */
class ReplayRealSessionTest {
    private val log = File(System.getProperty("user.home"), "temp/n44r_2026-10-15_rawble.txt")
    private val day = LocalDate.of(2026, 10, 15)
    private val startMs = 1_792_080_037_491L
    private val line = Regex("""^(\d\d):(\d\d):(\d\d)\.(\d{3}) (Rower Data|Heart Rate): \(0x\) ([0-9A-F-]+)""")

    @Test fun replay_2026_10_15() {
        assumeTrue("real log not present", log.exists())
        val midnight = day.atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        val plan = buildPhasePlan(SessionMode.INTERVAL, 0, 0, IntervalConfig(
            includeWarmup = true, warmupSec = 300, workSec = 240, restSec = 180, rounds = 4,
            includeCooldown = true, cooldownSec = 300))
        val sm = SessionStateMachine(plan, SessionMode.INTERVAL)
        sm.start(startMs)
        var endMs = Long.MAX_VALUE
        log.forEachLine { l ->
            if (l.endsWith("EVENT: Session beendet")) {
                val h = l.substring(0, 12).split(':', '.').map { it.toLong() }
                endMs = midnight + ((h[0] * 60 + h[1]) * 60 + h[2]) * 1000 + h[3]
            }
            val m = line.find(l) ?: return@forEachLine
            val (hh, mm, ss, ms, kind, hex) = m.destructured
            val t = midnight + ((hh.toLong() * 60 + mm.toLong()) * 60 + ss.toLong()) * 1000 + ms.toLong()
            if (t < startMs || t > endMs) return@forEachLine
            val raw = hex.split('-').map { it.toInt(16).toByte() }.toByteArray()
            if (kind == "Heart Rate") HeartRateParser.parse(raw)?.let { sm.setHeartRate(it.bpm) }
            else RowerDataParser.parse(raw)?.let { sm.onData(it.copy(timestampMs = t)) }
        }
        val s = sm.finish()
        println("duration ${s.totalDurationSec} s, distance ${s.totalDistanceM} m, kcal ${s.totalEnergyKcal}, laps ${s.laps.size}")
        s.laps.forEach { println("  ${it.label}: ${it.durationSec} s, ${it.endDistanceM - it.startDistanceM} m, ${it.avgPowerW} W") }
        s.hrMetrics.intervals.forEach { println("  T90 ${it.riseT90Sec} s, HRR60 ${it.hrr60}") }
        val out = File(log.parentFile, "n44r_2026-10-15_neu.fit")
        FitWriter.write(out, s)
        println("FIT: $out")
        assertEquals(8051.0, s.totalDistanceM.toDouble(), 5.0)
        assertTrue("kcal ${s.totalEnergyKcal}", s.totalEnergyKcal in 380..440)
        assertEquals(s.totalDurationSec + 1, FitWriter.resample(s).size)
    }
}
