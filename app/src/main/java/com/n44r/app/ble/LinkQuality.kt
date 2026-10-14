package com.n44r.app.ble

import kotlin.math.roundToInt

enum class SignalLevel { NONE, WEAK, OK, GOOD }

/** Connection quality over the last [LinkQualityTracker.WINDOW_MS]. */
data class LinkQuality(
    /** Mean signal strength in dBm, null if not measurable (demo, not connected yet). */
    val rssiAvg: Int?,
    /** Received packets in % of the expected rate (only for senders with a fixed rate). */
    val packetRatePct: Int?,
    /** Mean time between packets (for senders without a fixed rate, e.g. the Apple bridge). */
    val avgIntervalMs: Long?,
    val level: SignalLevel
) {
    companion object { val NONE = LinkQuality(null, null, null, SignalLevel.NONE) }

    fun describe(): String = buildList {
        rssiAvg?.let { add("$it dBm") }
        if (packetRatePct != null) add("$packetRatePct % Pakete")
        else avgIntervalMs?.let { add(String.format(java.util.Locale.GERMAN, "Paket alle %.1f s", it / 1000.0)) }
        add(when (level) {
            SignalLevel.GOOD -> "gut"; SignalLevel.OK -> "mittel"
            SignalLevel.WEAK -> "schwach"; SignalLevel.NONE -> "kein Signal"
        })
    }.joinToString(" · ")
}

/**
 * Judges a BLE link from two independent measurements:
 *  - RSSI (signal strength, polled every 2 s) -> distance / shielding (body, furniture)
 *  - packet flow -> interference / sender problems. For a sender with a known fixed interval
 *    ([expectedIntervalMs], e.g. the S4 ComModule) as % of the expected packets; for senders
 *    with their own irregular rhythm (Apple-Watch bridge: every ~1-3 s, chest straps ~1 s)
 *    via the longest gap instead - "40 % of 1/s" would wrongly flag a perfectly fine bridge.
 * Averaged over 20 s. RSSI scale: >= -70 dBm good, -70..-85 usable, below -85 critical.
 */
class LinkQualityTracker(@Volatile var expectedIntervalMs: Long?) {
    companion object {
        const val WINDOW_MS = 20_000L
        private const val GAP_OK_MS = 5_000L    // longer gap -> at most "mittel"
        private const val GAP_WEAK_MS = 8_000L  // longer gap -> "schwach"
    }

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
        val span = minOf(WINDOW_MS, now - since)
        val interval = expectedIntervalMs
        val rate = if (interval != null && span >= 5_000) {
            ((packets.size * interval * 100.0) / span).roundToInt().coerceAtMost(100)
        } else null
        val avgInterval = if (packets.size >= 2) (packets.last() - packets.first()) / (packets.size - 1) else null
        // longest gap in the window, including the time since the last packet
        var maxGap = if (packets.isEmpty()) span else now - packets.last()
        for (i in 1 until packets.size) maxGap = maxOf(maxGap, packets[i] - packets[i - 1])

        var level = when {
            avg == null -> if (packets.isNotEmpty()) SignalLevel.GOOD else SignalLevel.NONE
            avg >= -70 -> SignalLevel.GOOD
            avg >= -85 -> SignalLevel.OK
            else -> SignalLevel.WEAK
        }
        if (rate != null) {
            if (rate < 70) level = SignalLevel.WEAK
            else if (rate < 90 && level == SignalLevel.GOOD) level = SignalLevel.OK
        } else if (span >= 5_000 && level != SignalLevel.NONE) {
            // gap limits relative to the sender's own typical rhythm (median interval): the Apple
            // bridge normally sends every ~2 s with occasional 5-6 s pauses caused by the watch,
            // not by the radio link - only clearly unusual gaps count against the signal
            val gaps = (1 until packets.size).map { packets[it] - packets[it - 1] }.sorted()
            val median = if (gaps.isEmpty()) 1000L else gaps[gaps.size / 2]
            val okLimit = maxOf(GAP_OK_MS, 4 * median)
            val weakLimit = maxOf(GAP_WEAK_MS, 6 * median)
            if (maxGap > weakLimit) level = SignalLevel.WEAK
            else if (maxGap > okLimit && level == SignalLevel.GOOD) level = SignalLevel.OK
        }
        return LinkQuality(avg, rate, avgInterval, level)
    }
}
