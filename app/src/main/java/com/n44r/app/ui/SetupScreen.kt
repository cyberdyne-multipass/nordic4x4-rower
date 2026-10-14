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
    val warnSounds by vm.warnSoundsEnabled.collectAsState()
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
            Text("Herzfrequenz (Brustgurt oder iPhone-Bridge)")
            Switch(checked = hrEnabled, onCheckedChange = { vm.heartRateEnabled.value = it })
        }
        if (hrEnabled) HrSensorSection(vm)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Warntöne bei Verbindungsproblemen")
            Switch(checked = warnSounds, onCheckedChange = { vm.warnSoundsEnabled.value = it })
        }
        Text("HF: zwei kurze hohe Pieptöne · Rudergerät: zwei lange tiefe Töne · wieder da: kurzer Aufwärts-Ton",
            style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.backToStart() }) { Text("Fertig") }
            OutlinedButton(onClick = { vm.openProfile() }) { Text("Profil bearbeiten") }
        }
    }
}

/**
 * Preferred HR sensor. With a preferred sensor (e.g. Garmin HRM 600) the app waits a few
 * seconds for it and only then takes any other HR source (e.g. the iPhone bridge) as backup.
 */
@Composable
private fun HrSensorSection(vm: MainViewModel) {
    val preferred by vm.preferredHr.collectAsState()
    val found by vm.heartRateBle.discovered.collectAsState()
    val searching by vm.heartRateBle.discovering.collectAsState()
    val connectedName by vm.heartRateBle.connectedName.collectAsState()
    val rr by vm.heartRateBle.rrIntervalsSeen.collectAsState()
    DisposableEffect(Unit) { onDispose { vm.stopHrDiscovery() } }

    Card(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Herzfrequenz-Sensor", style = MaterialTheme.typography.titleSmall)
            Text(
                "Bevorzugt: " + (preferred?.second?.ifBlank { preferred?.first } ?: "automatisch (erster gefundener Sensor)"),
                style = MaterialTheme.typography.bodyMedium
            )
            connectedName?.let {
                Text("Verbunden: $it" + if (rr) " · sendet RR-Intervalle" else "", style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.startHrDiscovery() }, enabled = !searching) {
                    Text(if (searching) "Suche…" else "Sensor suchen")
                }
                if (preferred != null) OutlinedButton(onClick = { vm.chooseHrSensor(null) }) { Text("Automatisch") }
                if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (searching || found.isNotEmpty()) {
                Text("Gurt anlegen (Elektroden anfeuchten) bzw. Bridge-App starten, dann antippen:",
                    style = MaterialTheme.typography.labelSmall)
            }
            found.forEach { d ->
                val isPref = preferred?.first == d.address
                OutlinedButton(onClick = { vm.chooseHrSensor(d) }, modifier = Modifier.fillMaxWidth()) {
                    Text((if (isPref) "✓ " else "") + d.name + if (d.rssi != 0) "  (${d.rssi} dBm)" else "  (verbunden)")
                }
            }
        }
    }
}

fun metricLabel(m: MainMetric) = when (m) {
    MainMetric.DISTANCE -> "Distanz"; MainMetric.TIME -> "Zeit"; MainMetric.PACE_500M -> "Pace"
    MainMetric.POWER -> "Power"; MainMetric.STROKE_RATE -> "Schlagzahl"
}
