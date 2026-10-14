package com.n44r.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n44r.app.alert.AlertSounds
import com.n44r.app.ble.ConnectionState
import com.n44r.app.ble.DemoGenerator
import com.n44r.app.ble.DevicePrefs
import com.n44r.app.ble.HeartRateBleManager
import com.n44r.app.ble.RawLog
import com.n44r.app.ble.RowerBleManager
import com.n44r.app.data.AppPrefs
import com.n44r.app.export.SummaryImageGenerator
import com.n44r.app.fit.FitWriter
import com.n44r.app.session.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

enum class Screen { PROFILE, START, WAITING, SETUP, DASHBOARD, SUMMARY, EXPORT, RESUME }

/** Live connection health shown on the dashboard (banner, HR tile). */
data class LinkStatus(
    val rowerLost: Boolean = false,
    val rowerSilentSec: Int = 0,
    val rowerState: ConnectionState = ConnectionState.IDLE,
    val hrExpected: Boolean = false,
    val hrLost: Boolean = false,
    val hrSilentSec: Int = 0,
    val hrState: ConnectionState = ConnectionState.IDLE,
    /** HR link is alive but the sensor only sends invalid values (0) - a watch/strap issue. */
    val hrSensorSendsInvalid: Boolean = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val ROWER_LOST_MS = 3_000L
        private const val ROWER_RESUBSCRIBE_MS = 5_000L
        private const val ROWER_RECONNECT_MS = 10_000L
        private const val HR_LOST_MS = 8_000L
        private const val HR_DEAD_LINK_MS = 20_000L
        private const val SOUND_REPEAT_MS = 20_000L
        private const val SOUND_MAX_REPEATS = 5
    }

    val rawLog = RawLog()
    val rowerBle = RowerBleManager(app, rawLog)
    val heartRateBle = HeartRateBleManager(app, rawLog)

    var stateMachine: SessionStateMachine? = null
        private set
    private var lastModeLabel = ""

    // ---- profile ----
    val profile = MutableStateFlow(AppPrefs.loadProfile(app))

    // ---- training settings (restored from last use) ----
    private val saved = AppPrefs.loadSettings(app)
    val screen = MutableStateFlow(if (profile.value == null) Screen.PROFILE else Screen.START)
    val sessionMode = MutableStateFlow(saved?.mode ?: SessionMode.INTERVAL)
    val mainMetric = MutableStateFlow(saved?.mainMetric ?: MainMetric.DISTANCE)
    val intervalConfig = MutableStateFlow(saved?.interval ?: IntervalConfig.NORDIC_4X4)
    val timeTargetSec = MutableStateFlow(saved?.timeTargetSec ?: 1200)
    val distanceTargetM = MutableStateFlow(saved?.distanceTargetM ?: 5000)
    val heartRateEnabled = MutableStateFlow(DevicePrefs.getHeartRateEnabled(app))
    val warnSoundsEnabled = MutableStateFlow(DevicePrefs.getWarnSoundsEnabled(app))

    val sessionUi = MutableStateFlow<SessionUiState?>(null)
    val linkStatus = MutableStateFlow(LinkStatus())
    val lastSummary = MutableStateFlow<SessionSummary?>(null)
    val exportedFiles = MutableStateFlow<List<File>>(emptyList())

    // ---- crash recovery ----
    val resumable = MutableStateFlow<List<SessionJournal.Info>>(emptyList())
    val resumeData = MutableStateFlow<SessionJournal.Data?>(null)
    private var resumeFile: File? = null
    private var pendingResume: SessionStateMachine? = null
    private var pendingResumeWriter: SessionJournal.Writer? = null
    private var journal: SessionJournal.Writer? = null
    /** Session time (s) a pending resume continues from - shown on the waiting screen. */
    val pendingResumeAtSec = MutableStateFlow<Int?>(null)
    val crashReport = MutableStateFlow<File?>(null)

    // Auto-start: while WAITING we watch for the first real movement and only then start.
    private var waitingBaseline: Pair<Int, Int>? = null

    // ---- watchdog state ----
    private var watchdogJob: Job? = null
    private var hrGraceBaseMs = 0L
    private var rowerResubscribed = false
    private var lastRowerReconnectMs = 0L
    private var lastHrReconnectMs = 0L
    private var lastDemoHrMs = 0L
    private var demoHrDropUntilMs = 0L
    private class AlertTracker { var since = 0L; var lastSound = 0L; var count = 0 }
    private val rowerAlert = AlertTracker()
    private val hrAlert = AlertTracker()

    private val workoutsDir: File get() = File(getApplication<Application>().filesDir, "workouts").apply { mkdirs() }

    init {
        viewModelScope.launch {
            rowerBle.rowerData.collect { data ->
                data ?: return@collect
                if (screen.value == Screen.WAITING) {
                    val base = waitingBaseline
                    if (base == null) {
                        waitingBaseline = data.totalDistanceMeters to data.strokeCount
                        return@collect
                    } else if (data.totalDistanceMeters > base.first || data.strokeCount > base.second) {
                        if (pendingResume != null) activateResumed() else startSession()
                    } else return@collect
                }
                val sm = stateMachine ?: return@collect
                if (demoHrActive() && data.heartRateBpm > 0 && System.currentTimeMillis() >= demoHrDropUntilMs) {
                    sm.setHeartRate(data.heartRateBpm)
                    lastDemoHrMs = System.currentTimeMillis()
                }
                sm.onData(data)
                sessionUi.value = sm.uiState.value
                rowerBle.demoPhaseHint.value = sm.currentPhaseType()
            }
        }
        viewModelScope.launch {
            heartRateBle.heartRateBpm.collect { bpm -> if (!demoHrActive()) stateMachine?.setHeartRate(bpm) }
        }
        viewModelScope.launch { heartRateEnabled.collect { DevicePrefs.saveHeartRateEnabled(app, it) } }
        viewModelScope.launch { warnSoundsEnabled.collect { DevicePrefs.saveWarnSoundsEnabled(app, it) } }
        viewModelScope.launch {
            combine(sessionMode, mainMetric, timeTargetSec, distanceTargetM, intervalConfig) { mode, metric, t, d, iv ->
                AppPrefs.Settings(mode, metric, t, d, iv)
            }.collect { AppPrefs.saveSettings(app, it) }
        }
        refreshStartInfo()
    }

    /** Simulated HR from the demo curves is used only while no real HR bridge is connected. */
    private fun demoHrActive() =
        rowerBle.isDemoMode.value && heartRateBle.connectionState.value != ConnectionState.CONNECTED

    // ---------- profile ----------
    fun saveProfile(p: UserProfile) {
        AppPrefs.saveProfile(getApplication(), p)
        profile.value = p
        if (screen.value == Screen.PROFILE) screen.value = Screen.START
    }

    /** Fills work/rest HR targets from the profile (Nordic 4x4: 85-95 % / 60-70 %). */
    fun applyProfileHrZones() {
        val p = profile.value ?: return
        val work = HrZones.nordicWorkZone(p)
        val rest = HrZones.nordicRecoveryZone(p)
        intervalConfig.value = intervalConfig.value.copy(
            workTarget = PhaseTarget(type = TargetType.HEART_RATE, hrZoneLowBpm = work.first, hrZoneHighBpm = work.second),
            restTarget = PhaseTarget(type = TargetType.HEART_RATE, hrZoneLowBpm = rest.first, hrZoneHighBpm = rest.second)
        )
    }

    // ---------- navigation ----------
    fun openSettings() { screen.value = Screen.SETUP }
    fun openProfile() { screen.value = Screen.PROFILE }
    fun backToStart() { refreshStartInfo(); screen.value = Screen.START }

    private fun refreshStartInfo() {
        val app = getApplication<Application>()
        resumable.value = runCatching { SessionJournal.list(app.filesDir) }.getOrDefault(emptyList())
        crashReport.value = workoutsDir.listFiles { f -> f.name.startsWith("crash_") }?.maxByOrNull { it.name }
    }

    private fun connectDevices() {
        if (rowerBle.isDemoMode.value || rowerBle.connectionState.value != ConnectionState.CONNECTED) rowerBle.start()
        if (heartRateEnabled.value && heartRateBle.connectionState.value != ConnectionState.CONNECTED) heartRateBle.startScan()
    }

    /** Landing-screen "Start": connect (if needed) and wait for the first stroke. */
    fun startFromLanding() {
        clearPendingResume()
        connectDevices()
        rawLog.clear()
        waitingBaseline = null
        screen.value = Screen.WAITING
    }

    fun startDemoMode() {
        clearPendingResume()
        rowerBle.startDemo()
        if (heartRateEnabled.value && heartRateBle.connectionState.value != ConnectionState.CONNECTED) heartRateBle.startScan()
        rawLog.clear()
        waitingBaseline = null
        screen.value = Screen.WAITING
    }

    fun cancelWaiting() {
        if (rowerBle.isDemoMode.value) rowerBle.disconnect()
        clearPendingResume()
        backToStart()
    }

    private fun clearPendingResume() {
        pendingResumeWriter?.close()
        pendingResumeWriter = null
        pendingResume = null
        pendingResumeAtSec.value = null
    }

    private fun modeLabelFor(mode: SessionMode) = when (mode) {
        SessionMode.TIME -> "Zeittraining"
        SessionMode.DISTANCE -> "Distanztraining"
        SessionMode.INTERVAL -> "Intervalltraining"
    }

    private fun startSession() {
        val plan = buildPhasePlan(sessionMode.value, timeTargetSec.value, distanceTargetM.value, intervalConfig.value)
        val sm = SessionStateMachine(plan, sessionMode.value)
        val now = System.currentTimeMillis()
        sm.start(now)
        lastModeLabel = modeLabelFor(sessionMode.value)
        val demo = rowerBle.isDemoMode.value
        // Demo sessions are journaled too (marked as demo) so resume can be tested without the rower.
        journal = runCatching {
            SessionJournal.create(getApplication<Application>().filesDir, now, sessionMode.value, lastModeLabel, plan, demo = demo)
        }.getOrNull()
        sm.onRecord = { journal?.record(it) }
        if (!demo) rawLog.attachFile(File(workoutsDir, "n44r_${now}_rawble.txt"), append = false)
        rawLog.event("Session gestartet ($lastModeLabel)")
        stateMachine = sm
        startWatchdog()
        screen.value = Screen.DASHBOARD
    }

    // ---------- crash recovery / resume ----------
    fun openResume() {
        refreshStartInfo()
        resumeData.value = null
        resumeFile = null
        screen.value = Screen.RESUME
    }

    fun selectResume(info: SessionJournal.Info) {
        resumeFile = info.file
        resumeData.value = runCatching { SessionJournal.load(info.file) }.getOrNull()
    }

    fun clearResumeSelection() { resumeData.value = null; resumeFile = null }

    /** Restores the selected workout up to [cutElapsedMs] and waits for the first stroke. */
    fun confirmResume(cutElapsedMs: Long) {
        val data = resumeData.value ?: return
        val file = resumeFile ?: return
        val keep = data.records.filter { it.sessionElapsedMs <= cutElapsedMs }
        if (keep.isEmpty()) return
        clearPendingResume()
        val sm = SessionStateMachine(data.plan, data.mode)
        sm.restore(data.startedAtMs, keep)
        pendingResumeWriter = runCatching { SessionJournal.rewrite(file, data, keep) }.getOrNull()
        pendingResume = sm
        pendingResumeAtSec.value = (keep.last().sessionElapsedMs / 1000).toInt()
        lastModeLabel = data.modeLabel
        if (SessionJournal.isDemo(file)) {
            // resumed demo workout continues with simulated data
            rowerBle.startDemo()
            if (heartRateEnabled.value && heartRateBle.connectionState.value != ConnectionState.CONNECTED) heartRateBle.startScan()
        } else {
            connectDevices()
        }
        rawLog.clear()
        waitingBaseline = null
        screen.value = Screen.WAITING
    }

    private fun activateResumed() {
        val sm = pendingResume ?: return
        pendingResume = null
        pendingResumeAtSec.value = null
        journal = pendingResumeWriter
        pendingResumeWriter = null
        sm.onRecord = { journal?.record(it) }
        if (!rowerBle.isDemoMode.value) rawLog.attachFile(File(workoutsDir, "n44r_${sm.startedAtMs}_rawble.txt"), append = true)
        rawLog.event("Session fortgesetzt bei ${sm.uiState.value.sessionElapsedSec} s")
        stateMachine = sm
        sessionUi.value = sm.uiState.value
        startWatchdog()
        screen.value = Screen.DASHBOARD
    }

    // ---------- watchdog: connection health, warnings, data-independent UI refresh ----------
    private fun startWatchdog() {
        watchdogJob?.cancel()
        val now = System.currentTimeMillis()
        hrGraceBaseMs = now
        rowerResubscribed = false
        lastRowerReconnectMs = now
        lastHrReconnectMs = now
        listOf(rowerAlert, hrAlert).forEach { it.since = 0; it.count = 0; it.lastSound = 0 }
        watchdogJob = viewModelScope.launch {
            while (isActive) {
                runCatching { watchdogTick() }
                delay(500)
            }
        }
    }

    private fun watchdogTick() {
        val sm = stateMachine ?: return
        val now = System.currentTimeMillis()
        val demo = rowerBle.isDemoMode.value

        // --- rower data stream
        val rowerSilent = now - rowerBle.lastNotificationMs
        val rowerLost = rowerSilent > ROWER_LOST_MS
        if (!demo) {
            if (rowerSilent > ROWER_RESUBSCRIBE_MS && !rowerResubscribed) {
                rowerBle.resubscribe(); rowerResubscribed = true
            }
            if (rowerSilent > ROWER_RECONNECT_MS && now - lastRowerReconnectMs > 15_000) {
                rowerBle.forceReconnect("Watchdog: ${rowerSilent / 1000} s keine Daten")
                lastRowerReconnectMs = now
            }
        }
        if (!rowerLost) rowerResubscribed = false

        // --- heart rate
        val hrExpected = heartRateEnabled.value || demoHrActive()
        val lastValid = maxOf(heartRateBle.lastValidMs, lastDemoHrMs, hrGraceBaseMs)
        val hrSilent = now - lastValid
        val hrLost = hrExpected && hrSilent > HR_LOST_MS
        if (hrLost) sm.setHeartRate(null) // never keep showing/recording a stale value
        else if (!demoHrActive()) heartRateBle.heartRateBpm.value?.let { sm.setHeartRate(it) } // re-apply after a stale phase

        if (heartRateEnabled.value) {
            heartRateBle.ensureConnected()
            if (heartRateBle.connectionState.value == ConnectionState.CONNECTED &&
                now - heartRateBle.lastPacketMs > HR_DEAD_LINK_MS && now - lastHrReconnectMs > 30_000
            ) {
                heartRateBle.forceReconnect("${(now - heartRateBle.lastPacketMs) / 1000} s keine Pakete")
                lastHrReconnectMs = now
            }
        }
        val sendsInvalid = hrLost && heartRateBle.connectionState.value == ConnectionState.CONNECTED &&
            now - heartRateBle.lastPacketMs < 5_000

        // --- alerts (log + sound)
        updateAlert(rowerAlert, rowerLost, now, "Ruderdaten", AlertSounds::rowerLost)
        updateAlert(hrAlert, hrLost, now, "Herzfrequenz", AlertSounds::heartRateLost)

        linkStatus.value = LinkStatus(
            rowerLost = rowerLost, rowerSilentSec = (rowerSilent / 1000).toInt(),
            rowerState = rowerBle.connectionState.value,
            hrExpected = hrExpected, hrLost = hrLost, hrSilentSec = (hrSilent / 1000).toInt(),
            hrState = heartRateBle.connectionState.value, hrSensorSendsInvalid = sendsInvalid
        )
        sm.tick()
        sessionUi.value = sm.uiState.value
    }

    private fun updateAlert(t: AlertTracker, lost: Boolean, now: Long, what: String, sound: () -> Unit) {
        if (lost) {
            if (t.since == 0L) {
                t.since = now; t.count = 0; t.lastSound = 0
                rawLog.event("WARNUNG: $what ausgefallen")
            }
            if (t.count < SOUND_MAX_REPEATS && now - t.lastSound >= SOUND_REPEAT_MS) {
                if (warnSoundsEnabled.value) sound()
                t.lastSound = now; t.count++
            }
        } else if (t.since != 0L) {
            rawLog.event("OK: $what wieder da nach ${(now - t.since) / 1000} s")
            if (warnSoundsEnabled.value) AlertSounds.recovered()
            t.since = 0L
        }
    }

    // ---------- in-session actions ----------
    fun togglePause() {
        val sm = stateMachine ?: return
        sm.togglePause()
        sessionUi.value = sm.uiState.value
    }

    /** Demo only: simulate outages to test warnings/recovery without real hardware. */
    fun simulateRowerOutage() { if (rowerBle.isDemoMode.value) rowerBle.demoDropUntilMs = System.currentTimeMillis() + 12_000 }
    fun simulateHrOutage() { if (rowerBle.isDemoMode.value) demoHrDropUntilMs = System.currentTimeMillis() + 15_000 }

    fun seekDemo(deltaSeconds: Int) {
        val sm = stateMachine ?: return
        if (!rowerBle.isDemoMode.value) return
        val currentSec = sm.uiState.value.sessionElapsedSec
        val targetSec = (currentSec + deltaSeconds).coerceAtLeast(0)

        val previousHrBySecond = sm.liveRecords.value.associate { it.sessionElapsedSec to it.heartRateBpm }

        rowerBle.stopDemoJob()
        val startMs = System.currentTimeMillis() - targetSec * 1000L
        sm.reset(startMs)

        val generator = DemoGenerator()
        val useSimulatedHr = demoHrActive()
        for (tick in 0 until targetSec) {
            val phase = sm.currentPhaseType()
            val sample = generator.next(tick, 1.0, phase).copy(timestampMs = startMs + (tick + 1) * 1000L)
            if (useSimulatedHr) sm.setHeartRate(sample.heartRateBpm) else previousHrBySecond[tick]?.let { sm.setHeartRate(it) }
            sm.onData(sample, forcedDeltaMs = 1000L, publishUpdate = (tick == targetSec - 1))
        }
        lastDemoHrMs = System.currentTimeMillis()
        sessionUi.value = sm.uiState.value
        rowerBle.demoPhaseHint.value = sm.currentPhaseType()
        rowerBle.resumeDemo(generator, fromTick = targetSec)
    }

    fun retryHeartRate() {
        heartRateEnabled.value = true
        if (heartRateBle.connectionState.value == ConnectionState.CONNECTED) heartRateBle.forceReconnect("manuell")
        else heartRateBle.startScan()
    }

    fun finishSession() {
        val sm = stateMachine ?: return
        watchdogJob?.cancel()
        val summary = sm.finish()
        lastSummary.value = summary
        val demo = rowerBle.isDemoMode.value
        journal?.finished(System.currentTimeMillis())
        journal?.close()
        journal = null
        rawLog.event("Session beendet")
        rawLog.detachFile()

        val prefix = if (demo) "DEMO_n44r" else "n44r"
        val stamp = summary.startedAtMs
        val files = mutableListOf<File>()
        runCatching {
            val fitFile = File(workoutsDir, "${prefix}_$stamp.fit")
            FitWriter.write(fitFile, summary)
            files.add(fitFile)
        }.onFailure { rawLog.event("FIT-Export fehlgeschlagen: $it") }
        runCatching {
            val imgFile = File(workoutsDir, "${prefix}_${stamp}_square.png")
            SummaryImageGenerator.generate(getApplication(), summary, lastModeLabel, imgFile, isDemo = demo)
            files.add(imgFile)
        }.onFailure { rawLog.event("Bild-Export fehlgeschlagen: $it") }
        val logFile = File(workoutsDir, "${prefix}_${stamp}_rawble.txt")
        if (!demo && logFile.exists()) files.add(logFile)
        exportedFiles.value = files
        linkStatus.value = LinkStatus()
        screen.value = Screen.SUMMARY
    }

    fun openExport() { screen.value = Screen.EXPORT }

    fun exportedImage(): File? = exportedFiles.value.firstOrNull { it.extension == "png" }

    fun shareUris(context: Context): List<Uri> = exportedFiles.value.map { shareUri(context, it) }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "com.n44r.app.fileprovider", file)

    fun dismissCrashReport() {
        crashReport.value?.let { f -> f.renameTo(File(f.parentFile, "shown_" + f.name)) }
        crashReport.value = null
    }

    fun startOver() {
        stateMachine = null
        sessionUi.value = null
        lastSummary.value = null
        if (rowerBle.isDemoMode.value) rowerBle.disconnect()
        backToStart()
    }

    override fun onCleared() {
        watchdogJob?.cancel()
        journal?.close()
        rawLog.detachFile()
        rowerBle.shutdown()
        heartRateBle.shutdown()
    }
}
