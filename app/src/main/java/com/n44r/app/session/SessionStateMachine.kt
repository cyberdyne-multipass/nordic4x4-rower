package com.n44r.app.session

import com.n44r.app.ble.RowerData
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Core orchestrator: advances through the phase plan, applies manual + auto pause, tracks
 * per-phase split pace, and records every unpaused sample for the FIT/summary export.
 * The last phase in every plan is FREE (no target) - it runs until finish() is called.
 *
 * Timing: session time advances by the real wall-clock gap between rower samples. Robustness
 * rules (2026-10 rework):
 *  - [tick] is called twice a second by the ViewModel, independent of BLE data, so pause
 *    toggles and connection warnings show up immediately even when no data arrives.
 *  - A data gap > [GAP_THRESHOLD_MS] (lost connection) is credited as training time only if
 *    the rower distance advanced during the gap (= the user kept rowing), capped at 5 min.
 *  - Split pace runs on session time (not wall time), so pauses no longer distort it.
 *  - [restore] rebuilds the complete state from journaled records (crash recovery / rewind).
 *
 * onData's forcedDeltaMs/publishUpdate params exist for demo seek/replay.
 */
class SessionStateMachine(val plan: List<PhaseSpec>, val mode: SessionMode) {

    companion object {
        private const val GAP_THRESHOLD_MS = 3_000L
        private const val MAX_GAP_CREDIT_MS = 300_000L
    }

    private var pauseDetector = PauseDetector()
    private val splitTracker = SplitTracker()
    private val records = mutableListOf<RecordPoint>()
    private val laps = mutableListOf<LapSummary>()

    var startedAtMs: Long = 0L
        private set
    private var currentPhaseIndex = 0
    private var phaseStartDistanceM = 0
    private var phaseElapsedMs = 0L
    private var sessionElapsedMs = 0L
    private var lastSampleTimeMs = 0L
    private var manuallyPaused = false
    private var lastAutoPaused = false
    private var finished = false

    private var lastHeartRate: Int? = null
    private var lastDistance = 0
    private var lastRawDistance = 0
    private var lastPower = 0
    private var lastStrokeRate = 0f
    private var lastPace: Int? = null
    private var lastSplitPace: Int? = null

    // The S4 monitor reports distance since *its* last reset, not since our session started.
    // Zeroed on the first sample. After a restore it is re-derived so distance continues.
    private var distanceOffset = 0
    private var offsetSet = false
    private var resumePending = false

    /** Called for every new record (journal). Not called during restore/replay of old records. */
    var onRecord: ((RecordPoint) -> Unit)? = null

    val uiState = MutableStateFlow(initialUiState())
    val liveRecords = MutableStateFlow<List<RecordPoint>>(emptyList())
    val liveLaps = MutableStateFlow<List<LapSummary>>(emptyList())
    val workTarget: PhaseTarget = plan.firstOrNull { it.type == PhaseType.WORK }?.target ?: PhaseTarget()
    val restTarget: PhaseTarget = plan.firstOrNull { it.type == PhaseType.REST }?.target ?: PhaseTarget()

    fun start(nowMs: Long) {
        startedAtMs = nowMs
        lastSampleTimeMs = nowMs
        splitTracker.reset(0, 0L)
    }

    /** Full reset back to the initial state, e.g. before a demo seek-replay. */
    fun reset(nowMs: Long) {
        startedAtMs = nowMs
        currentPhaseIndex = 0
        phaseStartDistanceM = 0
        phaseElapsedMs = 0
        sessionElapsedMs = 0
        lastSampleTimeMs = nowMs
        manuallyPaused = false
        lastAutoPaused = false
        finished = false
        lastHeartRate = null
        lastDistance = 0
        lastRawDistance = 0
        lastPower = 0
        lastStrokeRate = 0f
        lastPace = null
        lastSplitPace = null
        distanceOffset = 0
        offsetSet = false
        resumePending = false
        records.clear()
        laps.clear()
        liveRecords.value = emptyList()
        liveLaps.value = emptyList()
        pauseDetector.reset()
        splitTracker.reset(0, 0L)
        uiState.value = initialUiState()
    }

    /**
     * Rebuilds the session from journaled records (in order). Phases/laps are re-derived by
     * replaying through the same logic. The next live sample continues seamlessly: its
     * distance is mapped onto the restored distance and the outage is not credited as time.
     */
    fun restore(originalStartMs: Long, saved: List<RecordPoint>) {
        reset(originalStartMs)
        val cb = onRecord
        onRecord = null
        offsetSet = true // records already carry session-relative distances
        var prevElapsed = 0L
        saved.forEachIndexed { i, r ->
            lastHeartRate = r.heartRateBpm
            val sample = RowerData(
                strokeRatePerMin = r.strokeRate, strokeCount = i + 1, totalDistanceMeters = r.distanceM,
                instantaneousPaceSecPer500m = r.paceSecPer500m, instantaneousPowerWatts = r.powerW,
                totalEnergyKcal = 0, energyPerHourKcal = 0, energyPerMinuteKcal = 0,
                heartRateBpm = 0, elapsedTimeSec = 0, timestampMs = r.timestampMs
            )
            onData(sample, forcedDeltaMs = (r.sessionElapsedMs - prevElapsed).coerceAtLeast(0), publishUpdate = false)
            prevElapsed = r.sessionElapsedMs
        }
        startedAtMs = originalStartMs
        onRecord = cb
        lastHeartRate = null
        resumePending = true
        pauseDetector.reset()
        liveRecords.value = records.toList()
        liveLaps.value = laps.toList()
        publish(false, false)
    }

    fun togglePause() {
        if (finished) return
        manuallyPaused = !manuallyPaused
        publish(manuallyPaused || lastAutoPaused, lastAutoPaused)
    }

    fun currentPhaseType(): PhaseType = currentPhase().type

    fun setHeartRate(bpm: Int?) {
        lastHeartRate = bpm?.takeIf { HrAnalysis.isValid(it) }
    }

    /** Data-independent UI refresh (see class doc). */
    fun tick() {
        if (finished) return
        publish(manuallyPaused || lastAutoPaused, lastAutoPaused)
    }

    fun onData(data: RowerData, forcedDeltaMs: Long? = null, publishUpdate: Boolean = true) {
        if (finished) return
        val now = data.timestampMs
        if (resumePending) {
            // first live sample after a restore: continue distance from where we left off
            distanceOffset = data.totalDistanceMeters - lastDistance
            lastRawDistance = data.totalDistanceMeters
            lastSampleTimeMs = now
            resumePending = false
        }
        if (!offsetSet) { distanceOffset = data.totalDistanceMeters; offsetSet = true; lastRawDistance = data.totalDistanceMeters }
        if (forcedDeltaMs == null && data.totalDistanceMeters < lastRawDistance - 5) {
            // monitor counter was reset (e.g. S4 rebooted during an outage) -> keep counting on
            distanceOffset = data.totalDistanceMeters - lastDistance
            lastRawDistance = data.totalDistanceMeters
        }
        val dist = (data.totalDistanceMeters - distanceOffset).coerceAtLeast(0)
        val rowedDuringGap = data.totalDistanceMeters > lastRawDistance
        lastRawDistance = data.totalDistanceMeters

        val autoPaused = pauseDetector.update(dist, data.strokeCount, now)
        lastAutoPaused = autoPaused
        val effectivePaused = manuallyPaused || autoPaused

        if (!effectivePaused) {
            val deltaMs = forcedDeltaMs ?: run {
                val gap = (now - lastSampleTimeMs).coerceAtLeast(0)
                when {
                    gap <= GAP_THRESHOLD_MS -> gap
                    rowedDuringGap -> gap.coerceAtMost(MAX_GAP_CREDIT_MS) // connection lost, kept rowing
                    else -> 1000L
                }
            }
            phaseElapsedMs += deltaMs
            sessionElapsedMs += deltaMs

            lastDistance = dist
            lastPower = data.instantaneousPowerWatts
            lastStrokeRate = data.strokeRatePerMin
            lastPace = data.instantaneousPaceSecPer500m
            lastSplitPace = splitTracker.currentSplitPaceSecPer500m(dist, sessionElapsedMs)

            val rec = RecordPoint(
                timestampMs = now,
                sessionElapsedSec = (sessionElapsedMs / 1000).toInt(),
                phaseType = currentPhase().type,
                phaseIndex = currentPhaseIndex,
                distanceM = dist,
                paceSecPer500m = data.instantaneousPaceSecPer500m,
                powerW = data.instantaneousPowerWatts,
                strokeRate = data.strokeRatePerMin,
                heartRateBpm = lastHeartRate,
                sessionElapsedMs = sessionElapsedMs
            )
            records.add(rec)
            onRecord?.invoke(rec)
            if (publishUpdate) liveRecords.value = records.toList()
            checkPhaseCompletion(dist)
        }
        lastSampleTimeMs = now
        if (publishUpdate) publish(effectivePaused, autoPaused)
    }

    private fun currentPhase() = plan[currentPhaseIndex]

    private fun lapFor(phase: PhaseSpec, endDist: Int): LapSummary {
        val recs = records.filter { it.phaseIndex == currentPhaseIndex }
        return LapSummary(
            phaseType = phase.type,
            label = phase.label,
            startDistanceM = phaseStartDistanceM,
            endDistanceM = endDist,
            durationSec = (phaseElapsedMs / 1000).toInt(),
            avgPaceSecPer500m = splitTracker.currentSplitPaceSecPer500m(endDist, sessionElapsedMs),
            avgPowerW = if (recs.isNotEmpty()) recs.map { it.powerW }.average().toInt() else lastPower,
            avgStrokeRate = if (recs.isNotEmpty()) recs.map { it.strokeRate }.average().toFloat() else lastStrokeRate
        )
    }

    private fun checkPhaseCompletion(dist: Int) {
        val phase = currentPhase()
        val timeUp = phase.targetTimeSec != null && phaseElapsedMs / 1000 >= phase.targetTimeSec
        val distanceUp = phase.targetDistanceMeters != null &&
            (dist - phaseStartDistanceM) >= phase.targetDistanceMeters
        if (!timeUp && !distanceUp) return
        if (phase.type == PhaseType.FREE) return // never auto-advances

        laps.add(lapFor(phase, dist))
        if (currentPhaseIndex < plan.lastIndex) currentPhaseIndex++
        liveLaps.value = laps.toList()
        phaseStartDistanceM = dist
        phaseElapsedMs = 0
        splitTracker.reset(dist, sessionElapsedMs)
    }

    fun finish(): SessionSummary {
        finished = true
        laps.add(lapFor(currentPhase(), lastDistance))
        val avgPower = if (records.isNotEmpty()) records.map { it.powerW }.average().toInt() else 0
        val avgStroke = if (records.isNotEmpty()) records.map { it.strokeRate }.average().toFloat() else 0f
        val avgPace = if (lastDistance > 0) ((sessionElapsedMs / 1000.0) / lastDistance * 500).toInt() else null
        return SessionSummary(
            mode = mode,
            startedAtMs = startedAtMs,
            totalDurationSec = (sessionElapsedMs / 1000).toInt(),
            totalDistanceM = lastDistance,
            avgPaceSecPer500m = avgPace,
            avgPowerW = avgPower,
            avgStrokeRate = avgStroke,
            totalEnergyKcal = 0,
            maxHeartRateBpm = HrAnalysis.maxHr(records),
            workTarget = workTarget,
            restTarget = restTarget,
            laps = laps.toList(),
            records = records.toList(),
            hrMetrics = HrAnalysis.computeMetrics(records)
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
