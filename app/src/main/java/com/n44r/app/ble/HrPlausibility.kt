package com.n44r.app.ble

/**
 * Detects garbage heart rate from chest straps that don't report skin contact over BLE
 * (Garmin HRM 600: flags 0x10, no contact bits). Observed 2026-10-15 while taking the strap
 * off: RR intervals of 0 / 240 / 1220 ms while the reported HR climbed from 63 to 140 bpm
 * and then decayed slowly after putting it back on - plausible-looking but fake numbers.
 *
 * Rule: a packet is an artifact if it carries RR fields but none is physiologically possible
 * (300..2000 ms), or if the reported bpm deviates > [MAX_DEVIATION] from the bpm implied by
 * the median of the recent valid RR intervals. The output is "untrusted" while any of the last
 * [WINDOW] packets was an artifact - so recovery needs a few consistent packets in a row.
 * Sensors without RR (Apple bridge) are always trusted (nothing to check against).
 */
class HrPlausibility {
    companion object {
        const val MIN_RR = 300
        const val MAX_RR = 2000
        private const val MAX_DEVIATION = 0.30
        private const val WINDOW = 6 // ~6 s of consistent beats before values are trusted again
        private const val RR_HISTORY = 7
    }

    private val recentRr = ArrayDeque<Int>()
    private val recentFlags = ArrayDeque<Boolean>() // true = artifact

    fun isValidRr(rr: Int) = rr in MIN_RR..MAX_RR

    fun reset() { recentRr.clear(); recentFlags.clear() }

    /** @return true if the packet's HR can be trusted. */
    @Synchronized
    fun check(bpm: Int, rrMs: List<Int>): Boolean {
        if (rrMs.isEmpty()) return true
        val valid = rrMs.filter(::isValidRr)
        valid.forEach { recentRr.addLast(it); if (recentRr.size > RR_HISTORY) recentRr.removeFirst() }
        val artifact = if (valid.isEmpty()) true else {
            val median = recentRr.sorted()[recentRr.size / 2]
            val rrBpm = 60000.0 / median
            kotlin.math.abs(bpm - rrBpm) / rrBpm > MAX_DEVIATION
        }
        recentFlags.addLast(artifact)
        if (recentFlags.size > WINDOW) recentFlags.removeFirst()
        return recentFlags.none { it }
    }
}
