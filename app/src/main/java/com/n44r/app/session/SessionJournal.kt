package com.n44r.app.session

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Crash-safe session journal: one small text file per workout in filesDir/journal, written
 * line by line (flushed every second) while rowing. If the app crashes or gets killed, the
 * workout can be resumed from it - optionally from an earlier point ("rewind").
 *
 * Format (pipe separated, one record per line):
 *   N44J|1
 *   META|startedAtMs|MODE|modeLabel
 *   PLAN|TYPE|label|timeSec|distM|TARGETTYPE|paceMin|paceMax|hrLow|hrHigh
 *   R|timestampMs|elapsedMs|phaseIndex|PHASETYPE|distM|pace|power|strokeRate|hr
 *   END|finishedAtMs                      (only when finished regularly)
 *
 * The last [KEEP] journals are kept (finished ones too, so an accidental "beenden" can be
 * undone by resuming).
 */
object SessionJournal {
    private const val KEEP = 8
    private const val KEEP_DEMO = 3
    private const val DEMO_SUFFIX = "_demo"

    data class Info(
        val file: File,
        val startedAtMs: Long,
        val modeLabel: String,
        val durationSec: Int,
        val distanceM: Int,
        val finished: Boolean,
        val demo: Boolean = false
    )

    fun isDemo(file: File) = file.nameWithoutExtension.endsWith(DEMO_SUFFIX)

    data class Data(
        val startedAtMs: Long,
        val mode: SessionMode,
        val modeLabel: String,
        val plan: List<PhaseSpec>,
        val records: List<RecordPoint>,
        val finished: Boolean
    )

    fun dir(filesDir: File) = File(filesDir, "journal").apply { mkdirs() }

    class Writer(val file: File, append: Boolean) {
        private val w = BufferedWriter(FileWriter(file, append))

        fun header(startedAtMs: Long, mode: SessionMode, modeLabel: String, plan: List<PhaseSpec>) {
            line("N44J|1")
            line("META|$startedAtMs|${mode.name}|${clean(modeLabel)}")
            plan.forEach { p ->
                val t = p.target
                line("PLAN|${p.type.name}|${clean(p.label)}|${p.targetTimeSec.s()}|${p.targetDistanceMeters.s()}|" +
                    "${t.type.name}|${t.paceTargetMinSec.s()}|${t.paceTargetMaxSec.s()}|${t.hrZoneLowBpm.s()}|${t.hrZoneHighBpm.s()}")
            }
        }

        fun record(r: RecordPoint) {
            line("R|${r.timestampMs}|${r.sessionElapsedMs}|${r.phaseIndex}|${r.phaseType.name}|${r.distanceM}|" +
                "${r.paceSecPer500m.s()}|${r.powerW}|${r.strokeRate}|${r.heartRateBpm.s()}")
        }

        fun finished(atMs: Long) = line("END|$atMs")

        @Synchronized
        private fun line(s: String) {
            runCatching { w.write(s); w.write("\n"); w.flush() }
        }

        fun close() { runCatching { w.close() } }
    }

    private fun Int?.s() = this?.toString() ?: ""
    private fun clean(s: String) = s.replace("|", "/").replace("\n", " ")

    /** [demo] journals (demo mode, for testing resume) are kept separately: only the last
     *  [KEEP_DEMO], so they never push real workouts out of the list. */
    fun create(filesDir: File, startedAtMs: Long, mode: SessionMode, modeLabel: String, plan: List<PhaseSpec>, demo: Boolean = false): Writer {
        val d = dir(filesDir)
        val keep = if (demo) KEEP_DEMO else KEEP
        d.listFiles { f -> f.extension == "n44j" && isDemo(f) == demo }
            ?.sortedByDescending { it.name }?.drop(keep - 1)?.forEach { it.delete() }
        val w = Writer(File(d, "$startedAtMs${if (demo) DEMO_SUFFIX else ""}.n44j"), append = false)
        w.header(startedAtMs, mode, modeLabel, plan)
        return w
    }

    /** Rewrites a journal truncated to [records] (rewind) and returns a writer that appends. */
    fun rewrite(file: File, data: Data, records: List<RecordPoint>): Writer {
        val tmp = File(file.parentFile, file.name + ".tmp")
        val w = Writer(tmp, append = false)
        w.header(data.startedAtMs, data.mode, data.modeLabel, data.plan)
        records.forEach { w.record(it) }
        w.close()
        file.delete()
        tmp.renameTo(file)
        return Writer(file, append = true)
    }

    fun list(filesDir: File): List<Info> =
        dir(filesDir).listFiles { f -> f.extension == "n44j" }.orEmpty()
            .mapNotNull { f ->
                val d = runCatching { load(f) }.getOrNull() ?: return@mapNotNull null
                val last = d.records.lastOrNull() ?: return@mapNotNull null
                Info(f, d.startedAtMs, d.modeLabel, (last.sessionElapsedMs / 1000).toInt(), last.distanceM, d.finished, isDemo(f))
            }
            .sortedByDescending { it.startedAtMs }

    fun load(file: File): Data? {
        var started = 0L
        var mode = SessionMode.INTERVAL
        var label = ""
        var finished = false
        val plan = mutableListOf<PhaseSpec>()
        val recs = mutableListOf<RecordPoint>()
        file.forEachLine { line ->
            val p = line.split("|")
            runCatching {
                when (p[0]) {
                    "META" -> { started = p[1].toLong(); mode = SessionMode.valueOf(p[2]); label = p[3] }
                    "PLAN" -> plan.add(
                        PhaseSpec(
                            type = PhaseType.valueOf(p[1]), label = p[2],
                            targetTimeSec = p[3].toIntOrNull(), targetDistanceMeters = p[4].toIntOrNull(),
                            target = PhaseTarget(
                                type = TargetType.valueOf(p[5]),
                                paceTargetMinSec = p[6].toIntOrNull(), paceTargetMaxSec = p[7].toIntOrNull(),
                                hrZoneLowBpm = p[8].toIntOrNull(), hrZoneHighBpm = p[9].toIntOrNull()
                            )
                        )
                    )
                    "R" -> {
                        val elapsed = p[2].toLong()
                        recs.add(
                            RecordPoint(
                                timestampMs = p[1].toLong(), sessionElapsedSec = (elapsed / 1000).toInt(),
                                phaseIndex = p[3].toInt(), phaseType = PhaseType.valueOf(p[4]),
                                distanceM = p[5].toInt(), paceSecPer500m = p[6].toIntOrNull(),
                                powerW = p[7].toInt(), strokeRate = p[8].toFloat(),
                                heartRateBpm = p[9].toIntOrNull(), sessionElapsedMs = elapsed
                            )
                        )
                    }
                    "END" -> finished = true
                }
            } // a half-written last line after a crash is simply skipped
        }
        if (started == 0L || plan.isEmpty()) return null
        return Data(started, mode, label, plan, recs, finished)
    }
}
