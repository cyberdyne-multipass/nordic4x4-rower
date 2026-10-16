package com.n44r.app

import com.n44r.app.ble.RowerData
import com.n44r.app.fit.FitWriter
import com.n44r.app.session.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.exp

class RobustnessTest {

    private fun sample(t: Long, dist: Int, strokes: Int, pace: Int? = 120) = RowerData(
        strokeRatePerMin = 24f, strokeCount = strokes, totalDistanceMeters = dist,
        instantaneousPaceSecPer500m = pace, instantaneousPowerWatts = 150,
        totalEnergyKcal = 0, energyPerHourKcal = 0, energyPerMinuteKcal = 0,
        heartRateBpm = 0, elapsedTimeSec = 0, timestampMs = t
    )

    private fun rec(sec: Int, hr: Int?, phaseIndex: Int = 0, type: PhaseType = PhaseType.MAIN) = RecordPoint(
        timestampMs = 1_000_000L + sec * 1000L, sessionElapsedSec = sec, phaseType = type, phaseIndex = phaseIndex,
        distanceM = sec * 4, paceSecPer500m = 120, powerW = 150, strokeRate = 24f, heartRateBpm = hr
    )

    private val smallPlan = buildPhasePlan(
        SessionMode.INTERVAL, 0, 0,
        IntervalConfig(includeWarmup = true, warmupSec = 30, workSec = 40, restSec = 20, rounds = 2, includeCooldown = false)
    )

    @Test fun hrCleaning_interpolatesShortGaps_dropsZeros_keepsLongGapsEmpty() {
        val hrs = mutableListOf<Int?>(100, 0, null, 106)          // 0 and null -> interpolated 102, 104
        repeat(70) { hrs.add(0) }                                  // 70 s of zeros -> stays empty
        hrs.add(120)
        val records = hrs.mapIndexed { i, h -> rec(i, h) }
        val c = HrAnalysis.clean(records)
        assertEquals(102, c.bpm[1]); assertTrue(c.interpolated[1])
        assertEquals(104, c.bpm[2])
        assertEquals(106, c.bpm[3]); assertFalse(c.interpolated[3])
        assertNull(c.bpm[40])
        assertEquals(120, c.bpm.last())
        assertEquals(120, HrAnalysis.maxHr(records))
    }

    @Test fun hrMetrics_riseAndRecovery() {
        val records = mutableListOf<RecordPoint>()
        var s = 0
        repeat(60) { records.add(rec(s++, 100, 0, PhaseType.WARMUP)) }
        val workStart = s
        repeat(240) { records.add(rec(s, (100 + 60 * (1 - exp(-(s - workStart) / 30.0))).toInt(), 1, PhaseType.WORK)); s++ }
        val restStart = s
        repeat(120) { records.add(rec(s, (110 + 50 * exp(-(s - restStart) / 40.0)).toInt(), 2, PhaseType.REST)); s++ }
        val m = HrAnalysis.computeMetrics(records)
        assertEquals(1, m.intervals.size)
        val iv = m.intervals[0]
        println("rise=${iv.riseT90Sec} hrr60=${iv.hrr60} start=${iv.startBpm} plateau=${iv.plateauBpm}")
        assertTrue("rise ${iv.riseT90Sec}", iv.riseT90Sec!! in 62..75)   // tau*ln(10) = 69 s
        assertTrue("hrr ${iv.hrr60}", iv.hrr60!! in 35..42)              // 160 - 121 = 39
    }

    @Test fun gapWhileRowing_isCredited_gapWhileStill_isNot() {
        val sm = SessionStateMachine(listOf(PhaseSpec(PhaseType.MAIN, "T", targetTimeSec = 3600), PhaseSpec(PhaseType.FREE, "F")), SessionMode.TIME)
        var t = 0L; var d = 1000; var k = 0
        sm.start(t)
        repeat(10) { t += 1000; d += 4; k++; sm.onData(sample(t, d, k)) }
        assertEquals(10, sm.uiState.value.sessionElapsedSec)
        // 20 s outage while rowing on
        t += 20_000; d += 80; k += 8; sm.onData(sample(t, d, k))
        assertEquals(30, sm.uiState.value.sessionElapsedSec)
        assertEquals(9 * 4 + 80, sm.uiState.value.totalDistanceM) // first sample defines zero
        // monitor counter reset during an outage -> distance keeps counting from where it was
        t += 1000; d = 3; k = 1; sm.onData(sample(t, d, k))
        t += 1000; d = 7; k = 2; sm.onData(sample(t, d, k))
        assertEquals(9 * 4 + 80 + 4, sm.uiState.value.totalDistanceM)
        // 20 s gap WITHOUT movement (user stopped) -> not credited
        val before = sm.uiState.value.sessionElapsedSec
        t += 20_000; sm.onData(sample(t, d, k))
        assertEquals(before, sm.uiState.value.sessionElapsedSec)
    }

    @Test fun manualPause_visibleImmediately_andSplitNotDistorted() {
        val sm = SessionStateMachine(listOf(PhaseSpec(PhaseType.MAIN, "T", targetTimeSec = 3600), PhaseSpec(PhaseType.FREE, "F")), SessionMode.TIME)
        var t = 0L; var d = 0; var k = 0
        sm.start(t)
        repeat(50) { t += 1000; d += 4; k++; sm.onData(sample(t, d, k)) }
        sm.togglePause()
        assertTrue("pause must show without new data", sm.uiState.value.isManuallyPaused)
        repeat(60) { t += 1000; sm.onData(sample(t, d, k)) } // paused for 60 s
        sm.togglePause()
        repeat(75) { t += 1000; d += 4; k++; sm.onData(sample(t, d, k)) }
        assertEquals(125, sm.uiState.value.sessionElapsedSec)
        assertEquals(496, sm.uiState.value.totalDistanceM)
        assertEquals(126, sm.uiState.value.splitPaceSecPer500m) // 496 m in 125 s ACTIVE time (pause excluded)
    }

    @Test fun restore_continuesSeamlessly() {
        val sm1 = SessionStateMachine(smallPlan, SessionMode.INTERVAL)
        val journaled = mutableListOf<RecordPoint>()
        sm1.onRecord = { journaled.add(it) }
        var t = 5_000_000L; var d = 200; var k = 0
        sm1.start(t)
        repeat(100) { t += 1000; d += 4; k++; sm1.onData(sample(t, d, k)) }
        val cut = journaled.filter { it.sessionElapsedSec <= 60 }
        assertEquals(60, cut.last().sessionElapsedSec)

        val sm2 = SessionStateMachine(smallPlan, SessionMode.INTERVAL)
        sm2.restore(5_000_000L, cut)
        val ui = sm2.uiState.value
        assertEquals(60, ui.sessionElapsedSec)
        assertEquals(cut.last().distanceM, ui.totalDistanceM)
        assertEquals(cut.last().phaseIndex, ui.phaseIndex)
        assertEquals(PhaseType.WORK, ui.phaseType)
        assertEquals(10, ui.phaseRemainingSec) // work 30..70 s

        // app restarted much later, monitor counter at an unrelated value
        val newRecs = mutableListOf<RecordPoint>()
        sm2.onRecord = { newRecs.add(it) }
        var t2 = 9_000_000L; var d2 = 7; var k2 = 1
        sm2.onData(sample(t2, d2, k2))
        assertEquals(60, sm2.uiState.value.sessionElapsedSec)        // outage not credited
        assertEquals(cut.last().distanceM, sm2.uiState.value.totalDistanceM)
        repeat(15) { t2 += 1000; d2 += 4; k2++; sm2.onData(sample(t2, d2, k2)) }
        assertEquals(75, sm2.uiState.value.sessionElapsedSec)
        assertEquals(cut.last().distanceM + 60, sm2.uiState.value.totalDistanceM)
        assertEquals(PhaseType.REST, sm2.uiState.value.phaseType)    // phase plan continued
        assertEquals(16, newRecs.size)
        val summary = sm2.finish()
        assertEquals(5_000_000L, summary.startedAtMs)
    }

    @Test fun journal_roundTrip_andRewrite() {
        val dir = createTempDir("n44r")
        val w = SessionJournal.create(dir, 123L, SessionMode.INTERVAL, "Intervalltraining", smallPlan)
        val recs = (1..50).map { rec(it, if (it % 7 == 0) null else 120 + it, if (it < 30) 0 else 1, if (it < 30) PhaseType.WARMUP else PhaseType.WORK) }
        recs.forEach { w.record(it) }
        w.close()
        val list = SessionJournal.list(dir)
        assertEquals(1, list.size); assertFalse(list[0].finished); assertEquals(50, list[0].durationSec)
        val data = SessionJournal.load(list[0].file)!!
        assertEquals(smallPlan, data.plan)
        assertEquals(recs, data.records)
        val w2 = SessionJournal.rewrite(list[0].file, data, recs.take(20))
        w2.finished(999L); w2.close()
        val data2 = SessionJournal.load(list[0].file)!!
        assertEquals(20, data2.records.size); assertTrue(data2.finished)
        dir.deleteRecursively()
    }

    /** Like the real S4: a sample every ~1.15 s, kcal counter running. */
    private fun realisticSession(): SessionSummary {
        val sm = SessionStateMachine(smallPlan, SessionMode.INTERVAL)
        var t = 1_791_924_436_369L; var d = 500; var k = 0
        sm.start(t)
        repeat(115) { i ->
            t += if (i % 7 == 6) 1300 else 1125; d += 5; k++
            sm.setHeartRate(if (i in 40..45) 0 else 100 + i / 2) // a short dropout with zeros
            sm.onData(sample(t, d, k).copy(totalEnergyKcal = 300 + i / 4, strokeRatePerMin = 29.5f))
        }
        return sm.finish()
    }

    @Test fun fit_resampledToOneRecordPerSecond() {
        val s = realisticSession()
        val secs = FitWriter.resample(s)
        assertEquals(s.totalDurationSec + 1, secs.size)            // no missing seconds any more
        for (i in 1 until secs.size) assertTrue(secs[i].distM >= secs[i - 1].distM)
        assertEquals(s.totalDistanceM.toDouble(), secs.last().distM, 6.0)
        assertTrue(secs.all { it.hr == null || it.hr!! > 0 })
    }

    @Test fun energy_fromMonitorCounter_andCounterResetIgnored() {
        assertEquals(28, realisticSession().totalEnergyKcal)        // 300 -> 328
        val sm = SessionStateMachine(listOf(PhaseSpec(PhaseType.MAIN, "T", targetTimeSec = 3600), PhaseSpec(PhaseType.FREE, "F")), SessionMode.TIME)
        var t = 0L; var d = 0; var k = 0
        sm.start(t)
        val kcal = listOf(50, 51, 52, 52, 0, 1, 2, 3) // monitor reset after the 4th sample
        kcal.forEach { e -> t += 1000; d += 4; k++; sm.onData(sample(t, d, k).copy(totalEnergyKcal = e)) }
        assertEquals(5, sm.finish().totalEnergyKcal)                // 2 + 3, the jump 52 -> 0 ignored
    }

    @Test fun energy_estimatedWhenMonitorSendsNone() {
        val sm = SessionStateMachine(listOf(PhaseSpec(PhaseType.MAIN, "T", targetTimeSec = 3600), PhaseSpec(PhaseType.FREE, "F")), SessionMode.TIME)
        var t = 0L; var d = 0; var k = 0
        sm.start(t)
        repeat(3600) { t += 1000; d += 4; k++; sm.onData(sample(t, d, k)) } // 150 W, energy always 0
        assertEquals(816.0, sm.finish().totalEnergyKcal.toDouble(), 3.0)  // 4*0.8604*150 + 300 per hour
    }

    @Test fun linkQuality_silentBeforeFirstStroke_isJudgedByRssiOnly() {
        val q = com.n44r.app.ble.LinkQualityTracker(1200L)
        q.reset(0L)
        for (s in 0..60 step 2) q.onRssi(-42, s * 1000L)
        val waiting = q.snapshot(60_000L)
        assertTrue(waiting.awaitingData)
        assertEquals(com.n44r.app.ble.SignalLevel.GOOD, waiting.level)
        assertEquals("-42 dBm · Daten ab dem ersten Schlag · gut", waiting.describe("Daten ab dem ersten Schlag"))
        // first stroke at 60 s, then a packet every 1.15 s: 6 s later the rate must not be diluted
        var t = 60_000L
        while (t <= 66_000L) { q.onPacket(t); t += 1150 }
        val rowing = q.snapshot(66_100L)
        assertFalse(rowing.awaitingData)
        assertTrue("rate ${rowing.packetRatePct}", rowing.packetRatePct!! >= 90)
        assertEquals(com.n44r.app.ble.SignalLevel.GOOD, rowing.level)
    }

    @Test fun writeFitForExternalValidation() {
        val summary = realisticSession()
        val out = File(System.getProperty("java.io.tmpdir"), "n44r_test.fit")
        FitWriter.write(out, summary)
        println("FIT written: ${out.absolutePath} (${out.length()} bytes)")
        assertTrue(out.length() > 1000)
    }
}
