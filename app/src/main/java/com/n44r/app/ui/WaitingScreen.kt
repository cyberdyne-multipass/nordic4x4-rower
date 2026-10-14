package com.n44r.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.n44r.app.MainViewModel
import com.n44r.app.ble.ConnectionState

/** Shown between "Start" and the first stroke - the session begins automatically on movement. */
@Composable
fun WaitingScreen(vm: MainViewModel) {
    val rowerState by vm.rowerBle.connectionState.collectAsState()
    val hrState by vm.heartRateBle.connectionState.collectAsState()
    val hrEnabled by vm.heartRateEnabled.collectAsState()
    val isDemo by vm.rowerBle.isDemoMode.collectAsState()
    val resumeAt by vm.pendingResumeAtSec.collectAsState()
    val connected = rowerState == ConnectionState.CONNECTED

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (connected) {
            Text(if (resumeAt != null) "Bereit zum Fortsetzen" else "Startklar", fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                if (resumeAt != null) "Das Training geht bei ${formatMmSs(resumeAt!!)} weiter – ruder einfach los."
                else "Ruder los, wenn du bereit bist – das Training startet beim ersten Schlag.",
                fontSize = 22.sp, textAlign = TextAlign.Center
            )
        } else {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Verbinde mit Rudergerät…", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(28.dp))
        Text("Rudergerät: ", style = MaterialTheme.typography.labelMedium)
        ConnectionBadge(rowerState)
        if (hrEnabled) {
            Spacer(Modifier.height(8.dp))
            Text("Herzfrequenz: ", style = MaterialTheme.typography.labelMedium)
            ConnectionBadge(hrState)
            val hrName by vm.heartRateBle.connectedName.collectAsState()
            val hrBattery by vm.heartRateBle.batteryLevel.collectAsState()
            val hrBpm by vm.heartRateBle.heartRateBpm.collectAsState()
            val contact by vm.heartRateBle.skinContact.collectAsState()
            if (hrState == ConnectionState.CONNECTED && hrName != null) {
                Text(
                    buildString {
                        append(hrName)
                        hrBattery?.let { append(" · Akku $it %") }
                        hrBpm?.let { append(" · $it bpm") }
                        if (contact == false) append(" · kein Hautkontakt")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hrBattery != null && hrBattery!! <= 15 || contact == false) Color(0xFFFFB74D) else Color.Unspecified
                )
            }
        }
        if (isDemo) {
            Spacer(Modifier.height(8.dp))
            Text("🧪 Demo-Modus", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = { vm.cancelWaiting() }) { Text("Abbrechen") }
    }
}
