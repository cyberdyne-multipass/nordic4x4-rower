package com.n44r.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n44r.app.MainViewModel
import com.n44r.app.session.HrZones
import com.n44r.app.session.LapSummary
import com.n44r.app.session.PhaseTarget
import com.n44r.app.session.PhaseType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * "Workout fortsetzen": pick one of the recent workouts (crashed or finished), then pick the
 * point in time to continue from - everything after that point is discarded ("rewind").
 */
@Composable
fun ResumeScreen(vm: MainViewModel) {
    val list by vm.resumable.collectAsState()
    val data by vm.resumeData.collectAsState()
    val profile by vm.profile.collectAsState()
    val dateFmt = remember { SimpleDateFormat("EEE d. MMM, HH:mm", Locale.GERMAN) }

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Workout fortsetzen", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        val d = data
        if (d == null) {
            Text("Welches Training?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (list.isEmpty()) Text("Keine gespeicherten Trainings vorhanden.")
            list.forEach { info ->
                Card(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(vertical = 4.dp).clickable { vm.selectResume(info) }
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                dateFmt.format(Date(info.startedAtMs)) + if (info.demo) "  🧪 DEMO" else "",
                                fontWeight = FontWeight.Bold, fontSize = 18.sp
                            )
                            Text("${info.modeLabel} · ${formatMmSs(info.durationSec)} · ${info.distanceM} m")
                        }
                        Text(
                            if (info.finished) "beendet" else "NICHT BEENDET",
                            color = if (info.finished) Color(0xAAFFFFFF) else Color(0xFFFFD54F),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = { vm.backToStart() }) { Text("Zurück") }
            return@Column
        }

        val records = d.records
        if (records.isEmpty()) {
            Text("Dieses Training enthält keine Daten.")
            OutlinedButton(onClick = { vm.clearResumeSelection() }) { Text("Zurück") }
            return@Column
        }
        val maxSec = (records.last().sessionElapsedMs / 1000).toInt()
        var cutSec by remember(d) { mutableStateOf(maxSec) }
        val cutRecord = records.lastOrNull { it.sessionElapsedMs / 1000 <= cutSec } ?: records.first()
        val phase = d.plan.getOrNull(cutRecord.phaseIndex)

        Text(dateFmt.format(Date(d.startedAtMs)) + " · " + d.modeLabel, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        // full chart of the stored workout; the part after the cut point is what gets discarded
        val laps = remember(d) { lapsFromRecords(records, d.plan.map { it.type to it.label }) }
        TrainingChart(
            records.filter { it.sessionElapsedMs / 1000 <= cutSec }, laps,
            d.plan.firstOrNull { it.type == PhaseType.WORK }?.target ?: PhaseTarget(),
            d.plan.firstOrNull { it.type == PhaseType.REST }?.target ?: PhaseTarget(),
            maxSec, isLive = false, compact = true,
            hrAxisMin = profile?.restingHr?.coerceIn(30, 120) ?: 40,
            easyZone = profile?.let { HrZones.easyZone(it) },
            hrPanelHeight = 140.dp, pacePanelHeight = 100.dp
        )
        Spacer(Modifier.height(12.dp))

        Text(
            "Fortsetzen ab ${formatMmSs(cutSec)} · ${cutRecord.distanceM} m · ${phase?.label ?: ""}",
            fontSize = 22.sp, fontWeight = FontWeight.Bold
        )
        Slider(
            value = cutSec.toFloat(), onValueChange = { cutSec = it.roundToInt() },
            valueRange = 0f..maxSec.toFloat(), modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(-60, -10, -5, 5, 10, 60).forEach { delta ->
                OutlinedButton(onClick = { cutSec = (cutSec + delta).coerceIn(0, maxSec) }) {
                    Text(if (delta < 0) "${delta} s" else "+${delta} s")
                }
            }
            OutlinedButton(onClick = { cutSec = maxSec }) { Text("Ende") }
        }
        Text(
            if (cutSec < maxSec) "Alles nach ${formatMmSs(cutSec)} wird verworfen." else "Es wird nichts verworfen.",
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = { vm.clearResumeSelection() }) { Text("Anderes Training") }
            Button(
                onClick = { vm.confirmResume(cutRecord.sessionElapsedMs) },
                modifier = Modifier.height(56.dp)
            ) { Text("Ab hier fortsetzen", fontSize = 18.sp) }
        }
    }
}

/** Phase bands for the preview chart, derived from the stored records. */
private fun lapsFromRecords(records: List<com.n44r.app.session.RecordPoint>, plan: List<Pair<PhaseType, String>>): List<LapSummary> {
    val out = mutableListOf<LapSummary>()
    var startSec = 0
    records.groupBy { it.phaseIndex }.toSortedMap().forEach { (idx, recs) ->
        val endSec = recs.last().sessionElapsedSec
        val (type, label) = plan.getOrElse(idx) { PhaseType.FREE to "" }
        out.add(LapSummary(type, label, recs.first().distanceM, recs.last().distanceM, (endSec - startSec).coerceAtLeast(0), null, 0, 0f))
        startSec = endSec
    }
    return out
}
