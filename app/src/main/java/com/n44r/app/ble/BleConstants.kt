package com.n44r.app.ble

import java.util.UUID

object BleConstants {
    fun sig(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    val SERVICE_FITNESS_MACHINE: UUID = sig("1826")
    val CHAR_ROWER_DATA: UUID = sig("2ad1")

    val SERVICE_BATTERY: UUID = sig("180f")
    val CHAR_BATTERY_LEVEL: UUID = sig("2a19")

    val SERVICE_HEART_RATE: UUID = sig("180d")
    val CHAR_HEART_RATE_MEASUREMENT: UUID = sig("2a37")

    val CLIENT_CHARACTERISTIC_CONFIG: UUID = sig("2902")
}
