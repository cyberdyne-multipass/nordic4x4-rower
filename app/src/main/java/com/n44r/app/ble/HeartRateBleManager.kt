package com.n44r.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Optional second BLE connection: connects to whatever nearby device broadcasts
 * the standard Heart Rate Service - in practice the iPhone running a bridge app
 * like HeartCast, relaying Apple Watch heart rate (the Watch itself cannot
 * broadcast BLE directly). Fully independent from the rower connection; if this
 * never connects, the app just shows no heart rate. No chest strap required.
 */
@SuppressLint("MissingPermission")
class HeartRateBleManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var userInitiatedDisconnect = false

    val connectionState = MutableStateFlow(ConnectionState.IDLE)
    val heartRateBpm = MutableStateFlow<Int?>(null)

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        userInitiatedDisconnect = false
        connectionState.value = ConnectionState.SCANNING
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_HEART_RATE))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scope.launch {
            delay(12_000)
            if (connectionState.value == ConnectionState.SCANNING) {
                scanner.stopScan(scanCallback)
                connectionState.value = ConnectionState.IDLE
            }
        }
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        gatt?.disconnect()
        connectionState.value = ConnectionState.IDLE
        heartRateBpm.value = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            adapter?.bluetoothLeScanner?.stopScan(this)
            connectionState.value = ConnectionState.CONNECTING
            gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionState.value = ConnectionState.CONNECTED
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                gatt = null
                heartRateBpm.value = null
                connectionState.value = ConnectionState.IDLE
                if (!userInitiatedDisconnect) startScan()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val characteristic = g.getService(BleConstants.SERVICE_HEART_RATE)
                ?.getCharacteristic(BleConstants.CHAR_HEART_RATE_MEASUREMENT) ?: return
            g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(BleConstants.CLIENT_CHARACTERISTIC_CONFIG)
            descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            descriptor?.let { g.writeDescriptor(it) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != BleConstants.CHAR_HEART_RATE_MEASUREMENT) return
            val raw = characteristic.value
            if (raw.isEmpty()) return
            val flags = raw[0].toInt()
            val is16bit = (flags and 0x01) != 0
            heartRateBpm.value = if (is16bit) {
                (raw[1].toInt() and 0xFF) or ((raw[2].toInt() and 0xFF) shl 8)
            } else {
                raw[1].toInt() and 0xFF
            }
        }
    }

    fun shutdown() {
        scope.cancel()
        gatt?.close()
    }
}
