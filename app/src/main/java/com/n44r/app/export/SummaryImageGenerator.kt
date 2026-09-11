package com.n44r.app.export

import android.content.Context
import android.graphics.*
import com.n44r.app.R
import com.n44r.app.session.PhaseType
import com.n44r.app.session.SessionSummary
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * End-of-workout share cards on Hans' AI-generated rower photos, in three formats. Data is
 * overlaid on translucent panels: tiles in the dark upper-left area, and a pace chart across
 * the bottom (wood/floor) with phase-coloured bands so the intervals stand out. The chart is
 * deliberately squeezed horizontally and stretched vertically for exactly that effect.
 */
object SummaryImageGenerator {

    enum class CardFormat(val w: Int, val h: Int, val bgRes: Int, val suffix: String) {
        SQUARE(1080, 1080, R.drawable.summary_bg_square, "square"),
        LANDSCAPE(1920, 1080, R.drawable.summary_bg_landscape, "wide"),
        PORTRAIT(1080, 1920, R.drawable.summary_bg_portrait, "story")
    }

    private fun phaseColor(t: PhaseType): Int = when (t) {
        PhaseType.WARMUP, PhaseType.COOLDOWN -> Color.rgb(44, 95, 124)
        PhaseType.MAIN -> Color.rgb(35, 65, 76)
        PhaseType.WORK -> Color.rgb(179, 58, 46)
        PhaseType.REST -> Color.rgb(31, 111, 92)
        PhaseType.FREE -> Color.rgb(58, 58, 58)
    }

    fun generate(context: Context, summary: SessionSummary, modeLabel: String, outFile: File, format: CardFormat, isDemo: Boolean = false) {
        val w = format.w; val h = format.h
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        drawBackground(context, canvas, format)

        val shadow = Color.argb(200, 0, 0, 0)
        val titlePaint = Paint().apply { color = Color.WHITE; textSize = 58f; isFakeBoldText = true; isAntiAlias = true; setShadowLayer(8f, 0f, 2f, shadow) }
        val subPaint = Paint().apply { color = Color.rgb(220, 230, 235); textSize = 30f; isAntiAlias = true; setShadowLayer(6f, 0f, 2f, shadow) }
        val dateStr = SimpleDateFormat("d. MMMM yyyy", Locale.GERMAN).format(Date(summary.startedAtMs))
        canvas.drawText("TRAINING ABGESCHLOSSEN", 48f, 100f, titlePaint)
        canvas.drawText("$modeLabel · $dateStr", 48f, 146f, subPaint)

        val tiles = mutableListOf(
            "Dauer" to formatDuration(summary.totalDurationSec),
            "Distanz" to String.format(Locale.GERMAN, "%.2f km", summary.totalDistanceM / 1000.0),
            "Ø Split /500m" to (summary.avgPaceSecPer500m?.let { formatSplit(it) } ?: "--:--"),
            "Ø Schlagzahl" to String.format(Locale.GERMAN, "%.1f", summary.avgStrokeRate)
        )
        summary.maxHeartRateBpm?.let { tiles.add("Max. HF" to "$it bpm") }
        summary.laps.count { it.phaseType == PhaseType.WORK }.takeIf { it > 0 }?.let { tiles.add("Intervalle" to "$it") }

        val chartRect: RectF
        when (format) {
            CardFormat.SQUARE -> {
                drawTiles(canvas, tiles, x0 = 48f, y0 = 186f, cols = 2, tw = 268f, th = 112f)
                chartRect = RectF(48f, 780f, w - 48f, h - 48f)
            }
            CardFormat.LANDSCAPE -> {
                drawTiles(canvas, tiles, x0 = 60f, y0 = 186f, cols = 3, tw = 250f, th = 112f)
                chartRect = RectF(60f, 830f, w - 60f, h - 40f)
            }
            CardFormat.PORTRAIT -> {
                drawTiles(canvas, tiles, x0 = 48f, y0 = 186f, cols = 2, tw = 268f, th = 112f)
                chartRect = RectF(48f, 1520f, w - 48f, h - 60f)
            }
        }
        drawPaceChart(canvas, chartRect, summary)
        drawBrand(context, canvas, w)

        if (isDemo) {
            val demoPaint = Paint().apply { color = Color.rgb(255, 110, 80); textSize = 34f; isFakeBoldText = true; isAntiAlias = true; setShadowLayer(6f, 0f, 2f, shadow) }
            canvas.drawText("DEMO – KEINE ECHTEN TRAININGSDATEN", 48f, chartRect.top - 14f, demoPaint)
        }

        FileOutputStream(outFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
        bmp.recycle()
    }

    private fun drawBackground(context: Context, canvas: Canvas, f: CardFormat) {
        val bg = BitmapFactory.decodeResource(context.resources, f.bgRes)
        if (bg == null) { canvas.drawColor(Color.rgb(10, 22, 30)); return }
        val scale = maxOf(f.w.toFloat() / bg.width, f.h.toFloat() / bg.height)
        val sw = (f.w / scale).toInt(); val sh = (f.h / scale).toInt()
        val sx = (bg.width - sw) / 2; val sy = (bg.height - sh) / 2
        canvas.drawBitmap(bg, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, f.w, f.h), Paint(Paint.FILTER_BITMAP_FLAG))
        bg.recycle()
    }

    private fun drawBrand(context: Context, canvas: Canvas, w: Int) {
        val mark = BitmapFactory.decodeResource(context.resources, R.drawable.brand_icon) ?: return
        val mh = 130f; val mw = mh * mark.width / mark.height
        val pad = 16f
        val textH = 34f
        val left = w - mw - 36f - pad
        val top = 28f
        // same translucent panel as the value tiles
        val panel = Paint().apply { color = Color.argb(150, 8, 16, 22); isAntiAlias = true }
        canvas.drawRoundRect(RectF(left - pad, top - pad, left + mw + pad, top + mh + textH + pad), 18f, 18f, panel)
        // icon drawn as-is (opaque blue background + white 4, exactly as designed) - only the
        // corners outside its own rounded square are transparent so it doesn't sit in a box
        canvas.drawBitmap(mark, null, RectF(left, top, left + mw, top + mh), Paint(Paint.FILTER_BITMAP_FLAG))
        mark.recycle()
        val p = Paint().apply { color = Color.WHITE; textSize = 26f; isFakeBoldText = true; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        canvas.drawText("Nordic4x4 Rower", left + mw / 2f, top + mh + 26f, p)
    }

    private fun drawTiles(canvas: Canvas, tiles: List<Pair<String, String>>, x0: Float, y0: Float, cols: Int, tw: Float, th: Float) {
        val panel = Paint().apply { color = Color.argb(150, 8, 16, 22); isAntiAlias = true }
        val labelPaint = Paint().apply { color = Color.rgb(190, 205, 215); textSize = 25f; isAntiAlias = true }
        val valuePaint = Paint().apply { color = Color.WHITE; textSize = 50f; isFakeBoldText = true; isAntiAlias = true }
        val gap = 16f
        tiles.forEachIndexed { i, (label, value) ->
            val col = i % cols; val row = i / cols
            val x = x0 + col * (tw + gap); val y = y0 + row * (th + gap)
            canvas.drawRoundRect(RectF(x, y, x + tw, y + th), 18f, 18f, panel)
            canvas.drawText(label, x + 18f, y + 36f, labelPaint)
            canvas.drawText(value, x + 18f, y + 90f, valuePaint)
        }
    }

    /** Phase bands + pace line, stretched to fill the panel so intervals pop. Fast pace = up. */
    private fun drawPaceChart(canvas: Canvas, r: RectF, s: SessionSummary) {
        val panel = Paint().apply { color = Color.argb(165, 8, 16, 22); isAntiAlias = true }
        canvas.drawRoundRect(r, 22f, 22f, panel)

        val label = Paint().apply { color = Color.rgb(200, 212, 220); textSize = 24f; isAntiAlias = true }
        canvas.drawText("Pace /500m", r.left + 20f, r.top + 32f, label)

        val inner = RectF(r.left + 20f, r.top + 48f, r.right - 90f, r.bottom - 20f)
        val totalSec = s.laps.sumOf { it.durationSec }.coerceAtLeast(1)
        fun xOf(sec: Float) = inner.left + inner.width() * (sec / totalSec)

        // phase bands
        var cursor = 0f
        val band = Paint().apply { isAntiAlias = true }
        for (lap in s.laps) {
            val x0 = xOf(cursor); cursor += lap.durationSec; val x1 = xOf(cursor)
            band.color = phaseColor(lap.phaseType); band.alpha = 165
            canvas.drawRect(x0, inner.top, x1, inner.bottom, band)
        }

        // pace line
        val pts = s.records.mapNotNull { rec -> rec.paceSecPer500m?.takeIf { it in 60..400 }?.let { rec.sessionElapsedSec to it } }
        if (pts.size < 2) return
        val minP = pts.minOf { it.second }; val maxP = pts.maxOf { it.second }
        val lo = (minP - 5).toFloat(); val hi = (maxP + 5).toFloat()
        fun yOf(p: Int) = inner.top + inner.height() * ((p - lo) / (hi - lo)) // slower = lower
        val path = Path()
        pts.forEachIndexed { i, (sec, p) -> val x = xOf(sec.toFloat()); val y = yOf(p); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        val line = Paint().apply { color = Color.WHITE; strokeWidth = 4f; style = Paint.Style.STROKE; isAntiAlias = true; strokeJoin = Paint.Join.ROUND; setShadowLayer(4f, 0f, 1f, Color.argb(160, 0, 0, 0)) }
        canvas.drawPath(path, line)

        // axis labels (fast on top)
        val axis = Paint().apply { color = Color.WHITE; textSize = 22f; isAntiAlias = true }
        canvas.drawText(formatSplit(minP), inner.right + 10f, inner.top + 20f, axis)
        canvas.drawText(formatSplit(maxP), inner.right + 10f, inner.bottom - 4f, axis)
    }

    private fun formatDuration(totalSec: Int) = String.format(Locale.GERMAN, "%02d:%02d", totalSec / 60, totalSec % 60)
    private fun formatSplit(secPer500m: Int) = String.format(Locale.GERMAN, "%d:%02d", secPer500m / 60, secPer500m % 60)
}
