package com.n44r.app.ble

/**
 * Bluetooth SIG Heart Rate Measurement (0x2A37):
 *  byte 0 flags: bit0 HR format (0 = u8, 1 = u16), bit1 sensor contact detected,
 *                bit2 sensor contact feature supported, bit3 energy expended present (u16),
 *                bit4 RR intervals present (u16 each, unit 1/1024 s, as many as fit).
 */
object HeartRateParser {

    data class Measurement(
        val bpm: Int,
        /** null = sensor does not support contact detection (Apple bridge), else detected yes/no. */
        val skinContact: Boolean?,
        val rrMs: List<Int>
    )

    fun parse(raw: ByteArray): Measurement? {
        if (raw.size < 2) return null
        val flags = raw[0].toInt() and 0xFF
        var i = 1
        val bpm: Int
        if ((flags and 0x01) != 0) {
            if (raw.size < 3) return null
            bpm = (raw[1].toInt() and 0xFF) or ((raw[2].toInt() and 0xFF) shl 8)
            i = 3
        } else {
            bpm = raw[1].toInt() and 0xFF
            i = 2
        }
        val contact = if ((flags and 0x04) != 0) (flags and 0x02) != 0 else null
        if ((flags and 0x08) != 0) i += 2 // energy expended
        val rr = mutableListOf<Int>()
        if ((flags and 0x10) != 0) {
            while (i + 1 < raw.size) {
                val v = (raw[i].toInt() and 0xFF) or ((raw[i + 1].toInt() and 0xFF) shl 8)
                rr.add(Math.round(v * 1000.0 / 1024.0).toInt())
                i += 2
            }
        }
        return Measurement(bpm, contact, rr)
    }
}
