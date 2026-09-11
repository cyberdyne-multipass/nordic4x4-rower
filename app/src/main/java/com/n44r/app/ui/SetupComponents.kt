package com.n44r.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.n44r.app.ble.ConnectionState
import com.n44r.app.session.IntervalConfig
import com.n44r.app.session.TargetType

@Composable
fun ConnectionBadge(state: ConnectionState) {
    val (text, color) = when (state) {
        ConnectionState.CONNECTED -> "Verbunden" to Color(0xFF3DBE7A)
        ConnectionState.CONNECTING -> "Verbinde..." to Color(0xFFD9A441)
        ConnectionState.SCANNING -> "Suche ComModule..." to Color(0xFFD9A441)
        ConnectionState.RECONNECTING -> "Verbindung verloren, neu verbinden..." to Color(0xFFD9663D)
        ConnectionState.IDLE -> "Nicht verbunden" to Color(0xFF888888)
    }
    Text(text, color = color)
}

@Composable
fun <T> SegmentedRow(items: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            FilterChip(selected = item == selected, onClick = { onSelect(item) }, label = { Text(label(item)) })
        }
    }
}

@Composable
fun StepperRow(label: String, value: Int, unit: String, step: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.width(120.dp))
        OutlinedButton(onClick = { onChange(value - step) }) { Text("-") }
        Text("$value $unit", modifier = Modifier.width(80.dp))
        OutlinedButton(onClick = { onChange(value + step) }) { Text("+") }
    }
}

@Composable
fun PaceStepperRow(label: String, valueSec: Int, step: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.width(120.dp))
        OutlinedButton(onClick = { onChange((valueSec - step).coerceAtLeast(30)) }) { Text("-") }
        Text(formatPace(valueSec) + "/500m", modifier = Modifier.width(110.dp))
        OutlinedButton(onClick = { onChange(valueSec + step) }) { Text("+") }
    }
}

fun targetTypeLabel(t: TargetType) = when (t) {
    TargetType.NONE -> "Kein Ziel"; TargetType.PACE -> "Pace"; TargetType.HEART_RATE -> "Herzfrequenz"
}
