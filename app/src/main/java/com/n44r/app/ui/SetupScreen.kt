package com.n44r.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.n44r.app.MainViewModel
import com.n44r.app.session.MainMetric
import com.n44r.app.session.SessionMode

@Composable
fun SetupScreen(vm: MainViewModel) {
    val mode by vm.sessionMode.collectAsState()
    val metric by vm.mainMetric.collectAsState()
    val interval by vm.intervalConfig.collectAsState()
    val timeTarget by vm.timeTargetSec.collectAsState()
    val distTarget by vm.distanceTargetM.collectAsState()
    val hrEnabled by vm.heartRateEnabled.collectAsState()
    val profile by vm.profile.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Einstellungen", style = MaterialTheme.typography.headlineMedium)
        Text("Werden automatisch gespeichert", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(20.dp))

        Text("Modus", style = MaterialTheme.typography.titleMedium)
        SegmentedRow(SessionMode.values().toList(), mode, { vm.sessionMode.value = it }) {
            when (it) { SessionMode.TIME -> "Zeit"; SessionMode.DISTANCE -> "Distanz"; SessionMode.INTERVAL -> "Intervall" }
        }
        Spacer(Modifier.height(16.dp))

        when (mode) {
            SessionMode.TIME -> StepperRow("Zieldauer", timeTarget / 60, "min", 1) { vm.timeTargetSec.value = (it * 60).coerceAtLeast(60) }
            SessionMode.DISTANCE -> StepperRow("Zieldistanz", distTarget, "m", 100) { vm.distanceTargetM.value = it.coerceAtLeast(100) }
            SessionMode.INTERVAL -> IntervalSetup(
                interval,
                onApplyProfileZones = if (profile != null) ({ vm.applyProfileHrZones() }) else null
            ) { vm.intervalConfig.value = it }
        }
        Spacer(Modifier.height(16.dp))

        Text("Hauptmetrik", style = MaterialTheme.typography.titleMedium)
        SegmentedRow(MainMetric.values().toList(), metric, { vm.mainMetric.value = it }) { metricLabel(it) }
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Herzfrequenz (iPhone-Bridge)")
            Switch(checked = hrEnabled, onCheckedChange = { vm.heartRateEnabled.value = it })
        }
        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.backToStart() }) { Text("Fertig") }
            OutlinedButton(onClick = { vm.openProfile() }) { Text("Profil bearbeiten") }
        }
    }
}

fun metricLabel(m: MainMetric) = when (m) {
    MainMetric.DISTANCE -> "Distanz"; MainMetric.TIME -> "Zeit"; MainMetric.PACE_500M -> "Pace"
    MainMetric.POWER -> "Power"; MainMetric.STROKE_RATE -> "Schlagzahl"
}
