package com.spotywoop.kt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des fonctions d'export (generateLRC, generatePlain, generateTTML, formatTimestamp).
 */
class LyricsExportTest {

    private fun makeSampleLines(): List<LyricsLine> {
        val syl1 = Syllable(text = "Hello ", part = false, timestamp = 1500L, endtime = 2200L)
        val syl2 = Syllable(text = "world", part = false, timestamp = 2200L, endtime = 3100L)
        val bg1 = Syllable(text = "world", part = false, timestamp = 2500L, endtime = 3200L)

        val line1 = LyricsLine(
            text = listOf(syl1, syl2),
            background = true,
            backgroundText = listOf(bg1),
            timestamp = 1500L,
            endtime = 3200L,
            songPart = "Chorus",
            isWordSynced = true,
        )

        val syl3 = Syllable(text = "Second line", part = false, timestamp = 5000L, endtime = 8000L, lineSynced = true)
        val line2 = LyricsLine(
            text = listOf(syl3),
            background = false,
            timestamp = 5000L,
            endtime = 8000L,
            songPart = "Chorus",
            isWordSynced = false,
        )

        return listOf(line1, line2)
    }

    @Test
    fun testTimestampFormatting() {
        // LRC : mm:ss.xx
        assertEquals("00:01.50", LyricsExport.formatTimestampLRC(1500L))
        assertEquals("01:15.28", LyricsExport.formatTimestampLRC(75_280L))

        // TTML : HH:MM:SS.mmm
        assertEquals("00:00:01.500", LyricsExport.formatTimestampTTML(1500L))
        assertEquals("00:01:15.280", LyricsExport.formatTimestampTTML(75_280L))
        assertEquals("01:00:00.000", LyricsExport.formatTimestampTTML(3_600_000L))
    }

    @Test
    fun testGenerateLRC() {
        val lines = makeSampleLines()
        val lrc = LyricsExport.generateLRC(
            lines = lines,
            title = "My Song",
            artist = "My Artist",
            album = "My Album",
            source = "Test Source",
        )

        assertTrue(lrc.contains("[ti:My Song]\n"))
        assertTrue(lrc.contains("[ar:My Artist]\n"))
        assertTrue(lrc.contains("[al:My Album]\n"))
        assertTrue(lrc.contains("[re:Test Source]\n"))
        assertTrue(lrc.contains("[00:01.50]Hello world\n"))
        assertTrue(lrc.contains("[00:05.00]Second line\n"))
    }

    @Test
    fun testGeneratePlain() {
        val lines = makeSampleLines()
        val plain = LyricsExport.generatePlain(lines)

        val plainLines = plain.lines()
        // line1 a des chœurs commençant à 2500ms > main 1500ms -> après le texte principal
        assertEquals("Hello world", plainLines[0])
        assertEquals("(world)", plainLines[1])
        assertEquals("Second line", plainLines[2])
    }

    @Test
    fun testGenerateTTML() {
        val lines = makeSampleLines()
        val ttml = LyricsExport.generateTTML(lines)

        assertTrue(ttml.contains("""<tt xmlns="http://www.w3.org/ns/ttml""""))
        assertTrue(ttml.contains("""<div itunes:song-part="Chorus">"""))
        assertTrue(ttml.contains("""<p begin="00:00:01.500" end="00:00:03.200">"""))
        assertTrue(ttml.contains("""<span begin="00:00:01.500" end="00:00:02.200">Hello </span>"""))
        assertTrue(ttml.contains("""<span begin="00:00:02.200" end="00:00:03.100">world</span>"""))
    }
}
