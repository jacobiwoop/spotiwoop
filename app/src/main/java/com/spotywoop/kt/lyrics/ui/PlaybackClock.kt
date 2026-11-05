package com.spotywoop.kt.lyrics.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * Horloge de lecture fluide pour le défilement et l'animation des paroles mot par mot.
 *
 * L'application ne fournit la position du lecteur que périodiquement (~500 ms).
 * Cette horloge interpole image par image à 60+ images/s avec [withFrameNanos],
 * se recale à chaque mise à jour externe, et détecte immédiatement les sauts (seeks).
 */
@Stable
interface PlaybackClock {
    /** Position actuelle en millisecondes (Long). */
    val timeMs: Long

    /** Position actuelle en millisecondes à virgule flottante (Float). */
    val timeFloatMs: Float

    /** Saut direct vers une position donnée. */
    fun seekTo(targetTimeMs: Long)
}

/**
 * Logique pure d'interpolation temporelle, découplée du cycle de vie Compose pour les tests unitaires JVM.
 */
class PlaybackClockState(initialMs: Long = 0L) : PlaybackClock {

    private var currentEstimatedMs: Float = initialMs.toFloat()

    override var timeMs by mutableLongStateOf(initialMs)
        private set

    override var timeFloatMs by mutableFloatStateOf(initialMs.toFloat())
        private set

    /**
     * Mise à jour périodique depuis le lecteur audio (typiquement toutes les 500 ms).
     */
    fun onPositionUpdate(newPositionMs: Long, isPlaying: Boolean) {
        val diff = abs(newPositionMs - currentEstimatedMs)
        if (diff > SEEK_THRESHOLD_MS || !isPlaying) {
            // Saut franc (seek) ou lecteur en pause : calage immédiat
            currentEstimatedMs = newPositionMs.toFloat()
            timeMs = newPositionMs
            timeFloatMs = newPositionMs.toFloat()
        } else {
            // Dérive légère pendant la lecture normale : rattrapage progressif pour éviter les à-coups
            currentEstimatedMs = currentEstimatedMs * 0.25f + newPositionMs.toFloat() * 0.75f
            timeMs = currentEstimatedMs.toLong()
            timeFloatMs = currentEstimatedMs
        }
    }

    /**
     * Avancement d'une image (nanosecondes écoulées).
     */
    fun onFrame(deltaNanos: Long, isPlaying: Boolean) {
        if (!isPlaying) return
        val deltaMs = deltaNanos / 1_000_000f
        currentEstimatedMs += deltaMs
        timeMs = currentEstimatedMs.toLong()
        timeFloatMs = currentEstimatedMs
    }

    override fun seekTo(targetTimeMs: Long) {
        currentEstimatedMs = targetTimeMs.toFloat()
        timeMs = targetTimeMs
        timeFloatMs = targetTimeMs.toFloat()
    }

    companion object {
        /** Seuil au-delà duquel un écart de temps est considéré comme un saut manuel (seek). */
        const val SEEK_THRESHOLD_MS = 500L
    }
}

/**
 * Crée et mémorise une [PlaybackClock] interpolant la position de lecture à chaque image.
 */
@Composable
fun rememberPlaybackClock(
    positionMs: Long,
    isPlaying: Boolean,
): PlaybackClock {
    val clock = remember { PlaybackClockState(positionMs) }

    // Synchronisation lors des changements d'état du lecteur ou de saut de position
    LaunchedEffect(positionMs, isPlaying) {
        clock.onPositionUpdate(positionMs, isPlaying)
    }

    // Boucle d'interpolation image par image
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (isActive) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameNanos != 0L) {
                    val deltaNanos = frameTimeNanos - lastFrameNanos
                    // Plafond à 100 ms pour éviter un bond anormal après suspension
                    val clampedDelta = minOf(deltaNanos, 100_000_000L)
                    clock.onFrame(clampedDelta, isPlaying)
                }
                lastFrameNanos = frameTimeNanos
            }
        }
    }

    return clock
}
