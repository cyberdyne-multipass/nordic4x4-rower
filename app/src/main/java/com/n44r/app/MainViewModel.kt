package com.n44r.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n44r.app.ble.ConnectionState
import com.n44r.app.ble.DemoGenerator
import com.n44r.app.ble.DevicePrefs
import com.n44r.app.ble.HeartRateBleManager
import com.n44r.app.ble.RowerBleManager
import com.n44r.app.data.AppPrefs
import com.n44r.app.export.SummaryImageGenerator
import com.n44r.app.fit.FitWriter
import com.n44r.app.session.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

enum class Screen { PROFILE, START, WAITING, SETUP, DASHBOARD, SUMMARY, EXPORT }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val rowerBle = RowerBleManager(app)
    val heartRateBle = HeartRateBleManager(app)

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

    val sessionUi = MutableStateFlow<SessionUiState?>(null)
    val lastSummary = MutableStateFlow<SessionSummary?>(null)
    val exportedFiles = MutableStateFlow<List<File>>(emptyList())

    // Auto-start: while WAITING we watch for the first real movement (distance or stroke
    // count increasing vs. the first sample) and only then create the session, so "just
    // start rowing" is the whole start gesture - no button press needed at the machine.
    private var waitingBaseline: Pair<Int, Int>? = null

    init {
        viewModelScope.launch {
            rowerBle.rowerData.collect { data ->
                data ?: return@collect
                if (screen.value == Screen.WAITING) {
                    val base = waitingBaseline
                    if (base == null) {
                        waitingBaseline = data.totalDistanceMeters to data.strokeCount
                    } else if (data.totalDistanceMeters > base.first || data.strokeCount > base.second) {
                        startSession()
                    } else return@collect
                }
                if (demoHrActive() && data.heartRateBpm > 0) stateMachine?.setHeartRate(data.heartRateBpm)
                stateMachine?.onData(data)
                sessionUi.value = stateMachine?.uiState?.value
                stateMachine?.let { rowerBle.demoPhaseHint.value = it.currentPhaseType() }
            }
        }
        viewModelScope.launch {
            heartRateBle.heartRateBpm.collect { bpm -> stateMachine?.setHeartRate(bpm) }
        }
        viewModelScope.launch {
            heartRateEnabled.collect { DevicePrefs.saveHeartRateEnabled(app, it) }
        }
        viewModelScope.launch {
            combine(sessionMode, mainMetric, timeTargetSec, distanceTargetM, intervalConfig) { mode, metric, t, d, iv ->
                AppPrefs.Settings(mode, metric, t, d, iv)
            }.collect { AppPrefs.saveSettings(app, it) }
        }
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
    fun backToStart() { screen.value = Screen.START }

    /** Landing-screen "Start": connect (if needed) and wait for the first stroke. */
    fun startFromLanding() {
        if (rowerBle.isDemoMode.value || rowerBle.connectionState.value != ConnectionState.CONNECTED) rowerBle.start()
        if (heartRateEnabled.value && heartRateBle.connectionState.value != ConnectionState.CONNECTED) heartRateBle.startScan()
        rowerBle.clearRawLog()
        waitingBaseline = null
        screen.value = Screen.WAITING
    }

    fun startDemoMode() {
        rowerBle.startDemo()
        if (heartRateEnabled.value && heartRateBle.connectionState.value != ConnectionState.CONNECTED) heartRateBle.startScan()
        rowerBle.clearRawLog()
        waitingBaseline = null
        screen.value = Screen.WAITING
    }

    fun cancelWaiting() {
        if (rowerBle.isDemoMode.value) rowerBle.disconnect()
        screen.value = Screen.START
    }

    private fun startSession() {
        val plan = buildPhasePlan(sessionMode.value, timeTargetSec.value, distanceTargetM.value, intervalConfig.value)
        val sm = SessionStateMachine(plan, sessionMode.value)
        sm.start(System.currentTimeMillis())
        stateMachine = sm
        lastModeLabel = when (sessionMode.value) {
            SessionMode.TIME -> "Zeittraining"
            SessionMode.DISTANCE -> "Distanztraining"
            SessionMode.INTERVAL -> "Intervalltraining"
        }
        screen.value = Screen.DASHBOARD
    }

    fun togglePause() = stateMachine?.togglePause()

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
        sessionUi.value = sm.uiState.value
        rowerBle.demoPhaseHint.value = sm.currentPhaseType()
        rowerBle.resumeDemo(generator, fromTick = targetSec)
    }

    fun retryHeartRate() {
        heartRateEnabled.value = true
        heartRateBle.startScan()
    }

    fun finishSession() {
        val sm = stateMachine ?: return
        val summary = sm.finish(System.currentTimeMillis())
        lastSummary.value = summary
        val demo = rowerBle.isDemoMode.value
        val prefix = if (demo) "DEMO_n44r" else "n44r"
        val dir = File(getApplication<Application>().filesDir, "workouts").apply { mkdirs() }
        val stamp = summary.startedAtMs
        val fitFile = File(dir, "${prefix}_$stamp.fit")
        FitWriter.write(fitFile, summary)
        val files = mutableListOf(fitFile)
        for (fmt in SummaryImageGenerator.CardFormat.values()) {
            val imgFile = File(dir, "${prefix}_${stamp}_${fmt.suffix}.png")
            SummaryImageGenerator.generate(getApplication(), summary, lastModeLabel, imgFile, fmt, isDemo = demo)
            files.add(imgFile)
        }
        val rawLog = rowerBle.getRawLogText()
        if (!demo && rawLog.isNotBlank()) {
            val logFile = File(dir, "${prefix}_${stamp}_rawble.txt")
            logFile.writeText(rawLog)
            files.add(logFile)
        }
        exportedFiles.value = files
        screen.value = Screen.SUMMARY
    }

    fun openExport() { screen.value = Screen.EXPORT }

    /** Image files of the last session, in CardFormat order (square, wide, story). */
    fun exportedImages(): List<File> = exportedFiles.value.filter { it.extension == "png" }

    fun shareUris(context: Context): List<Uri> = exportedFiles.value.map { shareUri(context, it) }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "com.n44r.app.fileprovider", file)

    fun startOver() {
        stateMachine = null
        sessionUi.value = null
        lastSummary.value = null
        if (rowerBle.isDemoMode.value) rowerBle.disconnect()
        screen.value = Screen.START
    }

    override fun onCleared() {
        rowerBle.shutdown()
        heartRateBle.shutdown()
    }
}
