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
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState { IDLE, SCANNING, CONNECTING, CONNECTED, RECONNECTING }

/**
 * Manages the BLE connection to the WaterRower S4 ComModule (FTMS Fitness Machine).
 * Handles scan-by-service-UUID, saved-MAC auto-connect, notification setup via a
 * small serial operation queue (Android GATT only allows one op in flight), and
 * automatic reconnect on unexpected disconnects (the ComModule drops the link
 * after ~10 min of inactivity - confirmed in testing).
 */
@SuppressLint("MissingPermission")
class RowerBleManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var userInitiatedDisconnect = false
    private var reconnectAddress: String? = null

    private val opQueue = ArrayDeque<() -> Unit>()
    private var opInFlight = false

    val connectionState = MutableStateFlow(ConnectionState.IDLE)
    val rowerData = MutableStateFlow<RowerData?>(null)
    val batteryLevel = MutableStateFlow<Int?>(null)
    val isDemoMode = MutableStateFlow(false)
    val demoSpeedFactor = MutableStateFlow(1.0)
    val demoPhaseHint = MutableStateFlow(PhaseType.MAIN)
    private var demoJob: Job? = null

    // Raw BLE notification log (untouched hex bytes as they arrived) - separate from the
    // parsed RowerData/FIT export, kept for building/refining the demo generator later from
    // a genuine real-hardware capture, same idea as the original nRF Connect log.
    private val rawLog = StringBuilder()
    private val logTimeFmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.GERMAN)
    private fun ByteArray.toHexString() = joinToString("-") { "%02X".format(it) }
    fun getRawLogText(): String = rawLog.toString()
    fun clearRawLog() { rawLog.setLength(0) }

    fun start() {
        demoJob?.cancel()
        isDemoMode.value = false
        userInitiatedDisconnect = false
        val saved = DevicePrefs.getSavedRowerAddress(context)
        if (saved != null) {
            connectByAddress(saved)
        } else {
            startScan()
        }
    }

    /** Procedural demo data (see DemoRowerDataSource). Speed adjustable live via demoSpeedFactor
     *  (1x = realistic real-time pacing; higher = faster pattern cycling). */
    fun startDemo() {
        resumeDemo(DemoGenerator(), fromTick = 0)
    }

    /** Continues (or starts) live demo emission from a given generator/tick - used both by
     *  startDemo() (fresh generator, tick 0) and after a seek-replay (generator carries the
     *  accumulated distance/stroke state, fromTick is where the replay left off). */
    fun resumeDemo(generator: DemoGenerator, fromTick: Int) {
        gatt?.disconnect()
        isDemoMode.value = true
        connectionState.value = ConnectionState.CONNECTED
        demoJob?.cancel()
        demoJob = scope.launch {
            var tick = fromTick
            while (isActive) {
                val speedFactor = demoSpeedFactor.value
                rowerData.value = generator.next(tick, 1.0 / speedFactor, demoPhaseHint.value)
                tick++
                delay((1000 / speedFactor).toLong())
            }
        }
    }

    /** Stops demo emission without touching isDemoMode/connectionState - used right before a
     *  seek-replay so the old live loop can't interleave with the replay's direct state-machine calls. */
    fun stopDemoJob() {
        demoJob?.cancel()
    }


    fun disconnect() {
        demoJob?.cancel()
        isDemoMode.value = false
        userInitiatedDisconnect = true
        gatt?.disconnect()
        connectionState.value = ConnectionState.IDLE
    }

    private fun connectByAddress(address: String) {
        connectionState.value = ConnectionState.CONNECTING
        reconnectAddress = address
        val device = adapter?.getRemoteDevice(address) ?: return
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        connectionState.value = ConnectionState.SCANNING
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_FITNESS_MACHINE))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            adapter?.bluetoothLeScanner?.stopScan(this)
            DevicePrefs.saveRowerAddress(context, result.device.address)
            reconnectAddress = result.device.address
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
                if (!userInitiatedDisconnect) {
                    connectionState.value = ConnectionState.RECONNECTING
                    scope.launch {
                        delay(2000)
                        reconnectAddress?.let { connectByAddress(it) }
                    }
                } else {
                    connectionState.value = ConnectionState.IDLE
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            enqueueNotificationSetup(g, BleConstants.SERVICE_FITNESS_MACHINE, BleConstants.CHAR_ROWER_DATA)
            enqueueNotificationSetup(g, BleConstants.SERVICE_BATTERY, BleConstants.CHAR_BATTERY_LEVEL)
            runNextOp()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val raw = characteristic.value
            val ts = logTimeFmt.format(java.util.Date())
            when (characteristic.uuid) {
                BleConstants.CHAR_ROWER_DATA -> {
                    rawLog.append("$ts Rower Data: (0x) ${raw.toHexString()}\n")
                    RowerDataParser.parse(raw)?.let { rowerData.value = it }
                }
                BleConstants.CHAR_BATTERY_LEVEL -> {
                    rawLog.append("$ts Battery Level: (0x) ${raw.toHexString()}\n")
                    batteryLevel.value = raw.getOrNull(0)?.toInt()?.and(0xFF)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            opInFlight = false
            runNextOp()
        }
    }

    private fun enqueueNotificationSetup(g: BluetoothGatt, serviceUuid: java.util.UUID, charUuid: java.util.UUID) {
        val characteristic = g.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
        opQueue.addLast {
            g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(BleConstants.CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(descriptor)
            } else {
                opInFlight = false
                runNextOp()
            }
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
        gatt?.close()
    }
}
