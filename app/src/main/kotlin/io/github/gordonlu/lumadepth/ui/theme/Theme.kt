package io.github.gordonlu.lumadepth.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LumaDepthColors = darkColorScheme(
    primary = Color(0xFFE8B34B),
    onPrimary = Color(0xFF1A1204),
    secondary = Color(0xFF4B8BE8),
    onSecondary = Color(0xFF04070E),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE6E9F0),
    surface = Color(0xFF12161F),
    onSurface = Color(0xFFE6E9F0),
    surfaceVariant = Color(0xFF1A2030),
    onSurfaceVariant = Color(0xFFA9B2C6),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A0505),
)

@Composable
fun LumaDepthTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LumaDepthColors,
        content = content,
    )
}
