package com.mlbb.surecure.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color

/**
 * SureCure's colours. Always light and never wallpaper-based ("dynamic colour"): the screens and the
 * dashboard design are light, and verdict colours (green, amber, red) must look the same on every
 * phone. Before 2026-11-20 the theme followed system dark mode, which made typed text hard to read on
 * the light screens, and Android 12+ retinted buttons to the wallpaper.
 */
private val SureCureColors = lightColorScheme(
    primary = SureCureGreen,
    onPrimary = Color.White,
    primaryContainer = SureCureMint,
    onPrimaryContainer = SureCureDarkGreen,
    secondary = SureCureDarkGreen,
    onSecondary = Color.White,
    error = SureCureRed,
    background = Color.White,
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
)

@Composable
fun SureCureTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SureCureColors, typography = Typography) {
        // A background and content colour for every screen.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
