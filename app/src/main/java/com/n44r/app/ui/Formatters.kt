package com.n44r.app.ui

fun formatMmSs(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    return String.format("%02d:%02d", s / 60, s % 60)
}

fun formatPace(secPer500m: Int?): String {
    if (secPer500m == null) return "--:--"
    return String.format("%d:%02d", secPer500m / 60, secPer500m % 60)
}

fun formatBuildStamp(timestampMs: Long): String {
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.GERMAN)
    return fmt.format(java.util.Date(timestampMs))
}
