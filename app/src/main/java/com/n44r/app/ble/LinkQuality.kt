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
    val level: SignalLevel,
    /** Connected, but no data packet since (re)connecting. The S4 ComModule only starts sending
     *  with the first stroke, so before the start this is normal - level is then RSSI-only. */
    val awaitingData: Boolean = false
) {
    companion object { val NONE = LinkQuality(null, null, null, SignalLevel.NONE) }

    /** [awaitingText]: what to say while connected but silent (e.g. "Daten ab erstem Schlag"). */
    fun describe(awaitingText: String = "noch keine Daten"): String = buildList {
        rssiAvg?.let { add("$it dBm") }
        if (awaitingData) add(awaitingText)
        else if (packetRatePct != null) add("$packetRatePct % Pakete")
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
    /** First data packet since [reset]; null = sender silent so far (see [LinkQuality.awaitingData]). */
    private var firstPacket: Long? = null

    @Synchronized fun reset(now: Long = System.currentTimeMillis()) {
        rssi.clear(); packets.clear(); since = now; firstPacket = null
    }
    @Synchronized fun onRssi(v: Int, now: Long = System.currentTimeMillis()) { rssi.addLast(now to v); trim(now) }
    @Synchronized fun onPacket(now: Long = System.currentTimeMillis()) {
        if (firstPacket == null) firstPacket = now
        packets.addLast(now); trim(now)
    }

    private fun trim(now: Long) {
        while (rssi.isNotEmpty() && now - rssi.first().first > WINDOW_MS) rssi.removeFirst()
        while (packets.isNotEmpty() && now - packets.first() > WINDOW_MS) packets.removeFirst()
    }

    @Synchronized
    fun snapshot(now: Long = System.currentTimeMillis()): LinkQuality {
        trim(now)
        val avg = if (rssi.isEmpty()) null else rssi.map { it.second }.average().roundToInt()
        val first = firstPacket
        if (first == null) {
            // connected but nothing received yet: the S4 ComModule is silent until the first stroke
            // (seen 2026-10-15: connected 17:56, first packet 18:00:36) -> judge the radio by RSSI
            // only instead of "0 % Pakete · schwach" at -42 dBm. A sender that stops later is
            // caught by the gap/rate logic below and by the ViewModel watchdog.
            val level = when {
                avg == null -> SignalLevel.NONE
                avg >= -70 -> SignalLevel.GOOD
                avg >= -85 -> SignalLevel.OK
                else -> SignalLevel.WEAK
            }
            return LinkQuality(avg, null, null, level, awaitingData = true)
        }
        // measure from the first packet, not from the connect: otherwise the first 20 s after the
        // first stroke showed "24 % Pakete · schwach" (the silent waiting time counted as loss)
        val span = minOf(WINDOW_MS, now - maxOf(since, first))
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
