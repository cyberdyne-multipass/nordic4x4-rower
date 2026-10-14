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
 * Optional second BLE connection to any standard Heart Rate Service peripheral - a chest
 * strap or the iPhone bridge relaying Apple Watch HR. Fully independent from the rower.
 *
 * Robustness:
 *  - Values outside [MIN_VALID_BPM]..[MAX_VALID_BPM] (notably the 0 the Apple-Watch bridge
 *    sends when the watch delivers nothing) are NOT published as heart rate - they count as
 *    "no signal" but still prove the link itself is alive ([lastPacketMs]).
 *  - [ensureConnected] is called periodically by the ViewModel watchdog, so a lost
 *    connection is re-scanned for forever (before: one 12 s scan, then never again).
 *  - Raw packets are logged; RR intervals (flag bit 4) are detected and reported once.
 */
@SuppressLint("MissingPermission")
class HeartRateBleManager(private val context: Context, private val rawLog: RawLog) {

    companion object {
        const val MIN_VALID_BPM = 30
        const val MAX_VALID_BPM = 230
        private const val SCAN_WINDOW_MS = 12_000L
        private const val SCAN_PAUSE_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var userInitiatedDisconnect = false
    private var scanTimeoutJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var lastScanEndMs = 0L

    val connectionState = MutableStateFlow(ConnectionState.IDLE)
    /** Last VALID heart rate, null when the sensor reports nothing usable. */
    val heartRateBpm = MutableStateFlow<Int?>(null)
    /** True once a packet with RR intervals has been seen (needed for future HRV features). */
    val rrIntervalsSeen = MutableStateFlow(false)

    @Volatile var lastPacketMs: Long = 0L
        private set
    @Volatile var lastValidMs: Long = 0L
        private set

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (connectionState.value == ConnectionState.SCANNING || connectionState.value == ConnectionState.CONNECTING ||
            connectionState.value == ConnectionState.CONNECTED) return
        userInitiatedDisconnect = false
        connectionState.value = ConnectionState.SCANNING
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_HEART_RATE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_WINDOW_MS)
            if (connectionState.value == ConnectionState.SCANNING) {
                runCatching { scanner.stopScan(scanCallback) }
                lastScanEndMs = System.currentTimeMillis()
                connectionState.value = ConnectionState.IDLE
            }
        }
    }

    /** Called by the watchdog: keeps (re)scanning while nothing is connected. Android limits
     *  scan starts (5 per 30 s), hence the pause between scan windows. */
    fun ensureConnected() {
        if (userInitiatedDisconnect) return
        if (connectionState.value == ConnectionState.IDLE &&
            System.currentTimeMillis() - lastScanEndMs > SCAN_PAUSE_MS) startScan()
    }

    /** Link looks dead (no packets at all) -> drop it, the watchdog rescans. */
    fun forceReconnect(reason: String) {
        rawLog.event("HF: erzwungener Reconnect ($reason)")
        closeGatt()
        heartRateBpm.value = null
        lastScanEndMs = 0L
        connectionState.value = ConnectionState.IDLE
        startScan()
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        scanTimeoutJob?.cancel()
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        closeGatt()
        connectionState.value = ConnectionState.IDLE
        heartRateBpm.value = null
    }

    private fun closeGatt() {
        connectTimeoutJob?.cancel()
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(this) }
            scope.launch {
                if (connectionState.value != ConnectionState.SCANNING) return@launch // duplicate
                scanTimeoutJob?.cancel()
                connectionState.value = ConnectionState.CONNECTING
                rawLog.event("HF: verbinde mit ${result.device.address} (${runCatching { result.device.name }.getOrNull() ?: "?"})")
                gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                connectTimeoutJob = scope.launch {
                    delay(15_000)
                    if (connectionState.value == ConnectionState.CONNECTING) {
                        rawLog.event("HF: Verbindungsaufbau Timeout")
                        closeGatt()
                        lastScanEndMs = System.currentTimeMillis()
                        connectionState.value = ConnectionState.IDLE
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            rawLog.event("HF: Scan fehlgeschlagen ($errorCode)")
            scope.launch {
                lastScanEndMs = System.currentTimeMillis()
                connectionState.value = ConnectionState.IDLE
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) { runCatching { g.close() }; return }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                rawLog.event("HF: verbunden")
                connectTimeoutJob?.cancel()
                lastPacketMs = System.currentTimeMillis()
                connectionState.value = ConnectionState.CONNECTED
                g.discoverServices()
            } else {
                rawLog.event("HF: getrennt (status=$status, state=$newState)")
                gatt = null
                runCatching { g.close() }
                heartRateBpm.value = null
                scope.launch {
                    lastScanEndMs = 0L
                    connectionState.value = ConnectionState.IDLE
                    if (!userInitiatedDisconnect) startScan()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val characteristic = g.getService(BleConstants.SERVICE_HEART_RATE)
                ?.getCharacteristic(BleConstants.CHAR_HEART_RATE_MEASUREMENT)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                rawLog.event("HF: Service-Discovery fehlgeschlagen (status=$status)")
                scope.launch { forceReconnect("discovery") }
                return
            }
            g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(BleConstants.CLIENT_CHARACTERISTIC_CONFIG)
            @Suppress("DEPRECATION")
            descriptor?.let { it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(it) }
        }

        @Deprecated("Deprecated in API 33, still the one called on Android 12")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != BleConstants.CHAR_HEART_RATE_MEASUREMENT) return
            val raw = characteristic.value ?: return
            if (raw.size < 2) return
            rawLog.bytes("Heart Rate", raw)
            val now = System.currentTimeMillis()
            lastPacketMs = now
            val flags = raw[0].toInt() and 0xFF
            val is16bit = (flags and 0x01) != 0
            if (is16bit && raw.size < 3) return
            val bpm = if (is16bit) (raw[1].toInt() and 0xFF) or ((raw[2].toInt() and 0xFF) shl 8) else raw[1].toInt() and 0xFF
            if ((flags and 0x10) != 0 && !rrIntervalsSeen.value) {
                rrIntervalsSeen.value = true
                rawLog.event("HF: Sensor sendet RR-Intervalle")
            }
            if (bpm in MIN_VALID_BPM..MAX_VALID_BPM) {
                lastValidMs = now
                heartRateBpm.value = bpm
            } else {
                heartRateBpm.value = null
            }
        }
    }

    fun shutdown() {
        scope.cancel()
        closeGatt()
    }
}
