package com.n44r.app

import com.n44r.app.ble.HeartRateParser
import org.junit.Assert.*
import org.junit.Test

class HeartRateParserTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun appleBridge_plainU8_noContactFeature() {
        val m = HeartRateParser.parse(b(0x00, 0x8C))!!
        assertEquals(140, m.bpm); assertNull(m.skinContact); assertTrue(m.rrMs.isEmpty())
    }

    @Test fun strap_contact_and_rr() {
        // flags: contact supported + detected (0x06) + RR present (0x10) = 0x16; HR 150; RR 410/1024 s, 420/1024 s
        val m = HeartRateParser.parse(b(0x16, 150, 0x9A, 0x01, 0xA4, 0x01))!!
        assertEquals(150, m.bpm); assertEquals(true, m.skinContact)
        assertEquals(listOf(400, 410), m.rrMs)
    }

    @Test fun plausibility_hrm600_strapOff_sequence_from_2026_10_15() {
        val p = com.n44r.app.ble.HrPlausibility()
        // resting, consistent: 60 bpm with RR ~1000 ms
        repeat(6) { assertTrue(p.check(60, listOf(1000 + it * 10))) }
        // strap being taken off: noise RR, then HR climbing with RR 0 / impossible values
        assertFalse(p.check(62, listOf(247)))
        assertFalse(p.check(113, listOf(245)))
        assertFalse(p.check(118, listOf(0)))
        assertFalse(p.check(134, listOf(1220)))
        // back on, HR still decaying from the strap's internal smoothing -> still untrusted
        assertFalse(p.check(120, listOf(1070)))
        // consistent again for a few packets -> trusted again
        repeat(6) { p.check(57, listOf(1050)) }
        assertTrue(p.check(57, listOf(1060)))
        // no RR at all (Apple bridge) is always trusted
        assertTrue(com.n44r.app.ble.HrPlausibility().check(140, emptyList()))
    }

    @Test fun strap_noContact_energy_u16() {
        // flags: u16 HR (0x01) + contact supported, not detected (0x04) + energy (0x08) + RR (0x10)
        val m = HeartRateParser.parse(b(0x1D, 0x00, 0x00, 0x10, 0x00, 0x00, 0x04))!!
        assertEquals(0, m.bpm); assertEquals(false, m.skinContact)
        assertEquals(listOf(1000), m.rrMs)
    }
}
