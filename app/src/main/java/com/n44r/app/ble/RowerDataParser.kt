package com.n44r.app.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses raw bytes of the FTMS Rower Data characteristic (0x2AD1).
 * Field order/presence per Bluetooth SIG FTMS spec, verified byte-for-byte
 * against real WaterRower S4 ComModule captures (nRF Connect log, 2026-09-07).
 */
object RowerDataParser {

    fun parse(raw: ByteArray): RowerData? {
        if (raw.size < 2) return null
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val flags = buf.short.toInt() and 0xFFFF

        // Bit 0 is inverted: 0 means Stroke Rate/Count ARE present.
        val hasStrokeRateAndCount = (flags and 0x0001) == 0
        val hasAvgStrokeRate = (flags and 0x0002) != 0
        val hasTotalDistance = (flags and 0x0004) != 0
        val hasInstPace = (flags and 0x0008) != 0
        val hasAvgPace = (flags and 0x0010) != 0
        val hasInstPower = (flags and 0x0020) != 0
        val hasAvgPower = (flags and 0x0040) != 0
        val hasResistance = (flags and 0x0080) != 0
        val hasEnergy = (flags and 0x0100) != 0
        val hasHeartRate = (flags and 0x0200) != 0
        val hasMetabolicEquiv = (flags and 0x0400) != 0
        val hasElapsedTime = (flags and 0x0800) != 0
        val hasRemainingTime = (flags and 0x1000) != 0

        var strokeRate = 0f
        var strokeCount = 0
        if (hasStrokeRateAndCount) {
            if (buf.remaining() < 3) return null
            strokeRate = (buf.get().toInt() and 0xFF) * 0.5f
            strokeCount = buf.short.toInt() and 0xFFFF
        }
        if (hasAvgStrokeRate) { if (buf.remaining() < 1) return null; buf.get() }

        var totalDistance = 0
        if (hasTotalDistance) {
            if (buf.remaining() < 3) return null
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt() and 0xFF
            totalDistance = b0 or (b1 shl 8) or (b2 shl 16)
        }

        var instPaceSec: Int? = null
        if (hasInstPace) {
            if (buf.remaining() < 2) return null
            val v = buf.short.toInt() and 0xFFFF
            instPaceSec = if (v == 0xFFFF) null else v
        }
        if (hasAvgPace) { if (buf.remaining() < 2) return null; buf.short }

        var instPower = 0
        if (hasInstPower) {
            if (buf.remaining() < 2) return null
            instPower = buf.short.toInt()
        }
        if (hasAvgPower) { if (buf.remaining() < 2) return null; buf.short }
        if (hasResistance) { if (buf.remaining() < 2) return null; buf.short }

        var totalEnergy = 0
        var energyPerHour = 0
        var energyPerMinute = 0
        if (hasEnergy) {
            if (buf.remaining() < 5) return null
            totalEnergy = buf.short.toInt() and 0xFFFF
            energyPerHour = buf.short.toInt() and 0xFFFF
            energyPerMinute = buf.get().toInt() and 0xFF
        }

        var heartRate = 0
        if (hasHeartRate) {
            if (buf.remaining() < 1) return null
            heartRate = buf.get().toInt() and 0xFF
        }
        if (hasMetabolicEquiv) { if (buf.remaining() < 1) return null; buf.get() }

        var elapsedTime = 0
        if (hasElapsedTime) {
            if (buf.remaining() < 2) return null
            elapsedTime = buf.short.toInt() and 0xFFFF
        }
        if (hasRemainingTime) { if (buf.remaining() < 2) return null; buf.short }

        return RowerData(
            strokeRatePerMin = strokeRate,
            strokeCount = strokeCount,
            totalDistanceMeters = totalDistance,
            instantaneousPaceSecPer500m = instPaceSec,
            instantaneousPowerWatts = instPower,
            totalEnergyKcal = totalEnergy,
            energyPerHourKcal = energyPerHour,
            energyPerMinuteKcal = energyPerMinute,
            heartRateBpm = heartRate,
            elapsedTimeSec = elapsedTime
        )
    }
}
