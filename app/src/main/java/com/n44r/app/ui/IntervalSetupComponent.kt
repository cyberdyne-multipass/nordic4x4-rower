package com.n44r.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.n44r.app.session.IntervalConfig
import com.n44r.app.session.PhaseTarget
import com.n44r.app.session.TargetType

/** Applies the type selection AND commits sensible default bounds immediately, so a target
 *  is fully usable right after picking its type - previously the steppers only *displayed*
 *  a fallback default without saving it until manually touched, which looked configured but
 *  wasn't. */
private fun applyTargetType(current: PhaseTarget, newType: TargetType, paceDefaults: Pair<Int, Int>, hrDefaults: Pair<Int, Int>): PhaseTarget =
    when (newType) {
        TargetType.PACE -> current.copy(
            type = newType,
            paceTargetMinSec = current.paceTargetMinSec ?: paceDefaults.first,
            paceTargetMaxSec = current.paceTargetMaxSec ?: paceDefaults.second
        )
        TargetType.HEART_RATE -> current.copy(
            type = newType,
            hrZoneLowBpm = current.hrZoneLowBpm ?: hrDefaults.first,
            hrZoneHighBpm = current.hrZoneHighBpm ?: hrDefaults.second
        )
        TargetType.NONE -> current.copy(type = newType)
    }

@Composable
fun IntervalSetup(config: IntervalConfig, onApplyProfileZones: (() -> Unit)? = null, onChange: (IntervalConfig) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onChange(IntervalConfig.NORDIC_4X4.copy(workTarget = config.workTarget, restTarget = config.restTarget)) }) { Text("Preset: Nordic 4x4") }
            if (onApplyProfileZones != null) {
                OutlinedButton(onClick = onApplyProfileZones) { Text("Pulszonen aus Profil") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Warm-up")
            Switch(checked = config.includeWarmup, onCheckedChange = { onChange(config.copy(includeWarmup = it)) })
        }
        if (config.includeWarmup) {
            StepperRow("Warm-up", config.warmupSec / 60, "min", 1) { onChange(config.copy(warmupSec = it * 60)) }
        }
        StepperRow("Work", config.workSec / 60, "min", 1) { onChange(config.copy(workSec = it * 60)) }
        StepperRow("Erholung", config.restSec / 60, "min", 1) { onChange(config.copy(restSec = it * 60)) }
        StepperRow("Runden", config.rounds, "x", 1) { onChange(config.copy(rounds = it.coerceAtLeast(1))) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cool-down")
            Switch(checked = config.includeCooldown, onCheckedChange = { onChange(config.copy(includeCooldown = it)) })
        }
        if (config.includeCooldown) {
            StepperRow("Cool-down", config.cooldownSec / 60, "min", 1) { onChange(config.copy(cooldownSec = it * 60)) }
        }

        Spacer(Modifier.height(16.dp))
        Text("Sollbereich (Work-Intervalle)", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        SegmentedRow(TargetType.values().toList(), config.workTarget.type, { newType ->
            onChange(config.copy(workTarget = applyTargetType(config.workTarget, newType, 120 to 130, 150 to 170)))
        }) { targetTypeLabel(it) }

        when (config.workTarget.type) {
            TargetType.PACE -> {
                val min = config.workTarget.paceTargetMinSec ?: 120
                val max = config.workTarget.paceTargetMaxSec ?: 130
                PaceStepperRow("Schnellste", min, 5) { onChange(config.copy(workTarget = config.workTarget.copy(paceTargetMinSec = it, paceTargetMaxSec = max))) }
                PaceStepperRow("Langsamste", max, 5) { onChange(config.copy(workTarget = config.workTarget.copy(paceTargetMinSec = min, paceTargetMaxSec = it))) }
            }
            TargetType.HEART_RATE -> {
                val low = config.workTarget.hrZoneLowBpm ?: 150
                val high = config.workTarget.hrZoneHighBpm ?: 170
                StepperRow("Untere Grenze", low, "bpm", 5) { onChange(config.copy(workTarget = config.workTarget.copy(hrZoneLowBpm = it, hrZoneHighBpm = high))) }
                StepperRow("Obere Grenze", high, "bpm", 5) { onChange(config.copy(workTarget = config.workTarget.copy(hrZoneLowBpm = low, hrZoneHighBpm = it))) }
            }
            TargetType.NONE -> {}
        }

        Spacer(Modifier.height(16.dp))
        Text("Sollbereich (Erholung)", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        SegmentedRow(TargetType.values().toList(), config.restTarget.type, { newType ->
            onChange(config.copy(restTarget = applyTargetType(config.restTarget, newType, 170 to 180, 120 to 140)))
        }) { targetTypeLabel(it) }

        when (config.restTarget.type) {
            TargetType.PACE -> {
                val min = config.restTarget.paceTargetMinSec ?: 170
                val max = config.restTarget.paceTargetMaxSec ?: 180
                PaceStepperRow("Schnellste", min, 5) { onChange(config.copy(restTarget = config.restTarget.copy(paceTargetMinSec = it, paceTargetMaxSec = max))) }
                PaceStepperRow("Langsamste", max, 5) { onChange(config.copy(restTarget = config.restTarget.copy(paceTargetMinSec = min, paceTargetMaxSec = it))) }
            }
            TargetType.HEART_RATE -> {
                val low = config.restTarget.hrZoneLowBpm ?: 120
                val high = config.restTarget.hrZoneHighBpm ?: 140
                StepperRow("Untere Grenze", low, "bpm", 5) { onChange(config.copy(restTarget = config.restTarget.copy(hrZoneLowBpm = it, hrZoneHighBpm = high))) }
                StepperRow("Obere Grenze", high, "bpm", 5) { onChange(config.copy(restTarget = config.restTarget.copy(hrZoneLowBpm = low, hrZoneHighBpm = it))) }
            }
            TargetType.NONE -> {}
        }
    }
}
