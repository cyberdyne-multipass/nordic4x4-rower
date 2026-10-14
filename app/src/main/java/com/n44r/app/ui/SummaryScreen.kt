package com.n44r.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.n44r.app.MainViewModel
import com.n44r.app.session.HrAnalysis
import com.n44r.app.session.HrZones

/** Step 1 after a workout: numbers + full training chart. "Weiter" leads to the export screen. */
@Composable
fun SummaryScreen(vm: MainViewModel) {
    val summary by vm.lastSummary.collectAsState()
    val profile by vm.profile.collectAsState()
    val s = summary ?: return

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Training abgeschlossen!", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            StatText("Dauer", formatMmSs(s.totalDurationSec))
            StatText("Distanz", "${s.totalDistanceM} m")
            StatText("Ø Split", formatPace(s.avgPaceSecPer500m))
            StatText("Ø Power", "${s.avgPowerW} W")
            s.maxHeartRateBpm?.let { StatText("Max. HF", "$it bpm") }
        }
        Spacer(Modifier.height(16.dp))

        Text("Trainingsverlauf", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        TrainingChart(
            s.records, s.laps, s.workTarget, s.restTarget, s.totalDurationSec, isLive = false, compact = true,
            hrAxisMin = profile?.restingHr?.coerceIn(30, 120) ?: 40,
            easyZone = profile?.let { HrZones.easyZone(it) },
            hrPanelHeight = 170.dp, pacePanelHeight = 130.dp
        )
        HrMetricsTable(s.hrMetrics)
        Spacer(Modifier.height(24.dp))

        Button(onClick = { vm.openExport() }, modifier = Modifier.height(56.dp).width(220.dp)) { Text("Weiter") }
    }
}

/**
 * Per-interval HR dynamics. Anstieg = seconds until HR covered 90 % of its rise to the
 * interval plateau (shorter = faster cardiovascular response). Erholung = bpm drop in the
 * 60 s after the interval (HRR60, larger = better recovery). "–" = not measurable
 * (missing HR data, interval too short, or session ended < 60 s after it).
 */
@Composable
private fun HrMetricsTable(m: com.n44r.app.session.HrMetrics) {
    if (m.intervals.isEmpty()) return
    Spacer(Modifier.height(16.dp))
    Text("Herzfrequenz-Dynamik", style = MaterialTheme.typography.titleMedium)
    Text(
        "Anstieg: Zeit bis 90 % des HF-Anstiegs erreicht sind · Erholung: HF-Abfall in 60 s nach dem Intervall (HRR60)",
        style = MaterialTheme.typography.labelSmall
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        m.intervals.forEach { iv ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Intervall ${iv.number}", style = MaterialTheme.typography.labelMedium)
                Text("Anstieg ${iv.riseT90Sec?.let { "$it s" } ?: "–"}", style = MaterialTheme.typography.bodyLarge)
                Text("Erholung ${iv.hrr60?.let { HrAnalysis.formatHrr(it) } ?: "–"}", style = MaterialTheme.typography.bodyLarge)
                if (iv.startBpm != null && iv.plateauBpm != null) {
                    Text("${iv.startBpm} → ${iv.plateauBpm} bpm", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (m.avgHrr60 != null || m.avgRiseT90Sec != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Ø", style = MaterialTheme.typography.labelMedium)
                Text("Anstieg ${m.avgRiseT90Sec?.let { "$it s" } ?: "–"}", style = MaterialTheme.typography.bodyLarge)
                Text("Erholung ${m.avgHrr60?.let { HrAnalysis.formatHrr(it) } ?: "–"}", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun StatText(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}
