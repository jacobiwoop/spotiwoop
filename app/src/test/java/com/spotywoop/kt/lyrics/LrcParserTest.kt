package com.spotywoop.kt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test de l'analyseur de sous-titres LRC ([mm:ss.xx] / [mm:ss.xxx]).
 */
class LrcParserTest {

    @Test
    fun testParseLrcSubtitles() {
        val lrc = """
            [ti:Test Title]
            [ar:Test Artist]
            [00:02.50]Première ligne de test
            [00:06.123]Deuxième ligne avec millisecondes
            [00:10.00]
            [00:15.80]Troisième ligne après pause instrumentale
        """.trimIndent()

        val lines = LyricsParsers.parseLrcSubtitles(lrc)

        // La ligne vide à 00:10.00 doit être ignorée (pause instrumentale)
        assertEquals(3, lines.size)

        // Ligne 1 : 00:02.50 -> 2500 ms, fin à 00:06.123 -> 6120 ms
        val l1 = lines[0]
        assertEquals(2500L, l1.timestamp)
        assertEquals(6120L, l1.endtime)
        assertEquals("Première ligne de test", l1.text[0].text)
        assertTrue(l1.text[0].lineSynced)
        assertFalse(l1.isWordSynced == true)

        // Ligne 2 : 00:06.123 -> 6120 ms (123ms arrondi à 12cs -> 120ms), fin à 10000 ms (la pause ignorée sert d'endtime)
        val l2 = lines[1]
        assertEquals(6120L, l2.timestamp)
        assertEquals(10000L, l2.endtime)
        assertEquals("Deuxième ligne avec millisecondes", l2.text[0].text)

        // Ligne 3 : 00:15.80 -> 15800 ms, dernière ligne -> endtime = timestamp + 5000 = 20800 ms
        val l3 = lines[2]
        assertEquals(15800L, l3.timestamp)
        assertEquals(20800L, l3.endtime)
        assertEquals("Troisième ligne après pause instrumentale", l3.text[0].text)
    }
}
