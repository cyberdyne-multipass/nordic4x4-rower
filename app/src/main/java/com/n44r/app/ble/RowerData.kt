package com.n44r.app.ble

/**
 * One parsed sample from the FTMS Rower Data characteristic (0x2AD1).
 * Fields mirror what the WaterRower S4 ComModule actually sends, verified
 * against real nRF Connect captures on 2026-09-07.
 */
data class RowerData(
    val strokeRatePerMin: Float,
    val strokeCount: Int,
    val totalDistanceMeters: Int,
    val instantaneousPaceSecPer500m: Int?, // null when device reports 0xFFFF (no stroke yet / stalled)
    val instantaneousPowerWatts: Int,
    val totalEnergyKcal: Int,
    val energyPerHourKcal: Int,
    val energyPerMinuteKcal: Int,
    val heartRateBpm: Int,
    val elapsedTimeSec: Int,
    val timestampMs: Long = System.currentTimeMillis()
)
