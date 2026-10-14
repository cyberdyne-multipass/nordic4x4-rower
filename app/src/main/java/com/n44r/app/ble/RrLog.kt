package com.n44r.app.ble

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Beat-to-beat (RR) intervals from a chest strap (e.g. Garmin HRM 600), streamed to
 * n44r_<start>_rr.csv during a session: "wall_time_ms;rr_ms" per beat. Basis for HRV
 * analysis later; the Apple-Watch bridge normally sends none, then the file stays empty
 * and is not exported.
 */
class RrLog {
    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var count = 0

    @Synchronized
    fun attach(f: File, append: Boolean) {
        detach()
        runCatching {
            val isNew = !append || !f.exists() || f.length() == 0L
            writer = BufferedWriter(FileWriter(f, append)).also { if (isNew) it.write("wall_time_ms;rr_ms\n") }
            file = f
            count = 0
        }
    }

    @Synchronized
    fun add(wallTimeMs: Long, rrMs: List<Int>) {
        val w = writer ?: return
        runCatching {
            rrMs.forEach { w.write("$wallTimeMs;$it\n"); count++ }
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
        return f?.takeIf { it.exists() && it.length() > "wall_time_ms;rr_ms\n".length }
    }
}
