package com.n44r.app.session

data class RecordPoint(
    val timestampMs: Long,
    val sessionElapsedSec: Int,
    val phaseType: PhaseType,
    val phaseIndex: Int,
    val distanceM: Int,
    val paceSecPer500m: Int?,
    val powerW: Int,
    val strokeRate: Float,
    val heartRateBpm: Int?
)

data class LapSummary(
    val phaseType: PhaseType,
    val label: String,
    val startDistanceM: Int,
    val endDistanceM: Int,
    val durationSec: Int,
    val avgPaceSecPer500m: Int?,
    val avgPowerW: Int,
    val avgStrokeRate: Float
)

data class SessionSummary(
    val mode: SessionMode,
    val startedAtMs: Long,
    val totalDurationSec: Int,
    val totalDistanceM: Int,
    val avgPaceSecPer500m: Int?,
    val avgPowerW: Int,
    val avgStrokeRate: Float,
    val totalEnergyKcal: Int,
    val maxHeartRateBpm: Int?,
    val workTarget: PhaseTarget,
    val restTarget: PhaseTarget,
    val laps: List<LapSummary>,
    val records: List<RecordPoint>
)

data class SessionUiState(
    val phaseType: PhaseType,
    val phaseLabel: String,
    val phaseIndex: Int,
    val totalPhases: Int,
    val phaseRemainingSec: Int?,
    val phaseRemainingMeters: Int?,
    val sessionElapsedSec: Int,
    val totalDistanceM: Int,
    val instantPaceSecPer500m: Int?,
    val splitPaceSecPer500m: Int?,
    val powerW: Int,
    val strokeRate: Float,
    val heartRateBpm: Int?,
    val isManuallyPaused: Boolean,
    val isAutoPaused: Boolean,
    val isFinished: Boolean
)
