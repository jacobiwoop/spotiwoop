package com.spotywoop.kt.lyrics.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Rangée d'intervalle instrumental avec les trois points qui respirent (AmLyrics updateInstrumentalGap).
 *
 * S'anime selon l'horloge de lecture avec :
 * - une respiration sinusoïdale (échelle entre 0,85 et 1,12 sur une période de 4 s) ;
 * - un éclairage séquentiel des trois points au fil de la pause ;
 * - un rebond (pop à 1,2) puis un effondrement fluide juste avant la reprise du chant.
 */
@Composable
fun InstrumentalGapRow(
    gap: InstrumentalGap,
    clock: PlaybackClock,
    modifier: Modifier = Modifier,
) {
    val currentTime = clock.timeMs
    val isActive = currentTime in gap.gapStart until gap.gapEnd

    // Hauteur animée lors de l'apparition et de la disparition
    val targetHeight = if (isActive) 56.dp else 0.dp
    val animatedHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = tween(LyricsUiConstants.SCROLL_ANIMATION_DURATION_MS.toInt()),
        label = "gap_height",
    )

    if (animatedHeight <= 0.dp && !isActive) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(animatedHeight)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer {
                    val t = clock.timeMs
                    val elapsed = (t - gap.gapStart).coerceAtLeast(0L)
                    val duration = gap.durationMs
                    val collapseLead = LyricsUiConstants.GAP_COLLAPSE_LEAD_MS
                    val exitLead = LyricsUiConstants.GAP_EXIT_LEAD_MS
                    val exitStart = max(
                        LyricsUiConstants.GAP_ENTRY_SCALE_MS,
                        duration - collapseLead - exitLead,
                    )
                    val end = min(duration, exitStart + exitLead)

                    val cycle = LyricsUiConstants.GAP_PULSE_DURATION_MS * 2
                    val phase = (elapsed + LyricsUiConstants.GAP_PULSE_DURATION_MS - (exitStart % cycle) + cycle) % cycle
                    val mix = (1f - cos((Math.PI.toFloat() * phase) / LyricsUiConstants.GAP_PULSE_DURATION_MS)) / 2f
                    var scale = LyricsUiConstants.GAP_BREATH_MAX_SCALE +
                        (LyricsUiConstants.GAP_BREATH_MIN_SCALE - LyricsUiConstants.GAP_BREATH_MAX_SCALE) * mix

                    // Fondu et montée d'entrée
                    if (elapsed < LyricsUiConstants.GAP_ENTRY_SCALE_MS) {
                        val entryRatio = (elapsed.toFloat() / LyricsUiConstants.GAP_ENTRY_SCALE_MS).coerceIn(0f, 1f)
                        scale *= entryRatio
                    }

                    var op = (elapsed.toFloat() / LyricsUiConstants.GAP_ENTRY_FADE_MS).coerceIn(0f, 1f)

                    // Rebond (pop) et effondrement à la sortie
                    if (elapsed >= exitStart) {
                        val progress = (elapsed - exitStart).toFloat() / max(1f, (end - exitStart).toFloat())
                        val popProgress = LyricsUiConstants.GAP_EXIT_POP_PROGRESS
                        if (progress <= popProgress) {
                            val phaseProgress = progress / popProgress
                            val ease = phaseProgress * phaseProgress * (3f - 2f * phaseProgress)
                            scale = LyricsUiConstants.GAP_BREATH_MIN_SCALE +
                                (LyricsUiConstants.GAP_EXIT_POP_SCALE - LyricsUiConstants.GAP_BREATH_MIN_SCALE) * ease
                            op = 1f
                        } else {
                            val phaseProgress = (progress - popProgress) / (1f - popProgress)
                            val ease = phaseProgress * phaseProgress * (3f - 2f * phaseProgress)
                            scale = LyricsUiConstants.GAP_EXIT_POP_SCALE * (1f - ease)
                            op = 1f - ease
                        }
                    }

                    scaleX = scale
                    scaleY = scale
                    alpha = op
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { dotIndex ->
                InstrumentalDot(
                    dotIndex = dotIndex,
                    gap = gap,
                    clock = clock,
                )
            }
        }
    }
}

@Composable
private fun InstrumentalDot(
    dotIndex: Int,
    gap: InstrumentalGap,
    clock: PlaybackClock,
) {
    Box(
        modifier = Modifier
            .size(13.dp)
            .graphicsLayer {
                val t = clock.timeMs
                val elapsed = (t - gap.gapStart).coerceAtLeast(0L)
                val duration = gap.durationMs
                val collapseLead = LyricsUiConstants.GAP_COLLAPSE_LEAD_MS
                val exitLead = LyricsUiConstants.GAP_EXIT_LEAD_MS
                val exitStart = max(
                    LyricsUiConstants.GAP_ENTRY_SCALE_MS,
                    duration - collapseLead - exitLead,
                )

                val dotDuration = exitStart / 3f
                val dotStart = dotIndex * dotDuration
                val dotElapsed = elapsed - dotStart

                val dotProgress = if (dotDuration > 0f) {
                    (dotElapsed / dotDuration).coerceIn(0f, 1f)
                } else 1f

                // Opacité de 0,25 à 1,0 au fur et à mesure que le temps s'écoule
                alpha = 0.25f + 0.75f * dotProgress
            }
            .clip(CircleShape)
            .background(Color.White),
    )
}
