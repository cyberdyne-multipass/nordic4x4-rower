package com.n44r.app.session

data class IntervalConfig(
    val includeWarmup: Boolean = true,
    val warmupSec: Int = 600,
    val workSec: Int = 240,
    val restSec: Int = 180,
    val rounds: Int = 4,
    val includeCooldown: Boolean = true,
    val cooldownSec: Int = 300,
    val workTarget: PhaseTarget = PhaseTarget(),
    val restTarget: PhaseTarget = PhaseTarget()
) {
    companion object {
        /** Norwegian 4x4: 10' warm-up, 4x(4' work + 3' active recovery), 5' cool-down. */
        val NORDIC_4X4 = IntervalConfig(
            includeWarmup = true, warmupSec = 600,
            workSec = 240, restSec = 180, rounds = 4,
            includeCooldown = true, cooldownSec = 300
        )
    }
}

data class PhaseSpec(
    val type: PhaseType,
    val label: String,
    val targetTimeSec: Int? = null,
    val targetDistanceMeters: Int? = null,
    val target: PhaseTarget = PhaseTarget()
)

fun buildPhasePlan(mode: SessionMode, timeTargetSec: Int, distanceTargetM: Int, interval: IntervalConfig): List<PhaseSpec> {
    val plan = mutableListOf<PhaseSpec>()
    when (mode) {
        SessionMode.TIME -> plan.add(PhaseSpec(PhaseType.MAIN, "Training", targetTimeSec = timeTargetSec))
        SessionMode.DISTANCE -> plan.add(PhaseSpec(PhaseType.MAIN, "Training", targetDistanceMeters = distanceTargetM))
        SessionMode.INTERVAL -> {
            if (interval.includeWarmup) plan.add(PhaseSpec(PhaseType.WARMUP, "Warm-up", targetTimeSec = interval.warmupSec))
            for (i in 1..interval.rounds) {
                plan.add(PhaseSpec(PhaseType.WORK, "Intervall $i/${interval.rounds}", targetTimeSec = interval.workSec, target = interval.workTarget))
                // The recovery after the *last* interval and the cool-down are physiologically
                // the same thing (easy rowing to bring HR down) - in the original protocol the
                // 3-min recoveries sit *between* intervals. So after the final work block we go
                // straight into cool-down; only if cool-down is disabled do we keep a last recovery.
                val isLast = i == interval.rounds
                if (!(isLast && interval.includeCooldown)) {
                    plan.add(PhaseSpec(PhaseType.REST, "Erholung $i/${interval.rounds}", targetTimeSec = interval.restSec, target = interval.restTarget))
                }
            }
            if (interval.includeCooldown) plan.add(PhaseSpec(PhaseType.COOLDOWN, "Cool-down", targetTimeSec = interval.cooldownSec))
        }
    }
    plan.add(PhaseSpec(PhaseType.FREE, "Frei"))
    return plan
}
