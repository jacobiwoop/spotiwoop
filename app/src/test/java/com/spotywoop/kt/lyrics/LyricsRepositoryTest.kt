package com.spotywoop.kt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des méthodes utilitaires et de sélection de source de LyricsRepository.
 */
class LyricsRepositoryTest {

    private fun makeDummyResult(source: String): LyricsSourceResult {
        val syl = Syllable(text = "test", part = false, timestamp = 1000L, endtime = 2000L, lineSynced = true)
        val line = LyricsLine(
            text = listOf(syl),
            timestamp = 1000L,
            endtime = 2000L,
            isWordSynced = false,
        )
        return LyricsSourceResult(lines = listOf(line), source = source)
    }

    @Test
    fun testCacheKey() {
        assertEquals("burna boy - last last", LyricsRepository.getCacheKey("  Burna Boy ", " Last Last  "))
    }

    @Test
    fun testFindAndNextSourceIndex() {
        val s1 = makeDummyResult("Apple Music")
        val s2 = makeDummyResult("BiniLyrics")
        val s3 = makeDummyResult("QQ")
        val sources = listOf(s1, s2, s3)

        // Recherche par identité
        assertEquals(0, LyricsRepository.findCurrentSourceIndex(sources, s1.lines, "Apple Music"))
        assertEquals(1, LyricsRepository.findCurrentSourceIndex(sources, s2.lines, "BiniLyrics"))

        // Recherche par label
        assertEquals(2, LyricsRepository.findCurrentSourceIndex(sources, emptyList(), "qq"))

        // Prochain index (cycle 0 -> 1 -> 2 -> 0)
        assertEquals(1, LyricsRepository.getNextSourceIndex(sources, 0, "Apple Music", s1.lines))
        assertEquals(2, LyricsRepository.getNextSourceIndex(sources, 1, "BiniLyrics", s2.lines))
        assertEquals(0, LyricsRepository.getNextSourceIndex(sources, 2, "QQ", s3.lines))
    }
}
