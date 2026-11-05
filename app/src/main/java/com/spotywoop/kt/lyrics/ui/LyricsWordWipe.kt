package com.spotywoop.kt.lyrics.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotywoop.kt.lyrics.Syllable
import kotlin.math.max
import kotlin.math.min

/**
 * Composant de mot avec remplissage progressif (wipe dégradé) et physique d'élévation lettre par lettre.
 *
 * Implémente exactement les règles de AmLyrics.ts :
 * - `startWordWipe` : balayage continu par dégradé avec plume (progression feather) ;
 * - `maybePreWipeNextWord` : pré-remplissage du mot suivant dès la fin du mot précédent ;
 * - `updateCharacterMotion` : élévation verticale et amortissement critique ([springProgress]) sur les mots longs ;
 * - lueur dorée/blanche ([LyricsUiConstants.GLOW_RADIUS_DP]) ;
 * - compatibilité RTL (sens de défilement inversé) ;
 * - exécution 100% dans la phase de dessin (`drawWithContent`) pour garantir 60 fps sans recomposition.
 */
@Composable
fun LyricsWordItem(
    word: WordUiData,
    clock: PlaybackClock,
    isActiveLine: Boolean,
    isBackground: Boolean,
    fontSize: TextUnit,
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val inactiveColor = if (isBackground) {
        Color(0xFFB0B0B0).copy(alpha = 0.50f)
    } else {
        Color.White.copy(alpha = 0.55f)
    }

    val activeColor = if (isBackground) {
        Color(0xFFE0E0E0)
    } else {
        Color.White
    }

    val baseStyle = TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    )

    // Si le mot est en mode EMPHASIS et que le mouvement n'est pas réduit : rendu lettre par lettre avec élévation
    if (!reducedMotion && word.mode == CharacterMotionMode.EMPHASIS && word.charCount in 2..7) {
        EmphasisWord(
            word = word,
            clock = clock,
            isActiveLine = isActiveLine,
            inactiveColor = inactiveColor,
            activeColor = activeColor,
            baseStyle = baseStyle,
            modifier = modifier,
        )
    } else {
        StandardWord(
            word = word,
            clock = clock,
            isActiveLine = isActiveLine,
            inactiveColor = inactiveColor,
            activeColor = activeColor,
            baseStyle = baseStyle,
            reducedMotion = reducedMotion,
            modifier = modifier,
        )
    }
}

/**
 * Mot standard balayé par un dégradé continu avec plume (feather).
 */
@Composable
private fun StandardWord(
    word: WordUiData,
    clock: PlaybackClock,
    isActiveLine: Boolean,
    inactiveColor: Color,
    activeColor: Color,
    baseStyle: TextStyle,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .graphicsLayer {
                if (!reducedMotion && isActiveLine && clock.timeMs >= word.startMs) {
                    // Légère élévation de base pour les mots démarrés (--char-rise-y * lift)
                    translationY = LyricsUiConstants.CHAR_RISE_PEAK_DP.dp.toPx()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Couche de base (texte inactif)
        Text(
            text = word.text,
            color = inactiveColor,
            style = baseStyle,
        )

        // Couche active masquée par le dégradé du wipe
        Text(
            text = word.text,
            color = activeColor,
            style = baseStyle,
            modifier = Modifier
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()

                    val t = clock.timeMs
                    val preWipeStart = max(0L, word.startMs - word.preWipeDurationMs)

                    when {
                        !isActiveLine || t < preWipeStart -> {
                            // Non commencé : tout masquer
                            drawRect(
                                color = Color.Transparent,
                                blendMode = BlendMode.DstIn,
                            )
                        }
                        t >= word.endMs -> {
                            // Entièrement achevé : 100% visible
                            // Pas de masque requis, le texte actif est intégralement affiché
                        }
                        else -> {
                            // Wipe actif en cours de progression
                            val featherPx = LyricsUiConstants.PROGRESSION_FEATHER_DP.dp.toPx()
                            val totalSpan = max(1L, word.endMs - preWipeStart)
                            val progress = ((t - preWipeStart).toFloat() / totalSpan).coerceIn(0f, 1f)

                            val totalWidth = size.width
                            val edgeX = if (word.isRtl) {
                                totalWidth * (1f - progress)
                            } else {
                                (totalWidth + featherPx) * progress - featherPx / 2f
                            }

                            val brush = if (word.isRtl) {
                                Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, Color.White),
                                    startX = (edgeX - featherPx).coerceAtLeast(0f),
                                    endX = edgeX.coerceAtMost(totalWidth),
                                )
                            } else {
                                Brush.horizontalGradient(
                                    colors = listOf(Color.White, Color.Transparent),
                                    startX = (edgeX - featherPx).coerceAtLeast(0f),
                                    endX = edgeX.coerceAtMost(totalWidth),
                                )
                            }

                            // Zone entièrement allumée avant la plume
                            if (!word.isRtl && edgeX - featherPx > 0f) {
                                drawRect(
                                    color = Color.White,
                                    topLeft = Offset.Zero,
                                    size = Size(edgeX - featherPx, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            } else if (word.isRtl && edgeX < totalWidth) {
                                drawRect(
                                    color = Color.White,
                                    topLeft = Offset(edgeX, 0f),
                                    size = Size(totalWidth - edgeX, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }

                            // Zone de plume avec dégradé doux
                            val plumeStart = (edgeX - featherPx).coerceAtLeast(0f)
                            val plumeWidth = min(featherPx, totalWidth - plumeStart)
                            if (plumeWidth > 0f) {
                                drawRect(
                                    brush = brush,
                                    topLeft = Offset(plumeStart, 0f),
                                    size = Size(plumeWidth, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }

                            // Zone non encore allumée après la plume
                            if (!word.isRtl && edgeX < totalWidth) {
                                drawRect(
                                    color = Color.Transparent,
                                    topLeft = Offset(edgeX, 0f),
                                    size = Size(totalWidth - edgeX, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            } else if (word.isRtl && edgeX - featherPx > 0f) {
                                drawRect(
                                    color = Color.Transparent,
                                    topLeft = Offset.Zero,
                                    size = Size(edgeX - featherPx, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                    }
                },
        )
    }
}

/**
 * Mot long en mode EMPHASIS avec élévation dynamique de chaque caractère (springProgress) et lueur.
 */
@Composable
private fun EmphasisWord(
    word: WordUiData,
    clock: PlaybackClock,
    isActiveLine: Boolean,
    inactiveColor: Color,
    activeColor: Color,
    baseStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val durationSec = word.durationMs / 1000f
    val count = max(1, word.charCount)
    val staggerMs = ((durationSec * 1000f) / count) * 0.35f
    val delayMs = min(staggerMs, 350f)
    val response = min(3f, durationSec)
    val hold = (2f * durationSec) / count
    val emphasis = (durationSec - 1f).coerceIn(0f, 1f)
    val glowIntensity = if (emphasis > 0f) 0.45f * ((durationSec - 1f) / 0.5f).coerceIn(0f, 1f) else 0f

    val chars = word.text.toList()

    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        var visibleCharIdx = 0
        chars.forEach { ch ->
            val isWhitespace = ch.isWhitespace()
            val charIdx = if (!isWhitespace) visibleCharIdx++ else 0

            Box(
                modifier = Modifier.graphicsLayer {
                    if (isActiveLine && !isWhitespace) {
                        val t = clock.timeMs
                        val startDelay = (charIdx + 1) * delayMs
                        val charElapsedSec = (t - word.startMs - startDelay) / 1000f

                        if (charElapsedSec > 0f) {
                            val rise = springProgress(charElapsedSec, response)
                            val yRise = LyricsUiConstants.CHAR_RISE_Y_DP.dp.toPx() * rise
                            translationY = yRise
                        }
                    }
                },
                contentAlignment = Alignment.CenterStart,
            ) {
                // Inactif
                Text(
                    text = ch.toString(),
                    color = inactiveColor,
                    style = baseStyle,
                )

                // Actif avec wipe et lueur
                Text(
                    text = ch.toString(),
                    color = activeColor,
                    style = if (glowIntensity > 0f) {
                        baseStyle.copy(
                            shadow = Shadow(
                                color = Color.White.copy(alpha = glowIntensity),
                                blurRadius = LyricsUiConstants.GLOW_RADIUS_DP.dp.value,
                            ),
                        )
                    } else baseStyle,
                    modifier = Modifier
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            val t = clock.timeMs
                            val preWipeStart = max(0L, word.startMs - word.preWipeDurationMs)

                            if (!isActiveLine || t < preWipeStart) {
                                drawRect(Color.Transparent, blendMode = BlendMode.DstIn)
                            } else if (t >= word.endMs) {
                                drawContent()
                            } else {
                                drawContent()
                                val progress = ((t - preWipeStart).toFloat() / max(1L, word.endMs - preWipeStart)).coerceIn(0f, 1f)
                                val charStartRatio = charIdx.toFloat() / count
                                val charEndRatio = (charIdx + 1).toFloat() / count

                                if (progress < charStartRatio) {
                                    drawRect(Color.Transparent, blendMode = BlendMode.DstIn)
                                } else if (progress < charEndRatio) {
                                    val subProgress = (progress - charStartRatio) / (charEndRatio - charStartRatio)
                                    val brush = Brush.horizontalGradient(
                                        colors = listOf(Color.White, Color.Transparent),
                                        startX = 0f,
                                        endX = size.width * (subProgress + 0.3f),
                                    )
                                    drawRect(brush = brush, blendMode = BlendMode.DstIn)
                                }
                            }
                        },
                )
            }
        }
    }
}
