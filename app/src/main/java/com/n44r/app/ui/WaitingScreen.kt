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
import com.n44r.app.MainViewModel
import com.n44r.app.ble.ConnectionState

/** Shown between "Start" and the first stroke - the session begins automatically on movement. */
@Composable
fun WaitingScreen(vm: MainViewModel) {
    val rowerState by vm.rowerBle.connectionState.collectAsState()
    val hrState by vm.heartRateBle.connectionState.collectAsState()
    val hrEnabled by vm.heartRateEnabled.collectAsState()
    val isDemo by vm.rowerBle.isDemoMode.collectAsState()
    val connected = rowerState == ConnectionState.CONNECTED

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (connected) {
            Text("Startklar", fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Ruder los, wenn du bereit bist – das Training startet beim ersten Schlag.",
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
        }
        if (isDemo) {
            Spacer(Modifier.height(8.dp))
            Text("🧪 Demo-Modus", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = { vm.cancelWaiting() }) { Text("Abbrechen") }
    }
}
