package com.spotywoop.kt.lyrics

import com.spotywoop.kt.lyrics.ui.PlaybackClockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests unitaires JVM de l'horloge fluide PlaybackClockState.
 */
class PlaybackClockTest {

    @Test
    fun `test initial state`() {
        val clock = PlaybackClockState(initialMs = 1200L)
        assertEquals(1200L, clock.timeMs)
        assertEquals(1200f, clock.timeFloatMs, 0.01f)
    }

    @Test
    fun `test frame interpolation advances time when playing`() {
        val clock = PlaybackClockState(initialMs = 5000L)

        // Simule 60 images par seconde (16.666 ms par image)
        val oneFrameNanos = 16_666_667L
        for (i in 1..60) {
            clock.onFrame(oneFrameNanos, isPlaying = true)
        }

        // En 60 images, environ 1000 ms écoulées
        val diff = abs(clock.timeMs - 6000L)
        assertTrue("Après 60 frames, l'horloge devrait être proche de 6000 ms (actuel: ${clock.timeMs})", diff <= 10L)
    }

    @Test
    fun `test frame interpolation does not advance when paused`() {
        val clock = PlaybackClockState(initialMs = 5000L)
        clock.onFrame(16_666_667L * 30, isPlaying = false)
        assertEquals(5000L, clock.timeMs)
    }

    @Test
    fun `test soft sync on normal playback update`() {
        val clock = PlaybackClockState(initialMs = 5000L)
        // Écoulement d'environ 450 ms
        clock.onFrame(450_000_000L, isPlaying = true)
        val beforeUpdate = clock.timeMs

        // Mise à jour de position périodique du lecteur (dérive faible de 50 ms)
        clock.onPositionUpdate(newPositionMs = 5500L, isPlaying = true)

        // Lissage progressif sans rupture violente
        assertTrue(clock.timeMs in beforeUpdate..5500L)
    }

    @Test
    fun `test hard snap on seek`() {
        val clock = PlaybackClockState(initialMs = 2000L)

        // Saut en avant de 10 s (> seuil 500 ms)
        clock.onPositionUpdate(newPositionMs = 12_000L, isPlaying = true)
        assertEquals(12_000L, clock.timeMs)

        // Saut en arrière
        clock.onPositionUpdate(newPositionMs = 1000L, isPlaying = true)
        assertEquals(1000L, clock.timeMs)
    }

    @Test
    fun `test direct seekTo call`() {
        val clock = PlaybackClockState(initialMs = 1000L)
        clock.seekTo(42_500L)
        assertEquals(42_500L, clock.timeMs)
        assertEquals(42_500f, clock.timeFloatMs, 0.01f)
    }
}
