package com.n44r.app.ble

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared raw BLE log for rower AND heart-rate packets plus connection events (EVENT lines),
 * in the same "HH:mm:ss.SSS Tag: (0x) AA-BB-.." format as the original nRF Connect captures.
 *
 * Thread-safe (both GATT callbacks append from binder threads). Once a session is running it
 * is also streamed line-by-line into the session's _rawble.txt file, so a crash or a killed
 * process no longer loses the log - exactly the situation where it is needed most.
 */
class RawLog {
    private val sb = StringBuilder()
    private var writer: BufferedWriter? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMAN)

    @Synchronized
    fun line(tag: String, text: String) {
        val l = "${fmt.format(Date())} $tag: $text\n"
        sb.append(l)
        writer?.let { w -> runCatching { w.write(l); w.flush() } }
    }

    fun bytes(tag: String, raw: ByteArray) = line(tag, "(0x) " + raw.joinToString("-") { "%02X".format(it) })
    fun event(text: String) = line("EVENT", text)

    @Synchronized fun text(): String = sb.toString()

    @Synchronized fun clear() { sb.setLength(0) }

    /** Starts streaming to [file]. Everything buffered so far is written first (new file) -
     *  with [append] (resumed session) the existing file content is kept and continued. */
    @Synchronized
    fun attachFile(file: File, append: Boolean) {
        detachFile()
        runCatching {
            val w = BufferedWriter(FileWriter(file, append))
            if (append) w.write("----- Fortsetzung -----\n")
            w.write(sb.toString())
            w.flush()
            writer = w
        }
    }

    @Synchronized
    fun detachFile() {
        runCatching { writer?.close() }
        writer = null
    }
}
