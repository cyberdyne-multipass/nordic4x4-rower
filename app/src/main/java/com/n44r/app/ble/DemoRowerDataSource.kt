package com.n44r.app.ble

import com.n44r.app.session.PhaseType
import kotlin.random.Random

/**
 * Demo data derived from a REAL recorded session (Hans, 2026-09-08, WaterRower S4 + ComModule,
 * Apple-Watch HR via iPhone bridge; 2010 samples). The FIT export was segmented into phases
 * (4 work blocks of ~210 s, 3 recoveries of ~155 s, warm-up, cool-down) and each field was
 * averaged in 10-second bins of "seconds into phase" - the tables below are those averages.
 * Work/rest curves stop before the athlete's anticipatory taper at the end of each block, and
 * values are held at the last point for phases configured longer than the recording.
 * Warm-up is lightly idealized (the recording had a stop and a late HR sensor in it).
 */
object DemoRowerDataSource {
    data class Pt(val sec: Int, val pace: Int, val hr: Int, val sr: Int, val pw: Int)

    // seconds-into-phase, pace s/500m, HR bpm, stroke rate spm, power W
    private val WORK = listOf(
        Pt(0, 115, 128, 30, 229), Pt(10, 102, 135, 30, 224), Pt(20, 99, 140, 29, 225),
        Pt(30, 100, 145, 29, 205), Pt(40, 101, 148, 29, 196), Pt(50, 103, 149, 28, 189),
        Pt(60, 104, 150, 29, 195), Pt(70, 105, 151, 29, 189), Pt(80, 105, 153, 29, 187),
        Pt(90, 106, 154, 28, 184), Pt(110, 106, 154, 28, 178), Pt(130, 107, 155, 28, 177),
        Pt(150, 107, 154, 28, 179), Pt(170, 108, 154, 28, 172), Pt(190, 110, 154, 28, 163),
        Pt(240, 112, 155, 27, 158)
    )
    private val REST = listOf(
        Pt(0, 134, 151, 23, 44), Pt(10, 159, 146, 24, 48), Pt(20, 179, 141, 22, 47),
        Pt(30, 182, 139, 21, 52), Pt(40, 182, 136, 22, 52), Pt(50, 172, 132, 23, 56),
        Pt(60, 166, 130, 24, 65), Pt(70, 161, 128, 24, 65), Pt(80, 170, 127, 22, 65),
        Pt(90, 173, 128, 22, 70), Pt(100, 170, 126, 25, 67), Pt(110, 158, 124, 24, 59),
        Pt(120, 166, 125, 23, 65), Pt(180, 168, 124, 23, 62)
    )
    private val WARMUP = listOf(
        Pt(0, 240, 80, 18, 70), Pt(20, 180, 84, 24, 86), Pt(40, 135, 90, 27, 113),
        Pt(60, 126, 96, 26, 119), Pt(90, 128, 104, 27, 127), Pt(120, 129, 112, 27, 125),
        Pt(150, 128, 118, 27, 110), Pt(200, 129, 121, 26, 104), Pt(300, 130, 122, 26, 102),
        Pt(600, 130, 123, 26, 100)
    )
    private val COOLDOWN = listOf(
        Pt(0, 138, 152, 24, 44), Pt(10, 163, 147, 25, 60), Pt(20, 162, 142, 26, 61),
        Pt(40, 160, 136, 22, 47), Pt(60, 165, 133, 25, 60), Pt(90, 158, 128, 25, 51),
        Pt(120, 174, 128, 24, 65), Pt(180, 160, 128, 23, 72), Pt(230, 198, 124, 20, 29),
        Pt(300, 203, 119, 20, 31), Pt(360, 201, 116, 20, 33), Pt(420, 202, 117, 21, 30)
    )
    /** Steady moderate rowing for plain time/distance mode (no such block in the recording -
     *  taken from the warm-up's steady section, slightly firmer). */
    private val MAIN = listOf(Pt(0, 135, 110, 26, 110), Pt(60, 126, 128, 27, 125), Pt(300, 125, 134, 27, 128), Pt(1200, 127, 136, 27, 125))

    fun curveFor(phase: PhaseType): List<Pt> = when (phase) {
        PhaseType.WORK -> WORK
        PhaseType.REST -> REST
        PhaseType.WARMUP -> WARMUP
        PhaseType.COOLDOWN -> COOLDOWN
        PhaseType.MAIN -> MAIN
        PhaseType.FREE -> COOLDOWN
    }

    /** Linear interpolation on the curve; clamps to the last point beyond the recording. */
    fun sample(curve: List<Pt>, sec: Int): Pt {
        if (sec <= curve.first().sec) return curve.first()
        if (sec >= curve.last().sec) return curve.last()
        val i = curve.indexOfLast { it.sec <= sec }
        val a = curve[i]; val b = curve[i + 1]
        val t = (sec - a.sec).toFloat() / (b.sec - a.sec)
        fun lerp(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Pt(sec, lerp(a.pace, b.pace), lerp(a.hr, b.hr), lerp(a.sr, b.sr), lerp(a.pw, b.pw))
    }

    fun jitter(rnd: Random, amount: Int) = rnd.nextInt(-amount, amount + 1)
}
