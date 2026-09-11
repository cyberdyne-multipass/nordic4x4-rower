package com.n44r.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.n44r.app.MainViewModel
import com.n44r.app.session.Gender
import com.n44r.app.session.HrZones
import com.n44r.app.session.UserProfile

@Composable
fun ProfileScreen(vm: MainViewModel) {
    val existing by vm.profile.collectAsState()
    val firstRun = existing == null

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var gender by remember { mutableStateOf(existing?.gender ?: Gender.MALE) }
    var ageText by remember { mutableStateOf(existing?.age?.toString() ?: "") }
    var restingText by remember { mutableStateOf(existing?.restingHr?.toString() ?: "") }

    val age = ageText.toIntOrNull()
    val resting = restingText.toIntOrNull()
    val preview = age?.let { UserProfile(name, gender, it, resting) }

    Column(
        Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (firstRun) "Willkommen – kurz ein paar Angaben" else "Profil", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Alter und Geschlecht braucht die App für die Schätzung deiner maximalen Herzfrequenz. " +
                "Der Ruhepuls ist optional – mit ihm werden die Pulszonen deutlich individueller (Karvonen-Methode).",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        Text("Geschlecht", style = MaterialTheme.typography.titleSmall)
        SegmentedRow(Gender.values().toList(), gender, { gender = it }) {
            when (it) { Gender.MALE -> "Männlich"; Gender.FEMALE -> "Weiblich"; Gender.OTHER -> "Divers" }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = ageText, onValueChange = { ageText = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("Alter") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = restingText, onValueChange = { restingText = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("Ruhepuls (optional, bpm)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Spacer(Modifier.height(20.dp))

        if (preview != null) {
            val work = HrZones.nordicWorkZone(preview)
            val rest = HrZones.nordicRecoveryZone(preview)
            val easy = HrZones.easyZone(preview)
            Text("Geschätzte max. Herzfrequenz: ${HrZones.estimateMaxHr(preview)} bpm", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Nordic 4x4 – Belastung ${work.first}–${work.second} bpm · Erholung ${rest.first}–${rest.second} bpm · " +
                    "Warm-up/Cool-down ${easy.first}–${easy.second} bpm" +
                    if (HrZones.usesKarvonen(preview)) "  (Karvonen)" else "  (%HFmax, ohne Ruhepuls)",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(20.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { preview?.let { vm.saveProfile(it) } },
                enabled = age != null && age in 10..100
            ) { Text("Speichern") }
            if (!firstRun) OutlinedButton(onClick = { vm.backToStart() }) { Text("Abbrechen") }
        }
    }
}
