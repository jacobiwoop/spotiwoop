package com.spotywoop.kt.lyrics

import com.spotywoop.kt.lyrics.ui.BackgroundPlacement
import com.spotywoop.kt.lyrics.ui.CharacterMotionMode
import com.spotywoop.kt.lyrics.ui.characterMotionMode
import com.spotywoop.kt.lyrics.ui.getBackgroundTextPlacement
import com.spotywoop.kt.lyrics.ui.groupSyllablesIntoWords
import com.spotywoop.kt.lyrics.ui.sampleSpring
import com.spotywoop.kt.lyrics.ui.springProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitaires JVM de la logique de progression des mots, découpage en mots et ressort physique.
 */
class WordWipeLogicTest {

    @Test
    fun `test characterMotionMode rules`() {
        // Mot court en durée (< 1 s) -> NONE
        assertEquals(CharacterMotionMode.NONE, characterMotionMode("Hello", 800L))

        // Mot long en durée (>= 1 s) et de 1 à 7 caractères -> EMPHASIS
        assertEquals(CharacterMotionMode.EMPHASIS, characterMotionMode("Tonight", 1200L))
        assertEquals(CharacterMotionMode.EMPHASIS, characterMotionMode("Stay", 2500L))

        // Mot de plus de 7 caractères -> NONE
        assertEquals(CharacterMotionMode.NONE, characterMotionMode("Beautifully", 1500L))

        // Chœurs : pas d'emphasis
        assertEquals(CharacterMotionMode.NONE, characterMotionMode("Stay", 2500L, background = true))

        // Idéogrammes CJK (>= 2 caractères) -> RISE
        assertEquals(CharacterMotionMode.RISE, characterMotionMode("東京", 1200L))

        // Écritures RTL (Hébreu) -> NONE
        assertEquals(CharacterMotionMode.NONE, characterMotionMode("שלום", 2000L))
    }

    @Test
    fun `test groupSyllablesIntoWords whitespace grouping`() {
        val syllables = listOf(
            Syllable(text = "Hello ", part = false, timestamp = 1000L, endtime = 1400L),
            Syllable(text = "world", part = false, timestamp = 1500L, endtime = 2200L),
            Syllable(text = "!", part = false, timestamp = 2200L, endtime = 2500L),
        )

        val words = groupSyllablesIntoWords(syllables, linePrefix = "test-1")
        assertEquals(2, words.size)

        // Premier mot : "Hello "
        assertEquals("Hello ", words[0].text)
        assertEquals(1000L, words[0].startMs)
        assertEquals(1400L, words[0].endMs)
        assertEquals(400L, words[0].durationMs)

        // Deuxième mot regroupé : "world" + "!" (pas d'espace entre les deux)
        assertEquals("world!", words[1].text)
        assertEquals(1500L, words[1].startMs)
        assertEquals(2500L, words[1].endMs)
        assertEquals(1000L, words[1].durationMs)
        assertEquals(CharacterMotionMode.EMPHASIS, words[1].mode)

        // Pré-remplissage borné entre 80 et 240 ms
        assertTrue(words[1].preWipeDurationMs in 80L..240L)
    }

    @Test
    fun `test getBackgroundTextPlacement before and after`() {
        // Chœur qui démarre avant le chant principal
        val lineBefore = LyricsLine(
            text = listOf(Syllable(text = "Main", part = false, timestamp = 2000L, endtime = 3000L)),
            backgroundText = listOf(Syllable(text = "(Intro)", part = false, timestamp = 1000L, endtime = 1800L)),
            timestamp = 1000L,
            endtime = 3000L,
        )
        assertEquals(BackgroundPlacement.BEFORE, getBackgroundTextPlacement(lineBefore))

        // Chœur qui démarre après ou en même temps
        val lineAfter = LyricsLine(
            text = listOf(Syllable(text = "Main", part = false, timestamp = 1000L, endtime = 2000L)),
            backgroundText = listOf(Syllable(text = "(Echo)", part = false, timestamp = 1500L, endtime = 2500L)),
            timestamp = 1000L,
            endtime = 2500L,
        )
        assertEquals(BackgroundPlacement.AFTER, getBackgroundTextPlacement(lineAfter))
    }

    @Test
    fun `test springProgress step response`() {
        // Au temps 0 : 0
        assertEquals(0f, springProgress(0f, 1f), 0.001f)

        // Progression monotone
        val p1 = springProgress(0.2f, 1f)
        val p2 = springProgress(0.5f, 1f)
        val p3 = springProgress(1.0f, 1f)
        val pFinal = springProgress(4.0f, 1f)

        assertTrue("p1 <= p2", p1 <= p2)
        assertTrue("p2 <= p3", p2 <= p3)
        assertTrue("pFinal proche de 1", abs(pFinal - 1f) < 0.05f)
    }

    @Test
    fun `test sampleSpring damped oscillator`() {
        // Échantillon au repos à t=0
        val (pos0, vel0) = sampleSpring(position = 1f, velocity = 0f, timeSec = 0f, frequency = 10f)
        assertEquals(1f, pos0, 0.001f)
        assertEquals(0f, vel0, 0.001f)

        // Après amortissement (t = 1.0 s) : retour vers l'équilibre
        val (pos1, _) = sampleSpring(position = 1f, velocity = 0f, timeSec = 1.0f, frequency = 10f)
        assertTrue("Position amortie proche de 0", abs(pos1) < 0.05f)
    }

    private fun abs(value: Float): Float = if (value < 0f) -value else value
}
