package com.spotywoop.kt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test d'un document TTML minimal avec chœur (x-bg) et duo de chanteurs (alignement start/end).
 */
class TTMLMinimalTest {

    @Test
    fun testTTMLMinimalWithChoirAndDuo() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyrics">
              <head>
                <metadata>
                  <ttm:agent xml:id="v1" type="person"/>
                  <ttm:agent xml:id="v2" type="person"/>
                  <songwriter>Damso</songwriter>
                  <songwriter>Angèle</songwriter>
                </metadata>
              </head>
              <body>
                <div itunes:song-part="Verse">
                  <p begin="00:01.000" end="00:04.000" ttm:agent="v1">
                    <span begin="00:01.000" end="00:02.000">Première </span>
                    <span begin="00:02.000" end="00:04.000">voix</span>
                    <span ttm:role="x-bg">
                      <span begin="00:02.500" end="00:03.500">écho</span>
                    </span>
                  </p>
                  <p begin="00:05.000" end="00:08.000" ttm:agent="v2">
                    <span begin="00:05.000" end="00:06.000">Deuxième </span>
                    <span begin="00:06.000" end="00:08.000">voix</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsed = LyricsParsers.parseTTML(ttml)
        assertNotNull(parsed)
        val lines = parsed!!.lines
        assertEquals(2, lines.size)

        // Auteurs
        assertEquals("Damso, Angèle", parsed.songwriters)

        // Ligne 1 : chanteur v1 (start, pas oppositeTurn) avec chœurs
        val line1 = lines[0]
        assertEquals("Verse", line1.songPart)
        assertEquals("v1", line1.agentId)
        assertEquals("start", line1.alignment)
        assertFalse(line1.oppositeTurn)
        assertEquals(1000L, line1.timestamp)
        assertEquals(4000L, line1.endtime)
        assertEquals(2, line1.text.size)
        assertEquals("Première ", line1.text[0].text)
        assertEquals(1000L, line1.text[0].timestamp)
        assertEquals(2000L, line1.text[0].endtime)

        // Chœur x-bg
        assertTrue(line1.background)
        assertEquals(1, line1.backgroundText.size)
        assertEquals("écho", line1.backgroundText[0].text)
        assertEquals(2500L, line1.backgroundText[0].timestamp)
        assertEquals(3500L, line1.backgroundText[0].endtime)

        // Ligne 2 : chanteur v2 (duo : alternance vers 'end' et oppositeTurn = true)
        val line2 = lines[1]
        assertEquals("Verse", line2.songPart)
        assertEquals("v2", line2.agentId)
        assertEquals("end", line2.alignment)
        assertTrue(line2.oppositeTurn)
        assertFalse(line2.background)
        assertEquals(5000L, line2.timestamp)
        assertEquals(8000L, line2.endtime)
        assertEquals(2, line2.text.size)
        assertEquals("Deuxième ", line2.text[0].text)
    }

    @Test
    fun testParseTTMLTimeVariousFormats() {
        // Unités
        assertEquals(220L, LyricsParsers.parseTTMLTime("220ms"))
        assertEquals(2600L, LyricsParsers.parseTTMLTime("2.6s"))
        assertEquals(120_000L, LyricsParsers.parseTTMLTime("2m"))
        assertEquals(3_600_000L, LyricsParsers.parseTTMLTime("1h"))

        // Formats sexagésimaux
        assertEquals(2600L, LyricsParsers.parseTTMLTime("02.600"))
        assertEquals(71_726L, LyricsParsers.parseTTMLTime("1:11.726"))
        assertEquals(3_661_500L, LyricsParsers.parseTTMLTime("01:01:01.500"))
    }
}
