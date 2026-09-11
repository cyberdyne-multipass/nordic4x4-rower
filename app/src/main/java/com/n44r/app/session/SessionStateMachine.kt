package com.n44r.app.session

import com.n44r.app.ble.RowerData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Core orchestrator: advances through the phase plan, applies manual + auto pause
 * (both freeze phase/session timers and distance accumulation identically), tracks
 * per-phase split pace, and records every unpaused sample for the FIT/summary export.
 * The last phase in every plan is FREE (no target) - it runs until finish() is called,
 * so "keep rowing after cooldown" just means staying in that phase.
 *
 * onData's forcedDeltaMs/publishUpdate params exist for demo seek/replay: normally timing
 * comes from real wall-clock gaps between samples (deliberately, to survive device timer
 * bugs), but a fast-forward/rewind replay needs to feed many synthetic samples with no real
 * delay between them - forcedDeltaMs lets the caller say "pretend N ms passed" instead, and
 * publishUpdate=false skips the (relatively expensive) UI-state publish on every one of those
 * replayed samples, only doing it once at the end.
 */
class SessionStateMachine(private val plan: List<PhaseSpec>, private val mode: SessionMode) {

    private var pauseDetector = PauseDetector()
    private val splitTracker = SplitTracker()
    private val records = mutableListOf<RecordPoint>()
    private val laps = mutableListOf<LapSummary>()

    private var currentPhaseIndex = 0
    private var phaseStartDistanceM = 0
    private var phaseElapsedMs = 0L
    private var sessionElapsedMs = 0L
    private var lastSampleTimeMs = 0L
    private var manuallyPaused = false
    private var finished = false
    private var maxHeartRate = 0

    private var lastHeartRate: Int? = null
    private var lastDistance = 0
    private var lastPower = 0
    private var lastStrokeRate = 0f
    private var lastPace: Int? = null
    private var lastSplitPace: Int? = null

    // The S4 monitor reports distance since *its* last reset, not since our session started -
    // with auto-start that may be non-zero. Zero it on the first sample we see.
    private var distanceOffset = 0
    private var offsetSet = false

    val uiState = MutableStateFlow(initialUiState())
    val liveRecords = MutableStateFlow<List<RecordPoint>>(emptyList())
    val liveLaps = MutableStateFlow<List<LapSummary>>(emptyList())
    val workTarget: PhaseTarget = plan.firstOrNull { it.type == PhaseType.WORK }?.target ?: PhaseTarget()
    val restTarget: PhaseTarget = plan.firstOrNull { it.type == PhaseType.REST }?.target ?: PhaseTarget()

    fun start(nowMs: Long) {
        lastSampleTimeMs = nowMs
        splitTracker.reset(0, nowMs)
    }

    /** Full reset back to the initial state, e.g. before a demo seek-replay. */
    fun reset(nowMs: Long) {
        currentPhaseIndex = 0
        phaseStartDistanceM = 0
        phaseElapsedMs = 0
        sessionElapsedMs = 0
        lastSampleTimeMs = nowMs
        manuallyPaused = false
        finished = false
        maxHeartRate = 0
        lastHeartRate = null
        lastDistance = 0
        lastPower = 0
        lastStrokeRate = 0f
        lastPace = null
        lastSplitPace = null
        distanceOffset = 0
        offsetSet = false
        records.clear()
        laps.clear()
        liveRecords.value = emptyList()
        liveLaps.value = emptyList()
        pauseDetector.reset()
        splitTracker.reset(0, nowMs)
        uiState.value = initialUiState()
    }

    fun togglePause() {
        if (finished) return
        manuallyPaused = !manuallyPaused
    }

    /** Always-accurate phase type, independent of publishUpdate - used to feed the phase-aware
     *  demo generator, since uiState itself may be stale mid-replay (publish skipped for speed). */
    fun currentPhaseType(): PhaseType = currentPhase().type

    fun setHeartRate(bpm: Int?) {
        lastHeartRate = bpm
        if (bpm != null && bpm > maxHeartRate) maxHeartRate = bpm
    }

    fun onData(data: RowerData, forcedDeltaMs: Long? = null, publishUpdate: Boolean = true) {
        if (finished) return
        val now = data.timestampMs
        if (!offsetSet) { distanceOffset = data.totalDistanceMeters; offsetSet = true }
        val dist = (data.totalDistanceMeters - distanceOffset).coerceAtLeast(0)

        val autoPaused = pauseDetector.update(dist, data.strokeCount, now)
        val effectivePaused = manuallyPaused || autoPaused

        if (!effectivePaused) {
            lastDistance = dist
            lastPower = data.instantaneousPowerWatts
            lastStrokeRate = data.strokeRatePerMin
            lastPace = data.instantaneousPaceSecPer500m
            lastSplitPace = splitTracker.currentSplitPaceSecPer500m(dist, now)

            val deltaMs = forcedDeltaMs ?: (now - lastSampleTimeMs).coerceIn(0, 10_000)
            phaseElapsedMs += deltaMs
            sessionElapsedMs += deltaMs
            records.add(
                RecordPoint(
                    timestampMs = now,
                    sessionElapsedSec = (sessionElapsedMs / 1000).toInt(),
                    phaseType = currentPhase().type,
                    phaseIndex = currentPhaseIndex,
                    distanceM = dist,
                    paceSecPer500m = data.instantaneousPaceSecPer500m,
                    powerW = data.instantaneousPowerWatts,
                    strokeRate = data.strokeRatePerMin,
                    heartRateBpm = lastHeartRate
                )
            )
            if (publishUpdate) liveRecords.value = records.toList()
            checkPhaseCompletion(dist, data, now)
        }
        lastSampleTimeMs = now
        if (publishUpdate) publish(effectivePaused, autoPaused)
    }

    private fun currentPhase() = plan[currentPhaseIndex]

    private fun checkPhaseCompletion(dist: Int, data: RowerData, nowMs: Long) {
        val phase = currentPhase()
        val timeUp = phase.targetTimeSec != null && phaseElapsedMs / 1000 >= phase.targetTimeSec
        val distanceUp = phase.targetDistanceMeters != null &&
            (dist - phaseStartDistanceM) >= phase.targetDistanceMeters
        if (!timeUp && !distanceUp) return
        if (phase.type == PhaseType.FREE) return // never auto-advances

        laps.add(
            LapSummary(
                phaseType = phase.type,
                label = phase.label,
                startDistanceM = phaseStartDistanceM,
                endDistanceM = dist,
                durationSec = (phaseElapsedMs / 1000).toInt(),
                avgPaceSecPer500m = splitTracker.currentSplitPaceSecPer500m(dist, nowMs),
                avgPowerW = data.instantaneousPowerWatts,
                avgStrokeRate = data.strokeRatePerMin
            )
        )
        if (currentPhaseIndex < plan.lastIndex) currentPhaseIndex++
        liveLaps.value = laps.toList()
        phaseStartDistanceM = dist
        phaseElapsedMs = 0
        splitTracker.reset(dist, nowMs)
    }

    fun finish(nowMs: Long): SessionSummary {
        finished = true
        val phase = currentPhase()
        laps.add(
            LapSummary(
                phaseType = phase.type, label = phase.label,
                startDistanceM = phaseStartDistanceM, endDistanceM = lastDistance,
                durationSec = (phaseElapsedMs / 1000).toInt(),
                avgPaceSecPer500m = splitTracker.currentSplitPaceSecPer500m(lastDistance, nowMs),
                avgPowerW = lastPower, avgStrokeRate = lastStrokeRate
            )
        )
        val avgPower = if (records.isNotEmpty()) records.map { it.powerW }.average().toInt() else 0
        val avgStroke = if (records.isNotEmpty()) records.map { it.strokeRate }.average().toFloat() else 0f
        val validPaces = records.mapNotNull { it.paceSecPer500m }
        val avgPace = if (validPaces.isNotEmpty()) validPaces.average().toInt() else null
        return SessionSummary(
            mode = mode,
            startedAtMs = records.firstOrNull()?.timestampMs ?: nowMs,
            totalDurationSec = (sessionElapsedMs / 1000).toInt(),
            totalDistanceM = lastDistance,
            avgPaceSecPer500m = avgPace,
            avgPowerW = avgPower,
            avgStrokeRate = avgStroke,
            totalEnergyKcal = 0,
            maxHeartRateBpm = if (maxHeartRate > 0) maxHeartRate else null,
            workTarget = workTarget,
            restTarget = restTarget,
            laps = laps.toList(),
            records = records.toList()
        )
    }

    private fun publish(manualPause: Boolean, autoPause: Boolean) {
        val phase = currentPhase()
        val remainingSec = phase.targetTimeSec?.let { (it - phaseElapsedMs / 1000).toInt().coerceAtLeast(0) }
        val remainingM = phase.targetDistanceMeters?.let { (it - (lastDistance - phaseStartDistanceM)).coerceAtLeast(0) }
        uiState.value = SessionUiState(
            phaseType = phase.type,
            phaseLabel = phase.label,
            phaseIndex = currentPhaseIndex,
            totalPhases = plan.size,
            phaseRemainingSec = remainingSec,
            phaseRemainingMeters = remainingM,
            sessionElapsedSec = (sessionElapsedMs / 1000).toInt(),
            totalDistanceM = lastDistance,
            instantPaceSecPer500m = lastPace,
            splitPaceSecPer500m = lastSplitPace,
            powerW = lastPower,
            strokeRate = lastStrokeRate,
            heartRateBpm = lastHeartRate,
            isManuallyPaused = manualPause && !autoPause,
            isAutoPaused = autoPause,
            isFinished = finished
        )
    }

    private fun initialUiState() = SessionUiState(
        phaseType = plan.first().type, phaseLabel = plan.first().label,
        phaseIndex = 0, totalPhases = plan.size,
        phaseRemainingSec = plan.first().targetTimeSec, phaseRemainingMeters = plan.first().targetDistanceMeters,
        sessionElapsedSec = 0, totalDistanceM = 0, instantPaceSecPer500m = null, splitPaceSecPer500m = null,
        powerW = 0, strokeRate = 0f, heartRateBpm = null,
        isManuallyPaused = false, isAutoPaused = false, isFinished = false
    )
}
