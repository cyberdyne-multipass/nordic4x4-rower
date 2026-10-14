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

    @Test fun strap_noContact_energy_u16() {
        // flags: u16 HR (0x01) + contact supported, not detected (0x04) + energy (0x08) + RR (0x10)
        val m = HeartRateParser.parse(b(0x1D, 0x00, 0x00, 0x10, 0x00, 0x00, 0x04))!!
        assertEquals(0, m.bpm); assertEquals(false, m.skinContact)
        assertEquals(listOf(1000), m.rrMs)
    }
}
