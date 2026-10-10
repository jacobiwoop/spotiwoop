package com.spotywoop.kt.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.spotywoop.kt.R

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
    // Vert du logo, celui du site web.
    val Accent = Color(0xFF95E800)
    val Up = Color(0xFF1ED760)
    val Down = Color(0xFFFF6B6B)
}

// Polices du site : Outfit pour les titres, DM Sans pour le texte (polices variables).
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: FontWeight) = FontFamily(
    weights.map { w -> Font(res, weight = w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight))) },
)

object SpotyFonts {
    val Display = variable(R.font.outfit, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)
    val Text = variable(R.font.dm_sans, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
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
