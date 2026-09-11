package com.n44r.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n44r.app.BuildConfig
import com.n44r.app.MainViewModel
import com.n44r.app.session.HrZones
import com.n44r.app.session.LapSummary
import com.n44r.app.session.MainMetric
import com.n44r.app.session.PhaseTarget
import com.n44r.app.session.PhaseType
import com.n44r.app.session.RecordPoint
import com.n44r.app.ui.theme.colorForPhase

@Composable
fun DashboardScreen(vm: MainViewModel) {
    val ui by vm.sessionUi.collectAsState()
    val metric by vm.mainMetric.collectAsState()
    val isDemo by vm.rowerBle.isDemoMode.collectAsState()
    val profile by vm.profile.collectAsState()
    val state = ui ?: return

    val phaseColor by animateColorAsState(colorForPhase(state.phaseType).let {
        if (state.isManuallyPaused || state.isAutoPaused) it.copy(alpha = 0.4f) else it
    }, label = "phaseColor")

    Column(Modifier.fillMaxSize().background(phaseColor)) {
        Text(
            "Build ${formatBuildStamp(BuildConfig.BUILD_TIMESTAMP)}",
            color = Color(0x66FFFFFF), fontSize = 10.sp, modifier = Modifier.padding(4.dp)
        )
        if (isDemo) {
            Text("🧪 DEMO-MODUS – simulierte Daten", color = Color.White, fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            DemoSpeedControls(vm)
        }
        PhaseBanner(
            state.phaseLabel, state.phaseType, state.phaseRemainingSec, state.phaseRemainingMeters,
            isPaused = state.isManuallyPaused || state.isAutoPaused, isAutoPaused = state.isAutoPaused
        )

        // Five equal tiles, main metric in the middle (2 left, 2 right), all the same size.
        // The main metric's own tile is dropped from the side list so nothing shows twice.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            // Font budget: widest plausible value is 5 digits ("10000") plus one character of
            // safety margin on each side, so a >9999 m distance still fits symmetrically.
            // Bold digits are ~0.6 em wide -> 7 chars ≈ 4.2 em of tile width.
            val tileFont = (maxWidth.value / 5f / 4.2f).coerceIn(24f, 64f).sp
            // Distance first so the total rowed distance is always on screen whatever the main metric is.
            val side = listOf(MainMetric.DISTANCE, MainMetric.PACE_500M, MainMetric.POWER, MainMetric.STROKE_RATE, MainMetric.TIME)
                .filter { it != metric }.take(3)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MetricTile(tileLabel(side[0]), mainMetricValue(side[0], state), Modifier.weight(1f), tileFont)
                MetricTile(tileLabel(side[1]), mainMetricValue(side[1], state), Modifier.weight(1f), tileFont)
                MetricTile(tileLabel(metric), mainMetricValue(metric, state), Modifier.weight(1f), tileFont, highlight = true)
                MetricTile(tileLabel(side[2]), mainMetricValue(side[2], state), Modifier.weight(1f), tileFont)
                MetricTile(
                    "HF [bpm]", state.heartRateBpm?.toString() ?: "–", Modifier.weight(1f), tileFont,
                    onClick = if (state.heartRateBpm == null) { { vm.retryHeartRate() } } else null
                )
            }
        }

        val sm = vm.stateMachine
        val liveRecords by sm?.liveRecords?.collectAsState() ?: remember { mutableStateOf(emptyList<RecordPoint>()) }
        val liveLaps by sm?.liveLaps?.collectAsState() ?: remember { mutableStateOf(emptyList<LapSummary>()) }
        val completedSec = liveLaps.sumOf { it.durationSec }
        val currentPartialLap = LapSummary(
            phaseType = state.phaseType, label = state.phaseLabel,
            startDistanceM = 0, endDistanceM = state.totalDistanceM,
            durationSec = (state.sessionElapsedSec - completedSec).coerceAtLeast(0),
            avgPaceSecPer500m = null, avgPowerW = 0, avgStrokeRate = 0f
        )
        val hrAxisMin = profile?.restingHr?.coerceIn(30, 120) ?: 40
        val easyZone = profile?.let { HrZones.easyZone(it) }

        // Chart takes all remaining height; HR panel gets the lion's share, pace stays compact.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            val avail = (maxHeight.value - 70f).coerceAtLeast(120f) // legend + labels + spacers
            TrainingChart(
                liveRecords, liveLaps + currentPartialLap,
                sm?.workTarget ?: PhaseTarget(), sm?.restTarget ?: PhaseTarget(),
                state.sessionElapsedSec, isLive = true, compact = true,
                hrAxisMin = hrAxisMin, easyZone = easyZone,
                hrPanelHeight = (avail * 0.62f).dp, pacePanelHeight = (avail * 0.38f).dp
            )
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = { vm.togglePause() }) { Text(if (state.isManuallyPaused) "Weiter" else "Pause") }
            Button(onClick = { vm.finishSession() }) { Text("Workout beenden") }
        }
    }
}

@Composable
private fun MetricTile(
    label: String, value: String, modifier: Modifier = Modifier, fontSize: TextUnit = 30.sp,
    highlight: Boolean = false, onClick: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    ) {
        Text(label, color = if (highlight) Color(0xFFFFD54F) else Color(0xCCFFFFFF), fontSize = 16.sp,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal)
        Text(value, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
        if (onClick != null) Text("↻ verbinden", color = Color(0xAAFFFFFF), fontSize = 11.sp)
    }
}

private fun tileLabel(m: MainMetric) = when (m) {
    MainMetric.PACE_500M -> "Split /500m"
    MainMetric.DISTANCE -> "Distanz [m]"
    MainMetric.POWER -> "Power [W]"
    MainMetric.STROKE_RATE -> "Schlagzahl"
    MainMetric.TIME -> "Zeit"
}

@Composable
private fun PhaseBanner(
    label: String, type: PhaseType, remainingSec: Int?, remainingM: Int?,
    isPaused: Boolean, isAutoPaused: Boolean
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        val statusText = when {
            isPaused -> if (isAutoPaused) "PAUSIERT (kein Rudern)" else "PAUSIERT"
            type == PhaseType.FREE -> "läuft weiter"
            remainingSec != null -> "noch ${formatMmSs(remainingSec)}"
            remainingM != null -> "noch $remainingM m"
            else -> ""
        }
        Text(statusText, color = Color.White, fontSize = 22.sp, fontWeight = if (isPaused) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun mainMetricValue(metric: MainMetric, s: com.n44r.app.session.SessionUiState): String = when (metric) {
    MainMetric.DISTANCE -> "${s.totalDistanceM}"
    MainMetric.TIME -> formatMmSs(s.sessionElapsedSec)
    MainMetric.PACE_500M -> formatPace(s.splitPaceSecPer500m)
    MainMetric.POWER -> "${s.powerW}"
    MainMetric.STROKE_RATE -> String.format("%.0f", s.strokeRate)
}

@Composable
private fun DemoSpeedControls(vm: MainViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Springen:", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        OutlinedButton(onClick = { vm.seekDemo(-300) }, modifier = Modifier.padding(horizontal = 3.dp)) {
            Text("⏪ 5 min", color = Color.White, fontSize = 12.sp)
        }
        OutlinedButton(onClick = { vm.seekDemo(-60) }, modifier = Modifier.padding(horizontal = 3.dp)) {
            Text("⏪ 1 min", color = Color.White, fontSize = 12.sp)
        }
        OutlinedButton(onClick = { vm.seekDemo(60) }, modifier = Modifier.padding(horizontal = 3.dp)) {
            Text("1 min ⏩", color = Color.White, fontSize = 12.sp)
        }
        OutlinedButton(onClick = { vm.seekDemo(300) }, modifier = Modifier.padding(horizontal = 3.dp)) {
            Text("5 min ⏩", color = Color.White, fontSize = 12.sp)
        }
    }
}
