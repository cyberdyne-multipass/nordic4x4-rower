package com.n44r.app.ble

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Beat-to-beat (RR) intervals from a chest strap (e.g. Garmin HRM 600), streamed to
 * n44r_<start>_rr.csv during a session: "wall_time_ms;rr_ms;plausibel" per beat, where
 * plausibel=0 marks artifacts (strap without skin contact, impossible intervals) that an HRV
 * analysis must skip. The Apple-Watch bridge normally sends no RR -> file stays empty and is
 * not exported.
 */
class RrLog {
    companion object { private const val HEADER = "wall_time_ms;rr_ms;plausibel\n" }

    private var writer: BufferedWriter? = null
    private var file: File? = null

    @Synchronized
    fun attach(f: File, append: Boolean) {
        detach()
        runCatching {
            val isNew = !append || !f.exists() || f.length() == 0L
            writer = BufferedWriter(FileWriter(f, append)).also { if (isNew) it.write(HEADER) }
            file = f
        }
    }

    @Synchronized
    fun add(wallTimeMs: Long, rr: List<Pair<Int, Boolean>>) {
        val w = writer ?: return
        runCatching {
            rr.forEach { (ms, ok) -> w.write("$wallTimeMs;$ms;${if (ok) 1 else 0}\n") }
            w.flush()
        }
    }

    /** Closes the file; returns it if it contains at least one interval. */
    @Synchronized
    fun detach(): File? {
        runCatching { writer?.close() }
        writer = null
        val f = file
        file = null
        return f?.takeIf { it.exists() && it.length() > HEADER.length }
    }
}
