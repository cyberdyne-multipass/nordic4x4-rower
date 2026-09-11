package com.n44r.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n44r.app.BuildConfig
import com.n44r.app.MainViewModel
import com.n44r.app.R
import com.n44r.app.session.SessionMode
import com.n44r.app.session.TargetType

/** Landing screen: one big Start with the last-used settings, everything else secondary. */
@Composable
fun StartScreen(vm: MainViewModel) {
    val profile by vm.profile.collectAsState()
    val mode by vm.sessionMode.collectAsState()
    val interval by vm.intervalConfig.collectAsState()
    val timeTarget by vm.timeTargetSec.collectAsState()
    val distTarget by vm.distanceTargetM.collectAsState()
    val metric by vm.mainMetric.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(painterResource(R.drawable.brand_icon), contentDescription = "Nordic4x4 Rower", modifier = Modifier.height(150.dp))
        Spacer(Modifier.height(8.dp))
        Text("Nordic4x4 Rower", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        if (profile?.name?.isNotBlank() == true) {
            Text("Hallo ${profile!!.name}", fontSize = 20.sp)
        }
        Spacer(Modifier.height(20.dp))

        val summary = when (mode) {
            SessionMode.TIME -> "Zeittraining · ${timeTarget / 60} min"
            SessionMode.DISTANCE -> "Distanztraining · $distTarget m"
            SessionMode.INTERVAL -> buildString {
                append("Intervall · ${interval.rounds}× ${interval.workSec / 60} min / ${interval.restSec / 60} min")
                if (interval.includeWarmup) append(" · Warm-up ${interval.warmupSec / 60} min")
                if (interval.includeCooldown) append(" · Cool-down ${interval.cooldownSec / 60} min")
            }
        }
        Text(summary, style = MaterialTheme.typography.bodyLarge)
        val target = interval.workTarget
        val targetText = when (target.type) {
            TargetType.HEART_RATE -> "Ziel Belastung: ${target.hrZoneLowBpm}–${target.hrZoneHighBpm} bpm"
            TargetType.PACE -> "Ziel Belastung: ${formatPace(target.paceTargetMinSec)}–${formatPace(target.paceTargetMaxSec)} /500m"
            TargetType.NONE -> "Kein Sollbereich"
        }
        if (mode == SessionMode.INTERVAL) Text(targetText, style = MaterialTheme.typography.bodyMedium)
        Text("Hauptmetrik: ${metricLabel(metric)}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(36.dp))

        Button(onClick = { vm.startFromLanding() }, modifier = Modifier.height(72.dp).width(280.dp)) {
            Text("Start", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.openSettings() }) { Text("Einstellungen") }
            OutlinedButton(onClick = { vm.openProfile() }) { Text("Profil") }
            OutlinedButton(onClick = { vm.startDemoMode() }) { Text("Demo-Modus") }
        }
        Spacer(Modifier.height(24.dp))
        Text("Build ${formatBuildStamp(BuildConfig.BUILD_TIMESTAMP)}", style = MaterialTheme.typography.labelSmall)
    }
}
