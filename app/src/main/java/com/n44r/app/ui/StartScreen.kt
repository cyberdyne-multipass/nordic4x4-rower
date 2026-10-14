package com.n44r.app.ui

import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    val resumable by vm.resumable.collectAsState()
    val crash by vm.crashReport.collectAsState()
    val context = LocalContext.current

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
        // An unfinished (crashed / killed) workout gets a prominent hint right below Start.
        val unfinished = resumable.firstOrNull()?.takeIf { !it.finished }
        if (unfinished != null) {
            Text(
                (if (unfinished.demo) "Letztes Demo-Training" else "Letztes Training") +
                    " wurde nicht beendet (${formatMmSs(unfinished.durationSec)} · ${unfinished.distanceM} m)",
                color = Color(0xFFFFD54F), style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (resumable.isNotEmpty()) {
                val label = "Workout fortsetzen…"
                if (unfinished != null) Button(onClick = { vm.openResume() }) { Text(label) }
                else OutlinedButton(onClick = { vm.openResume() }) { Text(label) }
            }
            OutlinedButton(onClick = { vm.openSettings() }) { Text("Einstellungen") }
            OutlinedButton(onClick = { vm.openProfile() }) { Text("Profil") }
            OutlinedButton(onClick = { vm.startDemoMode() }) { Text("Demo-Modus") }
        }
        crash?.let { file ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Die App ist zuletzt abgestürzt.", color = Color(0xFFFF8A65), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    val uri = vm.shareUri(context, file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Absturzbericht teilen"))
                }) { Text("Bericht teilen") }
                TextButton(onClick = { vm.dismissCrashReport() }) { Text("Ausblenden") }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Build ${formatBuildStamp(BuildConfig.BUILD_TIMESTAMP)}", style = MaterialTheme.typography.labelSmall)
    }
}
