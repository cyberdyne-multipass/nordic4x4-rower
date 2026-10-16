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

/** A heart-rate sensor seen during "Sensor suchen". */
data class HrDeviceInfo(val address: String, val name: String, val rssi: Int)

/**
 * Second, independent BLE connection to any standard Heart Rate Service (0x180D) sensor:
 * a chest strap (e.g. Garmin HRM 600) or the iPhone bridge relaying Apple Watch HR.
 *
 * - Preferred sensor: if one is chosen in the settings, the scan waits up to
 *   [PREFERRED_WAIT_MS] for it (matched by address OR name) and only then falls back to any
 *   other HR sensor seen - so strap and iPhone bridge can both be around without the app
 *   grabbing the wrong one, and the bridge still works as backup when the strap is missing.
 * - Values outside [MIN_VALID_BPM]..[MAX_VALID_BPM] (the 0 of the Apple bridge) are "no data".
 *   Straps report skin contact (flags bit 1/2); "no contact" is also "no data" and exposed
 *   via [skinContact] so the UI can say "Elektroden befeuchten" instead of a generic warning.
 * - RR intervals (flags bit 4, 1/1024 s) are decoded and handed to [onRrIntervals].
 * - Battery level of the sensor is read once after connecting (if it has a Battery Service).
 * - [ensureConnected] is called by the ViewModel watchdog -> endless rescan while disconnected.
 */
@SuppressLint("MissingPermission")
class HeartRateBleManager(private val context: Context, private val rawLog: RawLog) {

    companion object {
        const val MIN_VALID_BPM = 30
        const val MAX_VALID_BPM = 230
        /** BLE jitter allowance for the duplicate-packet filter (see onCharacteristicChanged). */
        const val DUPLICATE_SLACK_MS = 150
        private const val SCAN_WINDOW_MS = 12_000L
        private const val PREFERRED_WAIT_MS = 6_000L
        private const val SCAN_PAUSE_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var userInitiatedDisconnect = false
    private var scanTimeoutJob: Job? = null
    private var fallbackJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var lastScanEndMs = 0L
    private var fallbackCandidate: BluetoothDevice? = null
    @Volatile private var connectFailures = 0
    @Volatile private var connectedAtMs = 0L
    @Volatile private var quickDrops = 0
    private var lastRaw: ByteArray? = null
    private var lastRawMs = 0L
    private val plausibility = HrPlausibility()
    private var lastTrusted = true

    private fun bondLabel(state: Int) = when (state) {
        BluetoothDevice.BOND_BONDED -> "gekoppelt"
        BluetoothDevice.BOND_BONDING -> "koppelt…"
        BluetoothDevice.BOND_NONE -> "nicht gekoppelt"
        else -> "?"
    }

    val connectionState = MutableStateFlow(ConnectionState.IDLE)
    /** Last VALID heart rate, null when the sensor reports nothing usable. */
    val heartRateBpm = MutableStateFlow<Int?>(null)
    /** Name of the connected sensor (e.g. "HRM 600:123456" or the iPhone bridge). */
    val connectedName = MutableStateFlow<String?>(null)
    /** Skin contact as reported by the sensor; null = sensor doesn't report it. */
    val skinContact = MutableStateFlow<Boolean?>(null)
    val batteryLevel = MutableStateFlow<Int?>(null)
    /** True once a packet with RR intervals has been seen. */
    val rrIntervalsSeen = MutableStateFlow(false)
    /** Sensors found by [startDiscovery]. */
    val discovered = MutableStateFlow<List<HrDeviceInfo>>(emptyList())
    val discovering = MutableStateFlow(false)

    /** Called (on a binder thread) with the wall time and the RR intervals in ms of each packet,
     *  each paired with "plausible" (false = artifact, e.g. strap without skin contact). */
    @Volatile var onRrIntervals: ((Long, List<Pair<Int, Boolean>>) -> Unit)? = null

    @Volatile var lastPacketMs: Long = 0L
        private set
    @Volatile var lastValidMs: Long = 0L
        private set

    // Link quality: RSSI + longest packet gap (HR sensors have no common fixed send rate).
    private val qualityTracker = LinkQualityTracker(expectedIntervalMs = null)
    val quality = MutableStateFlow(LinkQuality.NONE)
    @Volatile private var setupDone = false

    init {
        scope.launch {
            var tick = 0
            while (isActive) {
                delay(1000)
                val connected = connectionState.value == ConnectionState.CONNECTED
                if (connected && setupDone && tick % 2 == 0) runCatching { gatt?.readRemoteRssi() }
                quality.value = if (connected) qualityTracker.snapshot() else LinkQuality.NONE
                tick++
            }
        }
    }

    private fun preferred(): Pair<String, String>? = DevicePrefs.getPreferredHr(context)

    private fun matchesPreferred(device: BluetoothDevice, name: String?): Boolean {
        val (addr, prefName) = preferred() ?: return false
        return device.address == addr || (prefName.isNotBlank() && name == prefName)
    }

    private fun deviceName(result: ScanResult): String? =
        result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (connectionState.value == ConnectionState.SCANNING || connectionState.value == ConnectionState.CONNECTING ||
            connectionState.value == ConnectionState.CONNECTED) return
        userInitiatedDisconnect = false
        connectionState.value = ConnectionState.SCANNING
        fallbackCandidate = null
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_HEART_RATE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
        // with a preferred sensor: after a grace period take any other HR sensor as fallback
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            delay(if (preferred() != null) PREFERRED_WAIT_MS else 0)
            while (connectionState.value == ConnectionState.SCANNING) {
                fallbackCandidate?.let { connect(it, "Ersatz-Sensor") ; return@launch }
                delay(250)
            }
        }
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
        if (userInitiatedDisconnect || discovering.value) return
        if (connectionState.value == ConnectionState.IDLE &&
            System.currentTimeMillis() - lastScanEndMs > SCAN_PAUSE_MS) startScan()
    }

    /** Link looks dead (no packets at all) or the user wants another sensor -> reconnect. */
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
        scanTimeoutJob?.cancel(); fallbackJob?.cancel()
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        closeGatt()
        connectionState.value = ConnectionState.IDLE
        heartRateBpm.value = null
    }

    private fun closeGatt() {
        connectTimeoutJob?.cancel()
        connectedName.value = null
        skinContact.value = null
        batteryLevel.value = null
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private fun connect(device: BluetoothDevice, why: String) {
        if (connectionState.value != ConnectionState.SCANNING) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanTimeoutJob?.cancel(); fallbackJob?.cancel()
        connectionState.value = ConnectionState.CONNECTING
        val name = runCatching { device.name }.getOrNull()
        rawLog.event("HF: verbinde mit ${device.address} (${name ?: "?"}) - $why")
        connectedName.value = name ?: device.address
        connectTimeoutJob?.cancel() // a timer of an earlier attempt must not kill this one
        // After repeated GATT 133 errors switch to autoConnect=true: the stack then waits for the
        // device's next connectable advertisement itself - a well-known workaround for 133.
        val autoConnect = connectFailures >= 2
        connectTimeoutJob = scope.launch {
            delay(600) // give the controller a moment after stopScan (another 133 trigger)
            if (connectionState.value != ConnectionState.CONNECTING) return@launch
            if (autoConnect) rawLog.event("HF: Verbindungsversuch mit autoConnect (nach $connectFailures Fehlversuchen)")
            gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
            delay(if (autoConnect) 30_000 else 15_000)
            if (connectionState.value == ConnectionState.CONNECTING) {
                rawLog.event("HF: Verbindungsaufbau Timeout")
                closeGatt()
                lastScanEndMs = System.currentTimeMillis()
                connectionState.value = ConnectionState.IDLE
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = deviceName(result)
            scope.launch {
                if (connectionState.value != ConnectionState.SCANNING) return@launch
                when {
                    preferred() == null -> connect(result.device, "erster gefundener Sensor")
                    matchesPreferred(result.device, name) -> connect(result.device, "bevorzugter Sensor")
                    fallbackCandidate == null -> fallbackCandidate = result.device
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

    // ---------- "Sensor suchen" in the settings ----------
    fun startDiscovery() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        discovered.value = emptyList()
        discovering.value = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_HEART_RATE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, discoveryCallback) }
        // the currently connected sensor does not advertise -> list it explicitly
        gatt?.device?.let { d ->
            discovered.value = listOf(HrDeviceInfo(d.address, connectedName.value ?: d.address, 0))
        }
        scope.launch {
            delay(10_000)
            stopDiscovery()
        }
    }

    fun stopDiscovery() {
        if (!discovering.value) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(discoveryCallback) }
        discovering.value = false
    }

    private val discoveryCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val info = HrDeviceInfo(result.device.address, deviceName(result) ?: result.device.address, result.rssi)
            scope.launch {
                discovered.value = (discovered.value.filter { it.address != info.address } + info).sortedByDescending { it.rssi }
            }
        }
    }

    /** Selects (or with null clears) the preferred sensor and reconnects to apply it. */
    fun choosePreferred(info: HrDeviceInfo?) {
        DevicePrefs.savePreferredHr(context, info?.address, info?.name)
        rawLog.event("HF: bevorzugter Sensor = ${info?.name ?: "automatisch"}")
        val connectedAddr = gatt?.device?.address
        if (info != null && connectedAddr != info.address) forceReconnect("anderer Sensor gewählt")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) { runCatching { g.close() }; return }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                val bond = runCatching { g.device.bondState }.getOrDefault(-1)
                rawLog.event("HF: verbunden (Kopplung: ${bondLabel(bond)})")
                connectFailures = 0
                plausibility.reset(); lastTrusted = true
                connectedAtMs = System.currentTimeMillis()
                setupDone = false
                qualityTracker.reset()
                qualityTracker.expectedIntervalMs = null
                connectTimeoutJob?.cancel()
                lastPacketMs = System.currentTimeMillis()
                connectionState.value = ConnectionState.CONNECTED
                // short pause before discovery - several sensors (and Android 12) drop the link
                // if discovery starts while the link/encryption setup is still in progress
                scope.launch { delay(600); if (gatt == g) runCatching { g.discoverServices() } }
            } else {
                rawLog.event("HF: getrennt (status=$status, state=$newState)")
                connectTimeoutJob?.cancel()
                if (connectionState.value == ConnectionState.CONNECTING) connectFailures++
                // Sensor drops the link within seconds after connecting, again and again: typical for
                // straps that require pairing (bonding) before they serve data -> pair once.
                val quickDrop = connectedAtMs > 0 && System.currentTimeMillis() - connectedAtMs < 5_000
                connectedAtMs = 0L
                if (quickDrop) {
                    quickDrops++
                    val dev = g.device
                    if (quickDrops >= 2 && runCatching { dev.bondState }.getOrDefault(-1) == BluetoothDevice.BOND_NONE) {
                        rawLog.event("HF: Sensor trennt sofort wieder -> Kopplung wird angefordert")
                        runCatching { dev.createBond() }
                    }
                } else quickDrops = 0
                gatt = null
                runCatching { g.close() }
                heartRateBpm.value = null
                scope.launch {
                    connectedName.value = null; skinContact.value = null; batteryLevel.value = null
                    lastScanEndMs = 0L
                    connectionState.value = ConnectionState.IDLE
                    if (!userInitiatedDisconnect) startScan()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val characteristic = g.getService(BleConstants.SERVICE_HEART_RATE)
                ?.getCharacteristic(BleConstants.CHAR_HEART_RATE_MEASUREMENT)
            rawLog.event("HF: Services gefunden (status=$status): " +
                g.services.joinToString(",") { it.uuid.toString().substring(4, 8) })
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

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            rawLog.event("HF: Notifications aktiviert (status=$status)")
            // notifications are set up -> now (one op at a time) read the sensor's battery level
            val battery = g.getService(BleConstants.SERVICE_BATTERY)?.getCharacteristic(BleConstants.CHAR_BATTERY_LEVEL)
            if (battery == null || runCatching { g.readCharacteristic(battery) }.getOrDefault(false) != true) setupDone = true
        }

        @Deprecated("Deprecated in API 33, still the one called on Android 12")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == BleConstants.CHAR_BATTERY_LEVEL && status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                val level = characteristic.value?.getOrNull(0)?.toInt()?.and(0xFF)
                batteryLevel.value = level
                rawLog.event("HF: Sensor-Akku $level %")
            }
            setupDone = true // from now on RSSI polling may use the GATT
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) qualityTracker.onRssi(rssi)
        }

        @Deprecated("Deprecated in API 33, still the one called on Android 12")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != BleConstants.CHAR_HEART_RATE_MEASUREMENT) return
            @Suppress("DEPRECATION")
            val raw = characteristic.value ?: return
            rawLog.bytes("Heart Rate", raw)
            val m = HeartRateParser.parse(raw) ?: return
            val now = System.currentTimeMillis()
            lastPacketMs = now
            // The HRM 600 sends every measurement twice (~0.45-0.72 s apart, identical bytes). A real
            // next beat arrives about one RR interval after the previous one (plus BLE jitter), so an
            // identical packet within RR + 150 ms of the FIRST copy is a duplicate. (Real beats with a
            // bit-identical RR and bpm are rare; losing one of those costs less than doubled RRs.
            // The old 75 % rule let ~20 duplicates per workout through.)
            val lastRr = m.rrMs.lastOrNull()
            val duplicate = lastRr != null && lastRaw?.contentEquals(raw) == true &&
                now - lastRawMs < lastRr + DUPLICATE_SLACK_MS
            if (!duplicate) { lastRaw = raw.copyOf(); lastRawMs = now }
            // quality judges unique packets only - otherwise the strap showed "Paket alle 0,5 s"
            // NOTE: no fixed expected rate for HR - the HeartCast bridge also reports a skin-contact
            // flag but sends only every ~2.5 s; packet flow is judged by gaps instead (LinkQuality)
            if (!duplicate) qualityTracker.onPacket(now)
            // Straps without contact bits (HRM 600) still give themselves away via their RR
            // intervals when they lose skin contact -> plausibility check (see HrPlausibility)
            val trusted = if (duplicate) lastTrusted else plausibility.check(m.bpm, m.rrMs).also { lastTrusted = it }
            val contact = m.skinContact ?: if (m.rrMs.isNotEmpty()) trusted else null
            if (contact == false && skinContact.value != false) rawLog.event("HF: kein Hautkontakt / unplausible Werte")
            if (contact == true && skinContact.value == false) rawLog.event("HF: Hautkontakt wieder ok")
            skinContact.value = contact
            if (m.rrMs.isNotEmpty() && !duplicate) {
                if (!rrIntervalsSeen.value) {
                    rrIntervalsSeen.value = true
                    rawLog.event("HF: Sensor sendet RR-Intervalle")
                }
                onRrIntervals?.invoke(now, m.rrMs.map { it to (trusted && plausibility.isValidRr(it)) })
            }
            val valid = m.bpm in MIN_VALID_BPM..MAX_VALID_BPM && contact != false
            if (valid) {
                lastValidMs = now
                heartRateBpm.value = m.bpm
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
