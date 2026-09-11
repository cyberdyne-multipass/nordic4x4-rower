package com.n44r.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.n44r.app.session.PhaseType

val BgBlack = Color(0xFF0A0E10)
val TileGray = Color(0xFF1B2226)
val WarmupCooldownBlue = Color(0xFF2C5F7C)
val MainNeutral = Color(0xFF23414C)
val WorkOrange = Color(0xFFB33A2E)
val RestGreen = Color(0xFF1F6F5C)
val FreeGray = Color(0xFF3A3A3A)

fun colorForPhase(type: PhaseType): Color = when (type) {
    PhaseType.WARMUP, PhaseType.COOLDOWN -> WarmupCooldownBlue
    PhaseType.MAIN -> MainNeutral
    PhaseType.WORK -> WorkOrange
    PhaseType.REST -> RestGreen
    PhaseType.FREE -> FreeGray
}

@Composable
fun N44RTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BgBlack,
            surface = TileGray,
            primary = Color.White,
            onPrimary = Color(0xFF15181A),
            secondary = Color.White,
            onSecondary = Color(0xFF15181A),
            outline = Color.White,
            onSurface = Color.White,
            onBackground = Color.White
        ),
        content = content
    )
}
