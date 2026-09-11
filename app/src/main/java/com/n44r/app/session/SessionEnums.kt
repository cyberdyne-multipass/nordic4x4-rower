package com.n44r.app.session

enum class SessionMode { TIME, DISTANCE, INTERVAL }

enum class MainMetric { DISTANCE, TIME, PACE_500M, POWER, STROKE_RATE }

enum class PhaseType { WARMUP, MAIN, WORK, REST, COOLDOWN, FREE }

enum class TargetType { NONE, PACE, HEART_RATE }

data class PhaseTarget(
    val type: TargetType = TargetType.NONE,
    val paceTargetMinSec: Int? = null,
    val paceTargetMaxSec: Int? = null,
    val hrZoneLowBpm: Int? = null,
    val hrZoneHighBpm: Int? = null
)
