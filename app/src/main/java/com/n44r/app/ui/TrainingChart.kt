package com.n44r.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n44r.app.session.*
import com.n44r.app.ui.theme.colorForPhase
import kotlin.math.max

enum class ChartMetric { PACE, HEART_RATE }

private const val FIXED_WINDOW_SEC = 1200
private const val RIGHT_MARGIN_SEC = 60
private const val LEFT_MARGIN_SEC = 60
private const val LABEL_INSET_DP = 44f
private val PACE_FIXED_RANGE = 90f to 240f // 1:30 - 4:00 /500m
private val TARGET_ZONE_COLOR = Color(0xFFFFD54F)

/** Fallback easy zone for warm-up/cool-down when no profile-derived zone is available. */
private val DEFAULT_EASY_ZONE = 100 to 130

@Composable
fun TrainingChart(
    records: List<RecordPoint>,
    laps: List<LapSummary>,
    workTarget: PhaseTarget,
    restTarget: PhaseTarget,
    elapsedSec: Int,
    isLive: Boolean,
    compact: Boolean = false,
    hrAxisMin: Int = 40,
    easyZone: Pair<Int, Int>? = null,
    hrPanelHeight: Dp? = null,
    pacePanelHeight: Dp? = null
) {
    val easyTarget = (easyZone ?: DEFAULT_EASY_ZONE).let {
        PhaseTarget(type = TargetType.HEART_RATE, hrZoneLowBpm = it.first, hrZoneHighBpm = it.second)
    }
    val hrRange = hrAxisMin.toFloat() to 200f
    // Scale px-per-second to the actual available width (minus the label inset) so the fixed
    // 20-min window always exactly fills the panel, on any tablet - a hardcoded dp/sec value
    // left a gap on wider screens and looked off-center.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val pxPerSec = ((maxWidth.value - LABEL_INSET_DP) / FIXED_WINDOW_SEC).coerceAtLeast(0.2f)
        val density = LocalDensity.current
        val scrollState = rememberScrollState()
        val contentSec = max(FIXED_WINDOW_SEC, elapsedSec + if (isLive) RIGHT_MARGIN_SEC else 0)
        val totalSpanSec = contentSec + LEFT_MARGIN_SEC
        val contentWidthDp = totalSpanSec * pxPerSec

        if (isLive) {
            LaunchedEffect(elapsedSec, pxPerSec) {
                val targetSec = (elapsedSec - (FIXED_WINDOW_SEC - RIGHT_MARGIN_SEC)).coerceAtLeast(0)
                val targetPx = with(density) { (targetSec * pxPerSec).dp.roundToPx() }
                scrollState.scrollTo(targetPx)
            }
        }

        Column {
            if (!compact) {
                Text("Trainingsverlauf", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            ChartLegend(laps, workTarget, restTarget)
            Spacer(Modifier.height(4.dp))
            val hrH = hrPanelHeight ?: if (compact) 90.dp else 150.dp
            val paceH = pacePanelHeight ?: if (compact) 90.dp else 150.dp

            Text("Herzfrequenz", style = MaterialTheme.typography.labelSmall)
            ChartPanel(records, laps, workTarget, restTarget, easyTarget, contentSec, contentWidthDp, hrH, scrollState, ChartMetric.HEART_RATE, fixedRange = hrRange, pxPerSec = pxPerSec)
            Spacer(Modifier.height(6.dp))
            Text("Pace (500m)", style = MaterialTheme.typography.labelSmall)
            ChartPanel(records, laps, workTarget, restTarget, easyTarget, contentSec, contentWidthDp, paceH, scrollState, ChartMetric.PACE, fixedRange = PACE_FIXED_RANGE, pxPerSec = pxPerSec)
        }
    }
}

@Composable
private fun ChartPanel(
    records: List<RecordPoint>, laps: List<LapSummary>, workTarget: PhaseTarget, restTarget: PhaseTarget, easyTarget: PhaseTarget,
    contentSec: Int, contentWidthDp: Float, panelHeight: Dp,
    scrollState: androidx.compose.foundation.ScrollState, metric: ChartMetric, fixedRange: Pair<Float, Float>, pxPerSec: Float
) {
    Box(Modifier.fillMaxWidth().height(panelHeight)) {
        Box(Modifier.fillMaxWidth().padding(start = LABEL_INSET_DP.dp).horizontalScroll(scrollState)) {
            Canvas(Modifier.width(contentWidthDp.dp).height(panelHeight)) {
                drawTrainingChart(records, laps, workTarget, restTarget, easyTarget, contentSec, metric, fixedRange, pxPerSec)
            }
        }
        val fmt: (Float) -> String = if (metric == ChartMetric.PACE) { v -> formatPace(v.toInt()) } else { v -> "${v.toInt()} bpm" }
        val topValue = if (metric == ChartMetric.PACE) fixedRange.first else fixedRange.second
        val bottomValue = if (metric == ChartMetric.PACE) fixedRange.second else fixedRange.first
        AxisLabelChip(fmt(topValue), Modifier.align(Alignment.TopStart))
        AxisLabelChip(fmt(bottomValue), Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun AxisLabelChip(text: String, alignmentModifier: Modifier) {
    Text(
        text, color = Color.White, fontSize = 12.sp,
        modifier = alignmentModifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
private fun ChartLegend(laps: List<LapSummary>, workTarget: PhaseTarget, restTarget: PhaseTarget) {
    val presentTypes = laps.map { it.phaseType }.distinct()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        presentTypes.forEach { type -> LegendItem(colorForPhase(type), phaseLegendLabel(type)) }
        LegendItem(TARGET_ZONE_COLOR, "Sollbereich")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun phaseLegendLabel(t: PhaseType) = when (t) {
    PhaseType.WARMUP -> "Warm-up"; PhaseType.MAIN -> "Training"; PhaseType.WORK -> "Work"
    PhaseType.REST -> "Erholung"; PhaseType.COOLDOWN -> "Cool-down"; PhaseType.FREE -> "Frei"
}

private fun targetFor(phaseType: PhaseType, workTarget: PhaseTarget, restTarget: PhaseTarget, easyTarget: PhaseTarget, metric: ChartMetric): PhaseTarget? = when (phaseType) {
    PhaseType.WORK -> workTarget
    PhaseType.REST -> restTarget
    PhaseType.WARMUP, PhaseType.COOLDOWN -> if (metric == ChartMetric.HEART_RATE) easyTarget else null
    else -> null
}

private fun DrawScope.drawTrainingChart(
    records: List<RecordPoint>, laps: List<LapSummary>, workTarget: PhaseTarget, restTarget: PhaseTarget, easyTarget: PhaseTarget,
    contentSec: Int, metric: ChartMetric, range: Pair<Float, Float>, pxPerSec: Float
) {
    val w = size.width
    val h = size.height
    val (minV, maxV) = range
    val totalSpanSec = contentSec + LEFT_MARGIN_SEC
    val axisPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(200, 255, 255, 255); textSize = 24f; isAntiAlias = true
    }
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)

    fun xOf(sec: Int) = w * (sec + LEFT_MARGIN_SEC) / totalSpanSec.toFloat()
    fun yOf(v: Float): Float {
        val t = ((v - minV) / (maxV - minV)).coerceIn(0f, 1f)
        val tAdj = if (metric == ChartMetric.PACE) 1f - t else t
        return h - tAdj * h
    }

    var cursor = 0
    for (lap in laps) {
        val x0 = xOf(cursor)
        cursor += lap.durationSec
        val x1 = xOf(cursor)
        drawRect(colorForPhase(lap.phaseType).copy(alpha = 0.35f), topLeft = Offset(x0, 0f), size = Size(x1 - x0, h))
    }

    drawLine(Color.White.copy(alpha = 0.4f), Offset(0f, 1f), Offset(w, 1f), strokeWidth = 2f)
    drawLine(Color.White.copy(alpha = 0.4f), Offset(0f, h - 1f), Offset(w, h - 1f), strokeWidth = 2f)

    val hasPoints = when (metric) {
        ChartMetric.PACE -> records.any { it.paceSecPer500m != null }
        ChartMetric.HEART_RATE -> records.any { it.heartRateBpm != null }
    }
    if (!hasPoints && metric == ChartMetric.HEART_RATE) {
        drawContext.canvas.nativeCanvas.drawText("Keine HF-Daten", xOf(0) + 8f, 24f, axisPaint)
    }

    var c2 = 0
    for (lap in laps) {
        val x0 = xOf(c2)
        c2 += lap.durationSec
        val x1 = xOf(c2)
        val target = targetFor(lap.phaseType, workTarget, restTarget, easyTarget, metric) ?: continue
        val bandMin = if (metric == ChartMetric.PACE && target.type == TargetType.PACE) target.paceTargetMinSec
            else if (metric == ChartMetric.HEART_RATE && target.type == TargetType.HEART_RATE) target.hrZoneLowBpm else null
        val bandMax = if (metric == ChartMetric.PACE && target.type == TargetType.PACE) target.paceTargetMaxSec
            else if (metric == ChartMetric.HEART_RATE && target.type == TargetType.HEART_RATE) target.hrZoneHighBpm else null
        if (bandMin != null && bandMax != null) {
            val yTop = yOf(maxOf(bandMin, bandMax).toFloat())
            val yBottom = yOf(minOf(bandMin, bandMax).toFloat())
            drawRect(TARGET_ZONE_COLOR.copy(alpha = 0.20f), topLeft = Offset(x0, yTop), size = Size(x1 - x0, yBottom - yTop))
            drawLine(TARGET_ZONE_COLOR, Offset(x0, yTop), Offset(x1, yTop), strokeWidth = 3.5f, pathEffect = dashEffect)
            drawLine(TARGET_ZONE_COLOR, Offset(x0, yBottom), Offset(x1, yBottom), strokeWidth = 3.5f, pathEffect = dashEffect)
        }
    }

    val points: List<Pair<Int, Float>> = when (metric) {
        ChartMetric.PACE -> records.mapNotNull { r -> r.paceSecPer500m?.let { r.sessionElapsedSec to it.toFloat() } }
        ChartMetric.HEART_RATE -> records.mapNotNull { r -> r.heartRateBpm?.let { r.sessionElapsedSec to it.toFloat() } }
    }
    if (points.isNotEmpty()) {
        val path = Path()
        points.forEachIndexed { i, (sec, v) ->
            val x = xOf(sec); val y = yOf(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = Color.White, style = Stroke(width = 4f, cap = StrokeCap.Round))
    }

    var tSec = 0
    val nativeCanvas = drawContext.canvas.nativeCanvas
    while (tSec <= contentSec) {
        val x = xOf(tSec)
        nativeCanvas.drawText(formatMmSs(tSec), x + 4f, h - 8f, axisPaint)
        drawLine(Color.White.copy(alpha = 0.15f), Offset(x, 0f), Offset(x, h))
        tSec += 300
    }
}
