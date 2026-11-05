package com.spotywoop.kt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du classement, de la comparaison et de la fusion/tri des sources de paroles.
 * Règles identiques à AmLyrics.getTimingRank, getRankForCollected, compareSources et mergeAndSortSources.
 */
class SourceRankingTest {

    private fun makeWordLine(timestamp: Long = 1000L): LyricsLine = LyricsLine(
        text = listOf(
            Syllable(text = "Un ", part = false, timestamp = timestamp, endtime = timestamp + 200L),
            Syllable(text = "mot", part = false, timestamp = timestamp + 200L, endtime = timestamp + 500L),
        ),
        timestamp = timestamp,
        endtime = timestamp + 500L,
        isWordSynced = true,
    )

    private fun makeLineSyncedLine(timestamp: Long = 1000L): LyricsLine = LyricsLine(
        text = listOf(
            Syllable(text = "Ligne entière", part = false, timestamp = timestamp, endtime = timestamp + 2000L, lineSynced = true),
        ),
        timestamp = timestamp,
        endtime = timestamp + 2000L,
        isWordSynced = false,
    )

    private fun makePlainLine(): LyricsLine = LyricsLine(
        text = listOf(
            Syllable(text = "Paroles brutes", part = false, timestamp = 0L, endtime = 0L),
        ),
        timestamp = 0L,
        endtime = 0L,
        isWordSynced = false,
    )

    @Test
    fun testTimingRank() {
        // Rang 0 : mot par mot
        assertEquals(0, LyricsProviders.getTimingRank(listOf(makeWordLine())))

        // Rang 1 : synchronisé ligne par ligne
        assertEquals(1, LyricsProviders.getTimingRank(listOf(makeLineSyncedLine())))

        // Rang 2 : texte brut non synchronisé
        assertEquals(2, LyricsProviders.getTimingRank(listOf(makePlainLine())))
    }

    @Test
    fun testRankForCollected() {
        assertEquals(0.0, LyricsProviders.getRankForCollected("lrc.red"), 0.001)
        assertEquals(1.0, LyricsProviders.getRankForCollected("Apple Music"), 0.001)
        assertEquals(2.0, LyricsProviders.getRankForCollected("BiniLyrics"), 0.001)
        assertEquals(2.5, LyricsProviders.getRankForCollected("Spicy Lyrics (Apple Music)"), 0.001)
        assertEquals(2.5, LyricsProviders.getRankForCollected("Spicy Lyrics"), 0.001)
        assertEquals(3.0, LyricsProviders.getRankForCollected("Unison"), 0.001)
        assertEquals(4.0, LyricsProviders.getRankForCollected("QQ"), 0.001)
        assertEquals(4.0, LyricsProviders.getRankForCollected("LyricsPlus (KPoe)"), 0.001)
        assertEquals(5.0, LyricsProviders.getRankForCollected("Musixmatch"), 0.001)
        assertEquals(6.0, LyricsProviders.getRankForCollected("LRCLIB"), 0.001)
        assertEquals(8.0, LyricsProviders.getRankForCollected("Genius"), 0.001)
        assertEquals(7.0, LyricsProviders.getRankForCollected("Autre source"), 0.001)
    }

    @Test
    fun testCompareSources() {
        val wordApple = LyricsSourceResult(lines = listOf(makeWordLine()), source = "Apple Music", byModel = false)
        val lineLrcRed = LyricsSourceResult(lines = listOf(makeLineSyncedLine()), source = "lrc.red", byModel = false)
        val plainLrcLib = LyricsSourceResult(lines = listOf(makePlainLine()), source = "LRCLIB", byModel = false)

        // Le mot par mot bat toujours le ligne par ligne, même si lrc.red a un rang de source 0
        assertTrue(LyricsProviders.compareSources(wordApple, lineLrcRed) < 0)

        // Le ligne par ligne bat le brut
        assertTrue(LyricsProviders.compareSources(lineLrcRed, plainLrcLib) < 0)

        // À même niveau de synchronisation, l'humain bat le modèle IA
        val wordModel = LyricsSourceResult(lines = listOf(makeWordLine()), source = "lrc.red", byModel = true)
        val wordHuman = LyricsSourceResult(lines = listOf(makeWordLine()), source = "Unison", byModel = false)
        assertTrue(LyricsProviders.compareSources(wordHuman, wordModel) < 0)

        // À même niveau de synchro et humain, l'ordre des sources s'applique (Apple 1 < Bini 2 < Spicy 2.5)
        val wordBini = LyricsSourceResult(lines = listOf(makeWordLine()), source = "BiniLyrics", byModel = false)
        assertTrue(LyricsProviders.compareSources(wordApple, wordBini) < 0)
    }

    @Test
    fun testMergeAndSortSources() {
        val s1 = LyricsSourceResult(lines = listOf(makePlainLine()), source = "LyricsPlus (KPoe)")
        val s2 = LyricsSourceResult(lines = listOf(makeLineSyncedLine()), source = "QQ")
        val s3 = LyricsSourceResult(lines = listOf(makeWordLine()), source = "Apple Music")
        val s4 = LyricsSourceResult(lines = listOf(makeLineSyncedLine()), source = "LRCLIB")

        // s1 et s2 ont la même clé ("qq"). s2 (ligne synchro) doit écraser s1 (brut) et être affiché "QQ"
        val merged = LyricsProviders.mergeAndSortSources(listOf(s1, s4, s2, s3))

        assertEquals(3, merged.size)
        // 1er : Apple Music (mot par mot)
        assertEquals("Apple Music", merged[0].source)
        // 2e : QQ (ligne synchronisée, meilleur que LRCLIB)
        assertEquals("QQ", merged[1].source)
        // 3e : LRCLIB (ligne synchronisée)
        assertEquals("LRCLIB", merged[2].source)
    }
}
