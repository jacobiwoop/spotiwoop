package com.spotywoop.kt.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette relevée sur SpotiFLAC desktop : fond quasi noir, cartes à bord fin, accent or.
object SpotyColors {
    val Background = Color(0xFF0F0F0F)
    val Surface = Color(0xFF171717)
    val SurfaceHigh = Color(0xFF1F1F1F)
    val Border = Color(0xFF2A2A2A)
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFFA1A1A1)
    val TextMuted = Color(0xFF6B6B6B)
    val Gold = Color(0xFFE0B43A)
    val GoldDark = Color(0xFF6B5210)
    val SpotifyGreen = Color(0xFF1ED760)
    val Explicit = Color(0xFFDC2626)
}

private val scheme = darkColorScheme(
    primary = SpotyColors.Gold,
    onPrimary = Color.Black,
    background = SpotyColors.Background,
    onBackground = SpotyColors.TextPrimary,
    surface = SpotyColors.Surface,
    onSurface = SpotyColors.TextPrimary,
    surfaceVariant = SpotyColors.SurfaceHigh,
    onSurfaceVariant = SpotyColors.TextSecondary,
    outline = SpotyColors.Border,
    error = SpotyColors.Explicit,
)

@Composable
fun SpotywoopTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
