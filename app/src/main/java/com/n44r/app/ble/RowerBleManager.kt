package com.n44r.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.n44r.app.session.PhaseType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

enum class ConnectionState { IDLE, SCANNING, CONNECTING, CONNECTED, RECONNECTING }

/**
 * Manages the BLE connection to the WaterRower S4 ComModule (FTMS Fitness Machine).
 *
 * Robustness (after the 2026-10-13 session where notifications silently stopped while the
 * link still counted as "connected"):
 *  - [lastNotificationMs] lets the ViewModel's watchdog detect a stalled data stream.
 *  - [resubscribe] re-writes the notification descriptors (cheap first repair step),
 *    [forceReconnect] tears the GATT down completely and connects again.
 *  - Connect attempts time out after [CONNECT_TIMEOUT_MS] instead of hanging forever.
 *  - The GATT op queue is reset on every new connection (a disconnect in the middle of the
 *    notification setup used to leave opInFlight=true -> no notifications after reconnect).
 *  - Every connection event is written to the raw log as an EVENT line for later analysis.
 */
@SuppressLint("MissingPermission")
class RowerBleManager(private val context: Context, private val rawLog: RawLog) {

    companion object { private const val CONNECT_TIMEOUT_MS = 15_000L }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var userInitiatedDisconnect = false
    private var reconnectAddress: String? = null
    private var reconnectJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var reconnectAttempts = 0

    private val opQueue = ArrayDeque<() -> Unit>()
    private var opInFlight = false

    val connectionState = MutableStateFlow(ConnectionState.IDLE)
    val rowerData = MutableStateFlow<RowerData?>(null)
    val batteryLevel = MutableStateFlow<Int?>(null)
    val isDemoMode = MutableStateFlow(false)
    val demoSpeedFactor = MutableStateFlow(1.0)
    val demoPhaseHint = MutableStateFlow(PhaseType.MAIN)
    private var demoJob: Job? = null

    /** Wall-clock time of the last rower data notification (or demo sample). */
    @Volatile var lastNotificationMs: Long = 0L
        private set

    /** Demo only: suppress emission until this time to simulate a rower-data outage. */
    @Volatile var demoDropUntilMs: Long = 0L

    fun start() {
        demoJob?.cancel()
        isDemoMode.value = false
        userInitiatedDisconnect = false
        reconnectAttempts = 0
        val saved = DevicePrefs.getSavedRowerAddress(context)
        if (saved != null) connectByAddress(saved) else startScan()
    }

    fun startDemo() {
        resumeDemo(DemoGenerator(), fromTick = 0)
    }

    /** Continues (or starts) live demo emission from a given generator/tick. */
    fun resumeDemo(generator: DemoGenerator, fromTick: Int) {
        closeGatt()
        isDemoMode.value = true
        connectionState.value = ConnectionState.CONNECTED
        lastNotificationMs = System.currentTimeMillis()
        demoJob?.cancel()
        demoJob = scope.launch {
            var tick = fromTick
            while (isActive) {
                val speedFactor = demoSpeedFactor.value
                val sample = generator.next(tick, 1.0 / speedFactor, demoPhaseHint.value)
                tick++
                if (System.currentTimeMillis() >= demoDropUntilMs) {
                    lastNotificationMs = System.currentTimeMillis()
                    rowerData.value = sample
                }
                delay((1000 / speedFactor).toLong())
            }
        }
    }

    fun stopDemoJob() { demoJob?.cancel() }

    fun disconnect() {
        demoJob?.cancel()
        isDemoMode.value = false
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        connectTimeoutJob?.cancel()
        closeGatt()
        connectionState.value = ConnectionState.IDLE
    }

    /** Step 1 of the watchdog: re-enable notifications on the existing link. */
    fun resubscribe() {
        if (isDemoMode.value) return
        val g = gatt ?: return
        rawLog.event("Rower: keine Daten - Notifications werden neu aktiviert")
        resetOpQueue()
        enqueueNotificationSetup(g, BleConstants.SERVICE_FITNESS_MACHINE, BleConstants.CHAR_ROWER_DATA)
        runNextOp()
    }

    /** Step 2 of the watchdog: complete teardown + fresh connection. */
    fun forceReconnect(reason: String) {
        if (isDemoMode.value || userInitiatedDisconnect) return
        rawLog.event("Rower: erzwungener Reconnect ($reason)")
        closeGatt()
        scheduleReconnect(1000)
    }

    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private fun scheduleReconnect(delayMs: Long) {
        connectionState.value = ConnectionState.RECONNECTING
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (userInitiatedDisconnect || isDemoMode.value) return@launch
            val addr = reconnectAddress ?: DevicePrefs.getSavedRowerAddress(context)
            if (addr != null) connectByAddress(addr) else startScan()
        }
    }

    private fun connectByAddress(address: String) {
        closeGatt()
        resetOpQueue()
        connectionState.value = if (connectionState.value == ConnectionState.RECONNECTING) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
        reconnectAddress = address
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: run { startScan(); return }
        rawLog.event("Rower: verbinde mit $address")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (connectionState.value != ConnectionState.CONNECTED && !userInitiatedDisconnect && !isDemoMode.value) {
                rawLog.event("Rower: Verbindungsaufbau Timeout")
                closeGatt()
                reconnectAttempts++
                // after a few failed direct attempts, fall back to a scan (address may have changed)
                if (reconnectAttempts >= 4) { reconnectAttempts = 0; reconnectAddress = null; startScan() }
                else scheduleReconnect(2000)
            }
        }
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        connectionState.value = ConnectionState.SCANNING
        rawLog.event("Rower: Scan gestartet")
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_FITNESS_MACHINE))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(this) }
            if (connectionState.value != ConnectionState.SCANNING) return // duplicate result
            DevicePrefs.saveRowerAddress(context, result.device.address)
            connectionState.value = ConnectionState.CONNECTING
            scope.launch { connectByAddress(result.device.address) }
        }

        override fun onScanFailed(errorCode: Int) {
            rawLog.event("Rower: Scan fehlgeschlagen ($errorCode)")
            scheduleReconnect(5000)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) { runCatching { g.close() }; return } // stale callback of a closed connection
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                rawLog.event("Rower: verbunden")
                connectTimeoutJob?.cancel()
                reconnectAttempts = 0
                connectionState.value = ConnectionState.CONNECTED
                lastNotificationMs = System.currentTimeMillis() // give the setup a fair chance
                g.discoverServices()
            } else {
                rawLog.event("Rower: getrennt (status=$status, state=$newState)")
                gatt = null
                runCatching { g.close() }
                if (!userInitiatedDisconnect) scope.launch { scheduleReconnect(2000) }
                else connectionState.value = ConnectionState.IDLE
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || g.getService(BleConstants.SERVICE_FITNESS_MACHINE) == null) {
                rawLog.event("Rower: Service-Discovery fehlgeschlagen (status=$status)")
                scope.launch { forceReconnect("discovery") }
                return
            }
            scope.launch { // op queue is only ever touched on the main thread
                resetOpQueue()
                enqueueNotificationSetup(g, BleConstants.SERVICE_FITNESS_MACHINE, BleConstants.CHAR_ROWER_DATA)
                enqueueNotificationSetup(g, BleConstants.SERVICE_BATTERY, BleConstants.CHAR_BATTERY_LEVEL)
                runNextOp()
            }
        }

        @Deprecated("Deprecated in API 33, still the one called on Android 12")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val raw = characteristic.value ?: return
            when (characteristic.uuid) {
                BleConstants.CHAR_ROWER_DATA -> {
                    lastNotificationMs = System.currentTimeMillis()
                    rawLog.bytes("Rower Data", raw)
                    RowerDataParser.parse(raw)?.let { rowerData.value = it }
                }
                BleConstants.CHAR_BATTERY_LEVEL -> {
                    rawLog.bytes("Battery Level", raw)
                    batteryLevel.value = raw.getOrNull(0)?.toInt()?.and(0xFF)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) rawLog.event("Rower: Descriptor-Write fehlgeschlagen (status=$status)")
            scope.launch { opInFlight = false; runNextOp() }
        }
    }

    private fun resetOpQueue() {
        opQueue.clear()
        opInFlight = false
    }

    private fun enqueueNotificationSetup(g: BluetoothGatt, serviceUuid: java.util.UUID, charUuid: java.util.UUID) {
        val characteristic = g.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
        opQueue.addLast {
            g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(BleConstants.CLIENT_CHARACTERISTIC_CONFIG)
            @Suppress("DEPRECATION")
            val started = descriptor != null && run {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(descriptor)
            }
            if (!started) { opInFlight = false; runNextOp() }
        }
    }

    private fun runNextOp() {
        if (opInFlight) return
        val next = opQueue.removeFirstOrNull() ?: return
        opInFlight = true
        next()
    }

    fun shutdown() {
        scope.cancel()
        closeGatt()
    }
}
