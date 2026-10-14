package com.n44r.app.ble

import kotlin.math.roundToInt

enum class SignalLevel { NONE, WEAK, OK, GOOD }

/** Connection quality over the last [LinkQualityTracker.WINDOW_MS]. */
data class LinkQuality(
    /** Mean signal strength in dBm, null if not measurable (demo, not connected yet). */
    val rssiAvg: Int?,
    /** Received packets in % of the expected rate, null if the sensor has no fixed rate. */
    val packetRatePct: Int?,
    val level: SignalLevel
) {
    companion object { val NONE = LinkQuality(null, null, SignalLevel.NONE) }

    fun describe(): String = buildList {
        rssiAvg?.let { add("$it dBm") }
        packetRatePct?.let { add("$it % Pakete") }
        add(when (level) {
            SignalLevel.GOOD -> "gut"; SignalLevel.OK -> "mittel"
            SignalLevel.WEAK -> "schwach"; SignalLevel.NONE -> "kein Signal"
        })
    }.joinToString(" · ")
}

/**
 * Judges a BLE link from two independent measurements:
 *  - RSSI (signal strength, polled every 2 s) -> distance / shielding (body, furniture)
 *  - packet rate vs. the sensor's fixed send interval -> interference / sender problems
 * RSSI alone is noisy, so both are averaged over a 20 s window. Rough RSSI scale:
 * >= -70 dBm good, -70..-85 usable, below -85 critical (links usually drop around -90).
 */
class LinkQualityTracker(@Volatile var expectedIntervalMs: Long?) {
    companion object { const val WINDOW_MS = 20_000L }

    private val rssi = ArrayDeque<Pair<Long, Int>>()
    private val packets = ArrayDeque<Long>()
    private var since = 0L

    @Synchronized fun reset(now: Long = System.currentTimeMillis()) { rssi.clear(); packets.clear(); since = now }
    @Synchronized fun onRssi(v: Int, now: Long = System.currentTimeMillis()) { rssi.addLast(now to v); trim(now) }
    @Synchronized fun onPacket(now: Long = System.currentTimeMillis()) { packets.addLast(now); trim(now) }

    private fun trim(now: Long) {
        while (rssi.isNotEmpty() && now - rssi.first().first > WINDOW_MS) rssi.removeFirst()
        while (packets.isNotEmpty() && now - packets.first() > WINDOW_MS) packets.removeFirst()
    }

    @Synchronized
    fun snapshot(now: Long = System.currentTimeMillis()): LinkQuality {
        trim(now)
        val avg = if (rssi.isEmpty()) null else rssi.map { it.second }.average().roundToInt()
        val interval = expectedIntervalMs
        val span = minOf(WINDOW_MS, now - since)
        val rate = if (interval != null && span >= 5_000) {
            ((packets.size * interval * 100.0) / span).roundToInt().coerceAtMost(100)
        } else null
        var level = when {
            avg == null -> if (rate != null || packets.isNotEmpty()) SignalLevel.GOOD else SignalLevel.NONE
            avg >= -70 -> SignalLevel.GOOD
            avg >= -85 -> SignalLevel.OK
            else -> SignalLevel.WEAK
        }
        if (rate != null) {
            if (rate < 70) level = SignalLevel.WEAK
            else if (rate < 90 && level == SignalLevel.GOOD) level = SignalLevel.OK
        }
        return LinkQuality(avg, rate, level)
    }
}
