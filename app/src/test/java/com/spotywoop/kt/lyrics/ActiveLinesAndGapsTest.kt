package com.spotywoop.kt.lyrics

import com.spotywoop.kt.lyrics.ui.computeLineHighlightEndTime
import com.spotywoop.kt.lyrics.ui.findAllInstrumentalGaps
import com.spotywoop.kt.lyrics.ui.findActiveLineIndices
import com.spotywoop.kt.lyrics.ui.findInstrumentalGapAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitaires JVM de la détection des lignes actives et des intervalles instrumentaux.
 */
class ActiveLinesAndGapsTest {

    private fun makeLine(
        start: Long,
        end: Long,
        text: String = "Test",
        background: List<Syllable> = emptyList(),
        alignment: String? = null,
    ): LyricsLine {
        return LyricsLine(
            text = listOf(Syllable(text = text, part = false, timestamp = start, endtime = end)),
            backgroundText = background,
            timestamp = start,
            endtime = end,
            alignment = alignment,
        )
    }

    @Test
    fun `test findActiveLineIndices normal sequence`() {
        val lyrics = listOf(
            makeLine(start = 1000L, end = 3000L, text = "Ligne 1"),
            makeLine(start = 3000L, end = 5000L, text = "Ligne 2"),
            makeLine(start = 5000L, end = 8000L, text = "Ligne 3"),
        )

        // Avant le début
        assertEquals(emptyList<Int>(), findActiveLineIndices(lyrics, 500L))

        // Pendant la ligne 1
        assertEquals(listOf(0), findActiveLineIndices(lyrics, 1500L))

        // Pendant la ligne 2
        assertEquals(listOf(1), findActiveLineIndices(lyrics, 3500L))

        // Pendant la ligne 3
        assertEquals(listOf(2), findActiveLineIndices(lyrics, 7000L))

        // Après la fin
        assertTrue(findActiveLineIndices(lyrics, 9000L).isEmpty())
    }

    @Test
    fun `test findActiveLineIndices overlapping duets`() {
        // Deux chanteurs qui chantent simultanément
        val lyrics = listOf(
            makeLine(start = 2000L, end = 6000L, text = "Chanteur A", alignment = "start"),
            makeLine(start = 3000L, end = 7000L, text = "Chanteur B", alignment = "end"),
        )

        // À 2500 ms : seul le chanteur A a commencé
        assertEquals(listOf(0), findActiveLineIndices(lyrics, 2500L))

        // À 4000 ms : les deux chanteurs sont actifs en même temps (duo simultané)
        val activeAtDuo = findActiveLineIndices(lyrics, 4000L)
        assertEquals(listOf(0, 1), activeAtDuo)

        // À 6500 ms : seul le chanteur B est encore actif
        assertEquals(listOf(1), findActiveLineIndices(lyrics, 6500L))
    }

    @Test
    fun `test computeLineHighlightEndTime with instrumental gap`() {
        val lyrics = listOf(
            // Ligne 1 se termine à 3000 ms, pause de 10 s avant la ligne 2 à 13 000 ms
            makeLine(start = 1000L, end = 3000L, text = "Avant pause"),
            makeLine(start = 13_000L, end = 16_000L, text = "Après pause"),
        )

        // Comme la pause fait 10 s (>= seuil instrumental 7 s), le surlignage s'arrête à la fin réelle (3000 ms)
        val end = computeLineHighlightEndTime(lyrics, 0)
        assertEquals(3000L, end)
    }

    @Test
    fun `test computeLineHighlightEndTime with background vocals exceeding main text`() {
        val bgSyllable = Syllable(text = "(chœur)", part = false, timestamp = 2000L, endtime = 4500L)
        val lyrics = listOf(
            makeLine(start = 1000L, end = 3000L, text = "Chant principal", background = listOf(bgSyllable)),
            makeLine(start = 5000L, end = 8000L, text = "Ligne suivante"),
        )

        // Le chœur dépasse le chant principal (4500 ms > 3000 ms), donc le surlignage s'étend jusqu'à la fin du chœur
        val end = computeLineHighlightEndTime(lyrics, 0)
        assertTrue(end >= 4500L)
    }

    @Test
    fun `test findAllInstrumentalGaps intro and inter-line`() {
        val lyrics = listOf(
            // Première ligne démarre à 9 s (pause intro >= 7 s)
            makeLine(start = 9000L, end = 12_000L, text = "Intro terminée"),
            // Pause courte de 2 s (12 s -> 14 s) : pas de gap
            makeLine(start = 14_000L, end = 17_000L, text = "Couplet"),
            // Pause solo de 8 s (17 s -> 25 s) : gap instrumental !
            makeLine(start = 25_000L, end = 28_000L, text = "Refrain"),
        )

        val gaps = findAllInstrumentalGaps(lyrics)
        assertEquals(2, gaps.size)

        // Gap d'intro
        assertEquals(0, gaps[0].insertBeforeIndex)
        assertEquals(0L, gaps[0].gapStart)
        assertEquals(9000L, gaps[0].gapEnd)

        // Gap de solo inter-lignes
        assertEquals(2, gaps[1].insertBeforeIndex)
        assertEquals(17_000L, gaps[1].gapStart)
        assertEquals(25_000L, gaps[1].gapEnd)
    }

    @Test
    fun `test findInstrumentalGapAt`() {
        val lyrics = listOf(
            makeLine(start = 10_000L, end = 14_000L, text = "Ligne 1"),
        )

        // À 5000 ms : à l'intérieur du gap d'intro (0 -> 10 000 ms)
        val gap = findInstrumentalGapAt(lyrics, 5000L)
        assertNotNull(gap)
        assertEquals(0, gap?.insertBeforeIndex)

        // À 12 000 ms : pendant le chant, aucun gap
        assertNull(findInstrumentalGapAt(lyrics, 12_000L))
    }
}
