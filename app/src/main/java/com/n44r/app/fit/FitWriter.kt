package com.n44r.app.fit

import com.n44r.app.session.SessionSummary
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Minimal, self-contained FIT (Flexible and Interoperable Data Transfer) file encoder.
 * Writes file_id, record, lap and session messages - the subset every consumer
 * (Strava included) needs to import a workout correctly.
 *
 * IMPORTANT: sport/sub_sport are deliberately written as running(1)/treadmill(1),
 * NOT rowing(15)/indoor_rowing(14). This mirrors the confirmed, working retagging
 * fix already used for WaterRower FIT files - Strava has a known display bug for
 * indoor_rowing (shows no distance / wrong moving time). Writing "running" from
 * the start means the app never needs the old post-hoc retagging step.
 */
object FitWriter {
    private const val FIT_EPOCH_OFFSET_SEC = 631065600L
    private val CRC_TABLE: UIntArray = uintArrayOf(
        0x0000u, 0xCC01u, 0xD801u, 0x1400u, 0xF001u, 0x3C00u, 0x2800u, 0xE401u,
        0xA001u, 0x6C00u, 0x7800u, 0xB401u, 0x5000u, 0x9C01u, 0x8801u, 0x4400u
    )

    private fun fitTime(unixMs: Long) = (unixMs / 1000L - FIT_EPOCH_OFFSET_SEC).toInt()

    // Rewritten with explicit unsigned arithmetic (UInt) to rule out any signed-shift/
    // sign-extension ambiguity - a prior signed-Int version produced a CRC that fitparse
    // and an independent Python re-implementation both rejected as wrong (0x0311 written
    // vs 0xBDAF actually expected on a real captured session), even though the table and
    // algorithm structure read correctly on paper.
    private fun crc16(bytes: ByteArray): Int {
        var crc: UInt = 0u
        for (b in bytes) {
            val byteVal = (b.toInt() and 0xFF).toUInt()
            var tmp = CRC_TABLE[(crc and 0xFu).toInt()]
            crc = (crc shr 4) and 0x0FFFu
            crc = crc xor tmp xor CRC_TABLE[(byteVal and 0xFu).toInt()]
            tmp = CRC_TABLE[(crc and 0xFu).toInt()]
            crc = (crc shr 4) and 0x0FFFu
            crc = crc xor tmp xor CRC_TABLE[((byteVal shr 4) and 0xFu).toInt()]
        }
        return (crc and 0xFFFFu).toInt()
    }

    private fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)
    private fun ByteArrayOutputStream.u16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
    private fun ByteArrayOutputStream.u32(v: Long) {
        write((v and 0xFF).toInt()); write(((v shr 8) and 0xFF).toInt())
        write(((v shr 16) and 0xFF).toInt()); write(((v shr 24) and 0xFF).toInt())
    }
    private fun ByteArrayOutputStream.u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)

    fun write(outFile: File, summary: SessionSummary) {
        val body = ByteArrayOutputStream()
        writeFileId(body, summary.startedAtMs)
        writeRecords(body, summary)
        writeLaps(body, summary)
        writeSession(body, summary)

        val bodyBytes = body.toByteArray()
        val header = ByteArrayOutputStream()
        header.u8(12); header.u8(0x10); header.u16(2178); header.u32(bodyBytes.size)
        header.write(".FIT".toByteArray(Charsets.US_ASCII))
        val headerBytes = header.toByteArray()

        val fileContent = ByteArrayOutputStream()
        fileContent.write(headerBytes); fileContent.write(bodyBytes)
        val crc = crc16(fileContent.toByteArray())
        fileContent.u16(crc)

        outFile.writeBytes(fileContent.toByteArray())
    }

    private fun writeFileId(body: ByteArrayOutputStream, startedAtMs: Long) {
        // Definition: local type 0, global mesg 0 (file_id): type(0,enum,1) manufacturer(1,u16,2) time_created(4,u32,4)
        body.u8(0x40); body.u8(0); body.u8(0); body.u16(0); body.u8(3)
        body.u8(0); body.u8(1); body.u8(0x00)
        body.u8(1); body.u8(2); body.u8(0x84.toByte().toInt() and 0xFF)
        body.u8(4); body.u8(4); body.u8(0x86.toByte().toInt() and 0xFF)
        // Data
        body.u8(0x00); body.u8(4); body.u16(255); body.u32(fitTime(startedAtMs))
    }

    private fun writeRecords(body: ByteArrayOutputStream, summary: SessionSummary) {
        body.u8(0x41); body.u8(0); body.u8(0); body.u16(20); body.u8(6)
        body.u8(253); body.u8(4); body.u8(0x86)
        body.u8(5); body.u8(4); body.u8(0x86)
        body.u8(6); body.u8(2); body.u8(0x84)
        body.u8(7); body.u8(2); body.u8(0x84)
        body.u8(4); body.u8(1); body.u8(0x02)
        body.u8(3); body.u8(1); body.u8(0x02)
        for (p in summary.records) {
            val speedMps = p.paceSecPer500m?.takeIf { it > 0 }?.let { 500.0 / it } ?: 0.0
            body.u8(0x01)
            body.u32(fitTime(p.timestampMs))
            body.u32(p.distanceM * 100)
            body.u16((speedMps * 1000).toInt().coerceIn(0, 65534))
            body.u16(p.powerW.coerceIn(0, 65534))
            body.u8(p.strokeRate.toInt().coerceIn(0, 254))
            body.u8((p.heartRateBpm ?: 0).coerceIn(0, 254))
        }
    }

    private fun writeLaps(body: ByteArrayOutputStream, summary: SessionSummary) {
        body.u8(0x42); body.u8(0); body.u8(0); body.u16(19); body.u8(5)
        body.u8(253); body.u8(4); body.u8(0x86)
        body.u8(2); body.u8(4); body.u8(0x86)
        body.u8(7); body.u8(4); body.u8(0x86)
        body.u8(8); body.u8(4); body.u8(0x86)
        body.u8(9); body.u8(4); body.u8(0x86)

        var cursorSec = 0L
        val baseFit = fitTime(summary.startedAtMs)
        for (lap in summary.laps) {
            val startSec = baseFit + cursorSec
            cursorSec += lap.durationSec
            val endSec = baseFit + cursorSec
            body.u8(0x02)
            body.u32(endSec.toInt())
            body.u32(startSec.toInt())
            body.u32(lap.durationSec * 1000)
            body.u32(lap.durationSec * 1000)
            body.u32((lap.endDistanceM - lap.startDistanceM) * 100)
        }
    }

    private fun writeSession(body: ByteArrayOutputStream, summary: SessionSummary) {
        body.u8(0x43); body.u8(0); body.u8(0); body.u16(18); body.u8(9)
        body.u8(253); body.u8(4); body.u8(0x86)
        body.u8(2); body.u8(4); body.u8(0x86)
        body.u8(7); body.u8(4); body.u8(0x86)
        body.u8(8); body.u8(4); body.u8(0x86)
        body.u8(9); body.u8(4); body.u8(0x86)
        body.u8(5); body.u8(1); body.u8(0x00)
        body.u8(6); body.u8(1); body.u8(0x00)
        body.u8(11); body.u8(2); body.u8(0x84)
        body.u8(17); body.u8(1); body.u8(0x02)

        val baseFit = fitTime(summary.startedAtMs)
        val endFit = baseFit + summary.totalDurationSec
        body.u8(0x03)
        body.u32(endFit)
        body.u32(baseFit)
        body.u32(summary.totalDurationSec * 1000)
        body.u32(summary.totalDurationSec * 1000)
        body.u32(summary.totalDistanceM * 100)
        body.u8(1)  // sport = running (Strava-compatible, see class doc)
        body.u8(1)  // sub_sport = treadmill
        body.u16(summary.totalEnergyKcal)
        body.u8(summary.maxHeartRateBpm ?: 0)
    }
}
